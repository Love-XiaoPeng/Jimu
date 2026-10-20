package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Draft;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Segment;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ComposeReportPlugin;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportComposerTest {
  private final ReportComposer composer = new ReportComposer();
  private final String url = "https://fixture.example/source";
  private final String quote = "中文资料包含 `https://api.example.test/anthropic`，端点仅作标识。";
  private final Instant time = Instant.parse("2026-10-20T10:00:00Z");

  private Source source(String id, String ref, int status, String body, boolean truncated) {
    String output =
        Payloads.json(
            new FetchOutput(
                status,
                ref,
                "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + ref + "\n\n" + body,
                truncated));
    return new Source(id, output, Payloads.hash(output));
  }

  private ReportContext context() {
    return new ReportContext(time, List.of(source("fetch", url, 200, "原文开始。 " + quote, false)));
  }

  private Segment text(String value, String ref, String evidence) {
    return new Segment("text", value, null, null, ref, evidence);
  }

  private Segment endpoint(String value, String evidence) {
    return new Segment("endpoint", null, "https", value, url, evidence);
  }

  private Draft draft(Segment segment) {
    return new Draft(
        1, "本地报告", List.of(text("受控摘要", url, quote)), List.of(segment), List.of("合成输入。"));
  }

  private Draft valid() {
    return draft(endpoint("api.example.test/anthropic", quote));
  }

  private void rejects(String code, Draft draft, ReportContext context) {
    assertEquals(
        code,
        assertThrows(TaskFailure.class, () -> composer.compose("report.md", draft, context))
            .code());
  }

  @Test
  void deterministicSixSectionsKeepProtocolIdentifierAndOnlyFetchedLinks() {
    var result = composer.compose("report.md", valid(), context());
    assertEquals(result, composer.compose("report.md", valid(), context()));
    assertEquals(6, result.markdown().lines().filter(s -> s.startsWith("## ")).count());
    assertTrue(result.markdown().contains("`api.example.test/anthropic`（协议 `https`）"));
    assertFalse(result.markdown().contains("https://api.example.test"));
    assertTrue(result.markdown().contains(time.toString()));
    assertEquals(Payloads.hash(Payloads.json(valid())), result.draftSha256());
    var binding = result.bindings().getFirst();
    assertEquals("原文开始。 ".getBytes(StandardCharsets.UTF_8).length, binding.evidenceOffset());
    assertEquals(quote.getBytes(StandardCharsets.UTF_8).length, binding.evidenceLength());
    assertEquals(Payloads.hash(quote), binding.quoteSha256());
    assertEquals(context().sources().getFirst().digest(), binding.outputDigest());
    var raw =
        new FilePort.Snapshot(
            "report.md",
            1,
            Payloads.hash(result.markdown()),
            result.markdown().getBytes(StandardCharsets.UTF_8).length,
            result.markdown());
    assertDoesNotThrow(() -> new ReportPolicy().validate(raw, context()));
    assertThrows(
        TaskFailure.class,
        () ->
            new ReportPolicy()
                .validate(
                    new FilePort.Snapshot(
                        raw.filePath(),
                        1,
                        Payloads.hash(raw.content() + "\n`https://api.example.test/anthropic`，"),
                        raw.size()
                            + "\n`https://api.example.test/anthropic`，"
                                .getBytes(StandardCharsets.UTF_8)
                                .length,
                        raw.content() + "\n`https://api.example.test/anthropic`，"),
                    context()));
  }

  @Test
  void arbitraryUrlsUnknownSourcesFabricatedQuotesAndUnsafeEndpointsRejectWholeDraft() {
    for (String value :
        List.of(
            "https://unfetched.example/path",
            "`https://api.example.test/anthropic`，",
            "[来源](https://fixture.example/source)"))
      rejects("REPORT_DRAFT_RAW_URL_FORBIDDEN", draft(text(value, url, quote)), context());
    rejects("REPORT_DRAFT_SOURCE_UNKNOWN", draft(text("摘要", url + "/other", quote)), context());
    rejects("REPORT_DRAFT_EVIDENCE_MISMATCH", draft(text("摘要", url, "伪造原文")), context());
    for (String value :
        List.of("api.example.test", "api.example.test/anth", "api.example.test/anthropic/extra"))
      rejects("REPORT_DRAFT_ENDPOINT_UNSUPPORTED", draft(endpoint(value, quote)), context());
    for (String value :
        List.of(
            "user:pass@api.example.test/anthropic",
            "api.example.test/anthropic?q=1",
            "api.example.test/anthropic#fragment",
            "api.example.test/`anthropic",
            "api.example.test/ anthropic"))
      rejects("REPORT_DRAFT_ENDPOINT_INVALID", draft(endpoint(value, quote)), context());
    // A model-cropped quote must not turn a saved endpoint prefix into a complete token.
    rejects(
        "REPORT_DRAFT_ENDPOINT_UNSUPPORTED",
        draft(endpoint("api.example.test/anth", "https://api.example.test/anth")),
        context());
    rejects("REPORT_DRAFT_EVIDENCE_MISMATCH", draft(text("摘要", url, "Source: " + url)), context());
  }

  @Test
  void sharedEvidencePredicateRejectsUnusableAmbiguousOrCorruptSources() {
    for (Source source :
        List.of(
            source("fetch", url, 200, "", false),
            source(
                "fetch",
                url,
                200,
                "Login required before accessing this page and all its contents.",
                false),
            source("fetch", url, 500, quote, false),
            source("fetch", url, 200, quote, true),
            source(
                "fetch",
                url,
                200,
                "Error page: the requested service is unavailable at this time.",
                false)))
      rejects("REPORT_DRAFT_SOURCE_UNKNOWN", valid(), new ReportContext(time, List.of(source)));
    rejects(
        "REPORT_DRAFT_SOURCE_AMBIGUOUS",
        valid(),
        new ReportContext(
            time,
            List.of(
                context().sources().getFirst(),
                source("second", url, 200, quote + "different version", false))));
    var saved = context().sources().getFirst();
    rejects(
        "SNAPSHOT_CORRUPT",
        valid(),
        new ReportContext(
            time, List.of(new Source(saved.invocationId(), saved.output(), "0".repeat(64)))));
    var partial =
        new ReportContext(time, List.of(saved, source("bad", url + "/bad", 500, "", false)));
    assertTrue(composer.compose("report.md", valid(), partial).markdown().contains("部分来源失败"));
    var unused =
        new ReportContext(
            time, List.of(saved, source("unused", url + "/unused", 200, quote, false)));
    String result = composer.compose("report.md", valid(), unused).markdown();
    assertTrue(result.contains("部分可用来源未用"));
    assertFalse(result.contains("部分来源失败"));
    assertFalse(result.contains(url + "/unused"));
  }

  @Test
  void strictRawParsingAndSchemaRefuseShapeBeforeAdmission() throws Exception {
    var contract = new WorkspaceContracts().load("compose_report");
    String raw = Payloads.json(new ComposeReportPlugin.Input("report.md", valid()));
    assertDoesNotThrow(() -> contract.in().validate(ReportComposer.parse(raw)));
    for (String bad :
        List.of(
            raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("\"version\":1", "\"version\":1.5"),
            raw.replace("\"draft\":{", "\"draft\":{\"receipt\":{},"),
            raw.replace("\"file_path\":", "\"tenant\":\"foreign\",\"file_path\":")))
      assertThrows(
          IllegalArgumentException.class, () -> contract.in().validate(ReportComposer.parse(bad)));
    assertThrows(
        java.io.IOException.class,
        () -> ReportComposer.parse(raw.replace("\"version\":1", "\"version\":1,\"version\":1")));
    assertThrows(java.io.IOException.class, () -> ReportComposer.parse(raw + "{}"));
    assertThrows(IllegalArgumentException.class, () -> ReportComposer.parse(" ".repeat(65537)));
    for (String bad : List.of("", " ", "null", "[]", "1"))
      assertThrows(IllegalArgumentException.class, () -> ReportComposer.parse(bad));
  }

  @Test
  void confirmedReceiptMustMatchExactInputSourceAndFileVersion() {
    var result = composer.compose("report.md", valid(), context());
    String input = Payloads.json(new ComposeReportPlugin.Input("report.md", valid()));
    long size = result.markdown().getBytes(StandardCharsets.UTF_8).length;
    var written = new FilePort.Written("report.md", 1, Payloads.hash(result.markdown()), size);
    String receipt = Payloads.json(result.receipt(written));
    assertTrue(
        composer.matches(
            input,
            receipt,
            new ReportFile("report.md", 1, written.sha256(), size, "报告"),
            context()));
    assertFalse(
        composer.matches(
            input,
            receipt,
            new ReportFile("report.md", 2, written.sha256(), size, "报告"),
            context()));
    assertFalse(
        composer.matches(
            input, receipt, new ReportFile("report.md", 1, "f".repeat(64), size, "报告"), context()));
    assertFalse(
        composer.matches(
            input,
            receipt.replace(result.draftSha256(), "e".repeat(64)),
            new ReportFile("report.md", 1, written.sha256(), size, "报告"),
            context()));
    assertThrows(
        TaskFailure.class,
        () ->
            composer.matches(
                input,
                "{}",
                new ReportFile("report.md", 1, written.sha256(), size, "报告"),
                context()));
  }

  @Test
  void quoteOffsetsReferToSavedBodyIncludingLeadingWhitespace() {
    var context =
        new ReportContext(
            time, List.of(source("fetch", url, 200, " \n中文 " + quote + "\n ", false)));
    var binding = composer.compose("report.md", valid(), context).bindings().getFirst();
    assertEquals(" \n中文 ".getBytes(StandardCharsets.UTF_8).length, binding.evidenceOffset());
  }

  @Test
  void ordinaryAndSsePreserveRawDraftForStrictGatewayWithoutChangingOldTools() throws Exception {
    String duplicate = "{\"draft\":{\"version\":1,\"version\":1}}";
    for (String arguments :
        List.of(duplicate, " ", "{}" + " ".repeat(65533), " ".repeat(65536), "中".repeat(22000))) {
      boolean oversized = arguments.getBytes(StandardCharsets.UTF_8).length > 65535;
      var call =
          Map.of(
              "id",
              "fixture",
              "type",
              "function",
              "function",
              Map.of("name", "compose_report", "arguments", arguments));
      var response =
          Map.of(
              "choices",
              List.of(
                  Map.of(
                      "index",
                      0,
                      "message",
                      Map.of("tool_calls", List.of(call)),
                      "finish_reason",
                      "tool_calls")),
              "usage",
              Map.of("prompt_tokens", 2, "completion_tokens", 1, "total_tokens", 3));
      byte[] ordinary = Payloads.json(response).getBytes(StandardCharsets.UTF_8);
      var protocol = new OpenAiProtocol();
      if (!oversized)
        assertEquals(
            arguments,
            protocol
                .parse(ordinary, false, List.of("compose_report"))
                .calls()
                .getFirst()
                .arguments());
      var stream =
          Map.of(
              "choices",
              List.of(
                  Map.of(
                      "index",
                      0,
                      "delta",
                      Map.of(
                          "tool_calls",
                          List.of(
                              Map.of(
                                  "index",
                                  0,
                                  "id",
                                  "fixture",
                                  "type",
                                  "function",
                                  "function",
                                  call.get("function")))),
                      "finish_reason",
                      "tool_calls")));
      byte[] sse =
          ("data: "
                  + Payloads.json(stream)
                  + "\n\ndata: "
                  + Payloads.json(Map.of("choices", List.of(), "usage", response.get("usage")))
                  + "\n\ndata: [DONE]\n\n")
              .getBytes(StandardCharsets.UTF_8);
      if (oversized) {
        for (boolean streaming : List.of(false, true)) {
          var failure =
              assertThrows(
                  OpenAiProtocol.InvalidResponse.class,
                  () ->
                      protocol.parse(
                          streaming ? sse : ordinary, streaming, List.of("compose_report")));
          assertEquals(3, failure.usage().totalTokens());
        }
      } else
        assertEquals(
            arguments,
            protocol.parse(sse, true, List.of("compose_report")).calls().getFirst().arguments());
      if (arguments.startsWith("{}")) assertDoesNotThrow(() -> ReportComposer.parse(arguments));
      else assertThrows(Exception.class, () -> ReportComposer.parse(arguments));
      String legacy =
          new String(ordinary, StandardCharsets.UTF_8).replace("compose_report", "write");
      if (!arguments.startsWith("{}"))
        assertThrows(
            java.io.IOException.class,
            () -> protocol.parse(legacy.getBytes(StandardCharsets.UTF_8), false, List.of("write")));
    }
  }

  @Test
  void plainFieldsEscapeMarkupAndRejectControlOrUrlInjection() {
    var escaped =
        composer
            .compose(
                "report.md",
                draft(text("<script>& &#104; [假引文](host) `代码`", url, quote)),
                context())
            .markdown();
    assertTrue(escaped.contains("&lt;script&gt;&amp; &amp;"));
    assertTrue(escaped.contains("\\[假引文\\]\\(host\\) \\`代码\\`"));
    for (String value : List.of("换\n行", "双向\u202e标记", "\u0000", " "))
      rejects("REPORT_DRAFT_TEXT_INVALID", draft(text(value, url, quote)), context());
  }
}
