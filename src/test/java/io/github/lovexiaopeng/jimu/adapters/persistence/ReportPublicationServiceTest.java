package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportPublications;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ReportPublicationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 首错/短路、惰性及事务内编排；codec 使用原适配器，不替代真实 MySQL 入口。 */
class ReportPublicationServiceTest {
  static final Lease LEASE =
      new Lease("t", "a", "task", "attempt", 3, "w", null, "g", List.of(), 1);
  static final ReportFile FILE =
      new ReportFile("report.md", 1, "a".repeat(64), 3, "text/markdown; charset=utf-8");

  static class Harness {
    final List<String> trace = new ArrayList<>();
    final MapperReportPublications codec = new MapperReportPublications(null);
    boolean inside, report = true, matches = true;
    String tool = "present", state = "RUNNING", artifactState = "STAGING";
    String input = Payloads.json(Map.of("files", List.of(Map.of("path", FILE.path()))));
    String receipt = Payloads.json(FILE),
        inputDigest = Payloads.hash("{}"),
        outputDigest = Payloads.hash("{}");
    PluginPin pin;
    String failAt;
    Throwable failure;
    int ids, stagedCount = 1;
    long limit = 100;
    List<ReportPublications.Artifact> existing = List.of(), others = List.of();
    List<ReportPublications.Composition> confirmed = List.of();
    final ReportPublications.Task task = proxy(ReportPublications.Task.class);
    final ReportPublications.Invocation invocation = proxy(ReportPublications.Invocation.class);
    final ReportPublications.Composition composition = proxy(ReportPublications.Composition.class);
    final ReportPublications.Artifact artifact = proxy(ReportPublications.Artifact.class);
    final ReportPublications.Budget budget = proxy(ReportPublications.Budget.class);
    final ReportPublications port = proxy(ReportPublications.class);
    final ReportPublicationServiceImpl service = new ReportPublicationServiceImpl(port);

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, a) -> {
                String name = m.getName();
                trace.add(name);
                if (name.equals("transaction")) {
                  assertSame(LEASE, a[0]);
                  assertFalse(inside);
                  inside = true;
                  try {
                    return ((Supplier<?>) a[1]).get();
                  } finally {
                    inside = false;
                    trace.add("end");
                  }
                }
                assertTrue(inside, name);
                if (name.equals(failAt)) throw failure;
                return switch (name) {
                  case "fenced" -> {
                    assertSame(LEASE, a[0]);
                    assertEquals(true, a[1]);
                    yield task;
                  }
                  case "publicReport" -> report;
                  case "invocation" -> invocation;
                  case "tool" -> tool;
                  case "state" -> type == ReportPublications.Artifact.class ? artifactState : state;
                  case "input" -> type == ReportPublications.Composition.class ? "{}" : input;
                  case "read" -> codec.read((String) a[0]);
                  case "receipt" ->
                      type == ReportPublications.Artifact.class
                          ? receipt
                          : codec.receipt((String) a[0]);
                  case "json" -> codec.json((ReportFile) a[0]);
                  case "hash" ->
                      type == ReportPublications.Artifact.class
                          ? FILE.sha256()
                          : codec.hash((String) a[0]);
                  case "compositionPin" -> pin;
                  case "confirmed" -> confirmed;
                  case "output" -> "{}";
                  case "inputDigest" -> inputDigest;
                  case "outputDigest" -> outputDigest;
                  case "version" -> pin.version();
                  case "digest" -> pin.digest();
                  case "textMatches", "imageMatches" -> {
                    assertSame(FILE, a[2]);
                    yield matches;
                  }
                  case "matching" -> existing;
                  case "otherProducer" -> others;
                  case "bucket" -> budget;
                  case "held" -> 0L;
                  case "limit" -> limit;
                  case "newId" -> "id" + ++ids;
                  case "insert" -> {
                    assertSame(FILE, a[4]);
                    yield null;
                  }
                  case "stagedIds" -> java.util.Collections.nCopies(stagedCount, artifact);
                  case "artifacts" -> List.of(artifact);
                  case "id" -> "id1";
                  case "uploadId" -> "id2";
                  case "snapshotKey" -> null;
                  case "receiptDigest" -> Payloads.hash(receipt);
                  case "event" -> {
                    trace.add(a[0] + "/" + a[1]);
                    yield null;
                  }
                  default -> null;
                };
              });
    }

    void code(String expected, Runnable work) {
      assertEquals(expected, assertThrows(TaskFailure.class, work::run).code());
      assertEquals("end", trace.getLast());
    }

    void stage() {
      service.stage(LEASE, "present", List.of(FILE));
    }

    void publish() {
      service.succeed(
          LEASE, "present", List.of(new Publication("id1", "id2-" + FILE.sha256())), output());
    }

    String output() {
      return Payloads.json(
          Map.of(
              "files",
              List.of(
                  Map.of(
                      "artifactId",
                      "id1",
                      "path",
                      FILE.path(),
                      "version",
                      FILE.version(),
                      "sha256",
                      FILE.sha256(),
                      "size",
                      FILE.size(),
                      "mediaType",
                      FILE.mediaType()))));
    }
  }

  @Test
  void stagingOwnsOrderedRulesAndWritesInOneLazyTransaction() {
    var h = new Harness();
    assertTrue(h.trace.isEmpty());
    assertEquals(
        List.of(new Upload("id1", "id2", "STAGING", FILE.sha256(), 3, null)),
        h.service.stage(LEASE, "present", List.of(FILE)));
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "publicReport",
            "invocation",
            "tool",
            "state",
            "input",
            "read",
            "compositionPin",
            "json",
            "hash",
            "matching",
            "otherProducer",
            "bucket",
            "held",
            "limit",
            "newId",
            "newId",
            "insert",
            "hold",
            "reserve",
            "event",
            "staging:id1/artifact.staging",
            "stagedIds",
            "end"),
        h.trace);
  }

  @Test
  void qualificationWinsOverNullFilesAndToolShortCircuitsState() {
    var h = new Harness();
    h.report = false;
    h.code("REPORT_POLICY_REQUIRED", () -> h.service.stage(LEASE, "present", null));
    assertFalse(h.trace.contains("invocation"));
    h.report = true;
    h.tool = "write";
    h.trace.clear();
    h.code("INVOCATION_STATE", () -> h.service.stage(LEASE, "present", null));
    assertFalse(h.trace.contains("state"));
    h.tool = "present";
    assertThrows(NullPointerException.class, () -> h.service.stage(LEASE, "present", null));
  }

  @Test
  void fileCardinalityDuplicatesAndPathOrderFailBeforeComposition() {
    var h = new Harness();
    for (var files :
        List.of(
            List.<ReportFile>of(), List.of(FILE, FILE), List.of(FILE, FILE, FILE, FILE, FILE))) {
      h.trace.clear();
      h.code("TOOL_INPUT_INVALID", () -> h.service.stage(LEASE, "present", files));
      assertFalse(h.trace.contains("input"));
    }
    h.input = "{\"files\":[{\"path\":\"other\"}]}";
    h.trace.clear();
    h.code("INVOCATION_MISMATCH", h::stage);
    assertFalse(h.trace.contains("compositionPin"));
  }

  @Test
  void compositionPrecedesFileValidationAndUsesOrderedDigestAndVersionBranches() {
    var h = new Harness();
    h.pin = new PluginPin("0.1.0", "pin", 1);
    var bad = new ReportFile(FILE.path(), 0, null, 0, null);
    h.code("REPORT_COMPOSITION_REQUIRED", () -> h.service.stage(LEASE, "present", List.of(bad)));
    h.confirmed = List.of(h.composition);
    h.inputDigest = "bad";
    h.trace.clear();
    h.code("REPORT_COMPOSITION_INVALID", h::stage);
    assertFalse(h.trace.contains("outputDigest"));
    assertFalse(h.trace.contains("textMatches"));
    h.inputDigest = Payloads.hash("{}");
    h.trace.clear();
    h.stage();
    assertTrue(h.trace.contains("textMatches"));
    assertFalse(h.trace.contains("imageMatches"));
    h.pin = new PluginPin("0.2.0", "pin", 1);
    h.trace.clear();
    h.stage();
    assertTrue(h.trace.contains("imageMatches"));
    assertFalse(h.trace.contains("textMatches"));
    h.matches = false;
    h.code("REPORT_COMPOSITION_REQUIRED", h::stage);
  }

  @Test
  void replayUsesFirstReceiptAndBypassesBudgetWhileMismatchWins() {
    var h = new Harness();
    h.existing = List.of(h.artifact, h.artifact);
    h.limit = 0;
    h.stage();
    assertFalse(h.trace.contains("bucket"));
    assertFalse(h.trace.contains("insert"));
    h.receipt = "{}";
    h.code("ARTIFACT_MISMATCH", h::stage);
    h.existing = List.of();
    h.others = List.of(h.artifact);
    h.trace.clear();
    h.code("REPORT_ALREADY_STAGED", h::stage);
    assertFalse(h.trace.contains("bucket"));
    h.others = List.of();
    h.code("STORAGE_BUDGET_EXCEEDED", h::stage);
  }

  @Test
  void publicationMatchesIdsAndReceiptBeforePerItemAndToolSettlement() {
    var h = new Harness();
    h.publish();
    assertTrue(h.trace.indexOf("publish") < h.trace.indexOf("settleStorage"));
    assertTrue(h.trace.indexOf("settleStorage") < h.trace.indexOf("succeed"));
    assertTrue(h.trace.indexOf("succeed") < h.trace.indexOf("settleTool"));
    assertEquals("result:present/tool.succeeded", h.trace.get(h.trace.size() - 2));
    h.artifactState = "PUBLISHED";
    h.trace.clear();
    h.code("ARTIFACT_MISMATCH", h::publish);
    assertFalse(h.trace.contains("receiptDigest"));
    assertFalse(h.trace.contains("publish"));
    h.artifactState = "STAGING";
    h.trace.clear();
    h.code(
        "ARTIFACT_MISMATCH",
        () ->
            h.service.succeed(
                LEASE, "present", List.of(new Publication("missing", "key")), h.output()));
    assertFalse(h.trace.contains("receipt"));
    h.code(
        "TOOL_OUTPUT_INVALID",
        () ->
            h.service.succeed(
                LEASE, "present", List.of(new Publication("id1", "key")), "{\"files\":{}}"));
  }

  @Test
  void fileNullDigestKeepsOriginalFaultAndFinalCountStillRejects() {
    var h = new Harness();
    assertThrows(
        NullPointerException.class,
        () ->
            h.service.stage(
                LEASE,
                "present",
                List.of(new ReportFile(FILE.path(), 1, null, 3, FILE.mediaType()))));
    assertFalse(h.trace.contains("json"));
    h.stagedCount = 2;
    h.code("ARTIFACT_MISMATCH", h::stage);
    assertTrue(h.trace.contains("reserve"));
  }

  @Test
  void eachOutputReceiptFieldAndKeyMustMatchBeforePublishing() {
    for (String key : List.of("artifactId", "path", "version", "sha256", "size", "mediaType")) {
      var h = new Harness();
      var item = new java.util.LinkedHashMap<String, Object>();
      item.put("artifactId", "id1");
      item.put("path", FILE.path());
      item.put("version", 1);
      item.put("sha256", FILE.sha256());
      item.put("size", 3);
      item.put("mediaType", FILE.mediaType());
      item.put(key, key.equals("version") || key.equals("size") ? 9 : "wrong");
      h.code(
          "ARTIFACT_MISMATCH",
          () ->
              h.service.succeed(
                  LEASE,
                  "present",
                  List.of(new Publication("id1", "id2-" + FILE.sha256())),
                  Payloads.json(Map.of("files", List.of(item)))));
      assertFalse(h.trace.contains("publish"));
    }
    var h = new Harness();
    h.code(
        "ARTIFACT_MISMATCH",
        () ->
            h.service.succeed(
                LEASE, "present", List.of(new Publication("id1", "wrong")), h.output()));
    assertFalse(h.trace.contains("receiptDigest"));
  }

  @Test
  void failuresAndErrorsPropagateSameInstanceWithoutFollowingWrites() {
    for (String at :
        List.of("fenced", "invocation", "confirmed", "insert", "reserve", "stagedIds")) {
      var h = new Harness();
      h.pin = new PluginPin("0.1.0", "pin", 1);
      h.confirmed = List.of(h.composition);
      h.failAt = at;
      var failure = new IllegalStateException(at);
      h.failure = failure;
      assertSame(failure, assertThrows(IllegalStateException.class, h::stage));
      assertFalse(h.inside);
      assertEquals("end", h.trace.getLast());
    }
    var h = new Harness();
    h.failAt = "publish";
    var error = new AssertionError("publish");
    h.failure = error;
    assertSame(error, assertThrows(AssertionError.class, h::publish));
    assertFalse(h.trace.contains("succeed"));
  }
}
