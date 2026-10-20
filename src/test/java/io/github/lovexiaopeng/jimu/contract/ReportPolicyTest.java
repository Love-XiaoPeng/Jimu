package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportPolicyTest {
  private final ReportPolicy policy = new ReportPolicy();
  private final Instant time = Instant.parse("2026-10-16T00:00:00Z");
  private final String url = "https://fixture.example/source";

  private Source source(int status, String text, boolean truncated) {
    String output =
        Payloads.json(
            new FetchOutput(
                status,
                url,
                "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + url + "\n\n" + text,
                truncated));
    return new Source("host-invocation", output, Payloads.hash(output));
  }

  private ReportContext context() {
    return new ReportContext(
        time,
        List.of(
            source(200, "Actual fetched article body with sufficient useful evidence.", false)));
  }

  private String report() {
    return "## 主题\n本地报告\n## 摘要\n证据摘要\n## 结论\n结论见[来源]("
        + url
        + ")\n## 来源链接\n[来源]("
        + url
        + ")\n## 生成时间\n"
        + time
        + "\n## 局限\n仅本地替身验证。";
  }

  private FilePort.Snapshot file(String content) {
    return new FilePort.Snapshot(
        "reports/result.md",
        1,
        Payloads.hash(content),
        content.getBytes(StandardCharsets.UTF_8).length,
        content);
  }

  private void rejects(String code, String content, ReportContext context) {
    assertEquals(
        code,
        assertThrows(TaskFailure.class, () -> policy.validate(file(content), context)).code());
  }

  @Test
  void actualReceiptAndTaskEvidencePermitReport() {
    assertDoesNotThrow(() -> policy.validate(file(report()), context()));
    var wrong =
        new FilePort.Snapshot(
            "reports/result.md", 1, "0".repeat(64), file(report()).size(), report());
    assertEquals(
        "REPORT_INVALID",
        assertThrows(TaskFailure.class, () -> policy.validate(wrong, context())).code());
  }

  @Test
  void summariesLoginErrorSnippetEmptyAndTruncatedAreNotEvidence() {
    for (var invalid :
        List.of(
            source(404, "Error page with enough characters to pass a length-only check.", false),
            source(200, "Error page: the requested service is unavailable at this time.", false),
            source(200, "", false),
            source(200, "Login required before accessing this page and all its contents.", false),
            source(200, "Search snippet containing an interesting but unfetched claim.", false),
            source(200, "An otherwise valid article with enough useful content to count.", true)))
      rejects("NO_EVIDENCE", report(), new ReportContext(time, List.of(invalid)));
    rejects("NO_EVIDENCE", report(), new ReportContext(time, List.of()));
  }

  @Test
  void foreignCitationMissingSectionAndModelClockRefused() {
    rejects(
        "REPORT_CITATION_INVALID", report().replace(url, "https://other.example/claim"), context());
    rejects("REPORT_CITATION_INVALID", report().replace("结论见[来源](" + url + ")", "没有引文"), context());
    rejects("REPORT_INVALID", report().replace("## 摘要", "## 其他"), context());
    rejects("REPORT_INVALID", report().replace(time.toString(), "2020-01-01T00:00:00Z"), context());
    rejects("REPORT_INVALID", report() + "\n## 主题\n重复", context());
  }

  @Test
  void partialFailureMustBeDisclosedAndSourceDigestIsChecked() {
    var partial =
        new ReportContext(
            time, List.of(context().sources().getFirst(), source(500, "Server failure", false)));
    rejects("REPORT_LIMITATION_REQUIRED", report(), partial);
    assertDoesNotThrow(() -> policy.validate(file(report() + " 部分来源失败。"), partial));
    var source = context().sources().getFirst();
    rejects(
        "SNAPSHOT_CORRUPT",
        report(),
        new ReportContext(
            time, List.of(new Source(source.invocationId(), source.output(), "0".repeat(64)))));
  }

  @Test
  void oneMiBUtf8BoundaryIsEnforced() {
    int base = report().getBytes(StandardCharsets.UTF_8).length;
    for (int length : List.of(1048575, 1048576))
      assertDoesNotThrow(
          () -> policy.validate(file(report() + "x".repeat(length - base)), context()));
    rejects("REPORT_INVALID", report() + "x".repeat(1048577 - base), context());
  }
}
