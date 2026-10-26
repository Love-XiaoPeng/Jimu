package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy;
import io.github.lovexiaopeng.jimu.kernel.artifact.domain.ReportValidationPolicy;
import io.github.lovexiaopeng.jimu.kernel.artifact.domain.ReportValidationPolicy.FetchedSource;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 迁移风险：真实旧入口、首错顺序、惰性解码、来源去重及Unicode边界。 */
class ReportValidationPolicyTest {
  private static final Instant TIME = Instant.parse("2026-10-16T01:02:03.123456Z");
  private static final String URL = "https://fixture.example/source/0";
  private static final String BODY = "Actual fetched article body with sufficient useful evidence.";
  private final ReportPolicy adapter = new ReportPolicy();

  @Test
  void invalidFileNeverDecodesEvenMalformedSource() {
    String report = report();
    var good = file(report);
    var malformed = context(List.of(raw("{")));
    for (var invalid :
        List.of(
            file(""),
            new FilePort.Snapshot("report.MD", 1, good.sha256(), good.size(), report),
            new FilePort.Snapshot("report.md", 0, good.sha256(), good.size(), report),
            new FilePort.Snapshot("report.md", -1, good.sha256(), good.size(), report),
            new FilePort.Snapshot("report.md", 1, "bad", good.size(), report),
            new FilePort.Snapshot("report.md", 1, good.sha256(), report.length(), report)))
      assertCode("REPORT_INVALID", () -> adapter.validate(invalid, malformed));
  }

  @Test
  void digestAndDecodeStayInterleavedAndStopAtFirstCorruptSource() {
    var first = source(URL, 200, BODY, false);
    var corrupt = new Source("second", "corrupt", "bad");
    var events = new ArrayList<String>();
    var policy =
        new ReportValidationPolicy(
            value -> {
              events.add("hash:" + value);
              return Payloads.hash(value);
            },
            value -> {
              events.add("decode:" + value);
              return decode(value);
            });
    assertCode(
        "SNAPSHOT_CORRUPT",
        () -> policy.validate(file(report()), context(List.of(first, corrupt, raw("{")))));
    assertEquals(
        List.of(
            "hash:" + report(),
            "hash:" + first.output(),
            "decode:" + first.output(),
            "hash:corrupt"),
        events);
  }

  @Test
  void decoderFailuresPropagateSameInstanceWithoutTouchingLaterSource() {
    for (var failure :
        List.of(new TaskFailure(409, "ORIGINAL_FAILURE"), new IllegalArgumentException("decode"))) {
      var events = new ArrayList<String>();
      var policy =
          new ReportValidationPolicy(
              value -> {
                events.add("hash:" + value);
                return Payloads.hash(value);
              },
              value -> {
                events.add("decode:" + value);
                throw failure;
              });
      assertSame(
          failure,
          assertThrows(
              RuntimeException.class,
              () -> policy.validate(file(report()), context(List.of(raw("first"), raw("later"))))));
      assertEquals(List.of("hash:" + report(), "hash:first", "decode:first"), events);
    }
  }

  @Test
  void digestFailureAlsoPropagatesSameInstanceBeforeDecode() {
    var failure = new TaskFailure(409, "DIGEST_FAILURE");
    var policy =
        new ReportValidationPolicy(
            value -> {
              throw failure;
            },
            value -> {
              throw new AssertionError("must not decode");
            });
    assertSame(failure, assertThrows(TaskFailure.class, () -> policy.evidence(raw("first"))));
    assertSame(
        failure,
        assertThrows(TaskFailure.class, () -> policy.validate(file(report()), context(List.of()))));
  }

  @Test
  void actualAdapterKeepsMalformedJsonAndDigestFailureOrder() {
    var malformed = raw("{");
    var corrupt = new Source("bad-digest", "{}", "bad");
    assertCode(
        "SNAPSHOT_CORRUPT",
        () -> adapter.validate(file(report()), context(List.of(corrupt, malformed))));
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapter.validate(file(report()), context(List.of(malformed, corrupt))));
    assertEquals("Invalid snapshot", failure.getMessage());
    assertTrue(failure.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException);
  }

  @Test
  void eightDistinctSourcesPassNineFailAndDuplicatesDoNotConsumeLimit() {
    var eight = sources(8);
    assertDoesNotThrow(() -> adapter.validate(file(report()), context(eight)));
    assertCode("REPORT_SOURCE_LIMIT", () -> adapter.validate(file(report()), context(sources(9))));
    var duplicates = new ArrayList<>(eight);
    duplicates.addAll(eight);
    assertDoesNotThrow(() -> adapter.validate(file(report()), context(duplicates)));
    duplicates.add(source("https://fixture.example/failed", 500, BODY, false));
    assertCode(
        "REPORT_LIMITATION_REQUIRED", () -> adapter.validate(file(report()), context(duplicates)));
    assertDoesNotThrow(() -> adapter.validate(file(report() + " 部分来源失败。"), context(duplicates)));
  }

  @Test
  void everySourceIsProcessedBeforeCollectionLimitAndSectionChecks() {
    var nineThenMalformed = new ArrayList<>(sources(9));
    nineThenMalformed.add(raw("{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.validate(file("not a report"), context(nineThenMalformed)));
    assertCode(
        "REPORT_SOURCE_LIMIT", () -> adapter.validate(file("not a report"), context(sources(9))));
    assertCode("NO_EVIDENCE", () -> adapter.validate(file("not a report"), context(List.of())));
  }

  @Test
  void sectionsPrecedeCitationsWhichPrecedePartialDisclosure() {
    var partial = new ArrayList<>(sources(1));
    partial.add(source(URL, 500, BODY, false));
    String foreign = report().replace(URL, "https://foreign.example/source");
    assertCode(
        "REPORT_INVALID",
        () -> adapter.validate(file(foreign.replace("## 摘要", "## 其他")), context(partial)));
    assertCode("REPORT_CITATION_INVALID", () -> adapter.validate(file(foreign), context(partial)));
    assertCode(
        "REPORT_LIMITATION_REQUIRED", () -> adapter.validate(file(report()), context(partial)));
    assertDoesNotThrow(() -> adapter.validate(file(report() + " 部分来源失败。"), context(partial)));
  }

  @Test
  void eachSectionMustBeNonemptyAndDuplicatesAreRejected() {
    for (String section : List.of("主题", "摘要", "结论", "来源链接", "生成时间", "局限")) {
      String empty = report().replaceFirst("(?s)(## " + section + "\\n).*?(?=\\n## |$)", "$1  ");
      assertCode("REPORT_INVALID", () -> adapter.validate(file(empty), context(sources(1))));
      assertCode(
          "REPORT_INVALID",
          () -> adapter.validate(file(report() + "\n## " + section + "\n重复"), context(sources(1))));
    }
  }

  @Test
  void unicodeThresholdCountsCodePointsAndPreservesUnstrippedBody() {
    String emoji = new String(Character.toChars(0x1f642));
    for (String point : List.of("字", emoji)) {
      String shortBody = " \n" + point.repeat(31) + "\t ";
      assertFalse(adapter.evidence(source(URL, 200, shortBody, false)).usable());
      String enough = " \n" + point.repeat(32) + "\t ";
      var evidence = adapter.evidence(source(URL, 200, enough, false));
      assertTrue(evidence.usable());
      assertEquals(enough, evidence.body());
      assertEquals(URL, evidence.sourceUrl());
      assertDoesNotThrow(
          () ->
              adapter.validate(file(report()), context(List.of(source(URL, 200, enough, false)))));
    }
  }

  @Test
  void untrustedPrefixMustMatchVersionUrlAndNewlinesExactly() {
    String prefix = "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + URL + "\n\n";
    for (String content :
        List.of(
            BODY,
            " " + prefix + BODY,
            prefix.replace("v1", "v2") + BODY,
            prefix.replace(URL, "https://other.example") + BODY,
            prefix.replace("\n", "\r\n") + BODY)) {
      var evidence =
          adapter.evidence(raw(Payloads.json(new FetchOutput(200, URL, content, false))));
      assertFalse(evidence.usable());
      assertEquals("", evidence.body());
    }
  }

  @Test
  void statusTruncationAndErrorPageRulesRemainUnchanged() {
    for (int status : List.of(199, 300, 404, 500))
      assertFalse(adapter.evidence(source(URL, status, BODY, false)).usable());
    for (int status : List.of(200, 299))
      assertTrue(adapter.evidence(source(URL, status, BODY, false)).usable());
    assertFalse(adapter.evidence(source(URL, 200, BODY, true)).usable());
    for (String marker :
        List.of(
            "sign in",
            "log in",
            "login",
            "authentication required",
            "access denied",
            "captcha",
            "search results",
            "search snippet",
            "error page",
            "not found",
            "service unavailable",
            "something went wrong",
            "登录",
            "访问被拒绝",
            "验证码",
            "错误页面",
            "页面不存在"))
      assertFalse(
          adapter
              .evidence(
                  source(URL, 200, BODY + "\n" + marker.toUpperCase(java.util.Locale.ROOT), false))
              .usable());
  }

  private static FetchedSource decode(String value) {
    var output = Payloads.read(value, FetchOutput.class);
    return new FetchedSource(
        output.httpStatus(), output.sourceUrl(), output.content(), output.truncated());
  }

  private static List<Source> sources(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> source("https://fixture.example/source/" + i, 200, BODY, false))
        .toList();
  }

  private static Source source(String url, int status, String body, boolean truncated) {
    return raw(
        Payloads.json(
            new FetchOutput(
                status,
                url,
                "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + url + "\n\n" + body,
                truncated)));
  }

  private static Source raw(String output) {
    return new Source("host-invocation", output, Payloads.hash(output));
  }

  private static ReportContext context(List<Source> sources) {
    return new ReportContext(TIME, sources);
  }

  private static FilePort.Snapshot file(String content) {
    return new FilePort.Snapshot(
        "reports/result.md",
        1,
        Payloads.hash(content),
        content.getBytes(StandardCharsets.UTF_8).length,
        content);
  }

  private static String report() {
    return "## 主题\n本地报告\n## 摘要\n证据摘要\n## 结论\n结论见[来源]("
        + URL
        + ")\n## 来源链接\n[来源]("
        + URL
        + ")\n## 生成时间\n"
        + TIME
        + "\n## 局限\n仅本地替身验证。";
  }

  private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(TaskFailure.class, action);
    assertEquals(409, failure.status());
    assertEquals(code, failure.code());
  }
}
