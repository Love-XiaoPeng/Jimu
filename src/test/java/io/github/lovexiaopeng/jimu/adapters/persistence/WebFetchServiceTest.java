package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.invocation.port.WebFetchRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.service.impl.WebFetchServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Cancellation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.CheckpointCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Reason;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Synthetic order/first-error evidence, separate from the real public Ledger MySQL calls. */
class WebFetchServiceTest {
  static final Lease LEASE =
      new Lease("t", "a", "task", "attempt", 3, "w", null, "g", List.of(), 1);
  static final WebFetchObservation CANCEL =
      new WebFetchObservation(
          Phase.BODY,
          Failure.CANCELLED,
          new Cancellation(
              Reason.CHECKPOINT_REJECTED, Phase.BODY, 123, CheckpointCode.FENCE_REJECTED, 7));

  static class Harness {
    final List<String> trace = new ArrayList<>();
    final List<List<Object>> events = new ArrayList<>();
    Object tool = "web_fetch", version = "0.3.0", state = "RUNNING", output, outputDigest;
    final Object stepId = new Object();
    String receipt = "proof", failAt;
    RuntimeException failure;
    boolean inside, recorded;
    int bindings = 1;
    List<WebFetchRecords.Receipt> receipts = List.of();
    final WebFetchRecords.Task task = proxy(WebFetchRecords.Task.class);
    final WebFetchRecords.Invocation invocation = proxy(WebFetchRecords.Invocation.class);
    final WebFetchRecords.Step step = proxy(WebFetchRecords.Step.class);
    final WebFetchRecords records = proxy(WebFetchRecords.class);
    final WebFetchServiceImpl service = new WebFetchServiceImpl(records);

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
                  assertFalse(inside);
                  inside = true;
                  try {
                    return ((Supplier<?>) a[0]).get();
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
                    assertEquals(false, a[1]);
                    yield task;
                  }
                  case "lockDiagnosticTask" -> task;
                  case "diagnosticBindings" -> bindings;
                  case "cancellationRecorded" -> recorded;
                  case "invocation" -> invocation;
                  case "tool" -> tool;
                  case "version" -> version;
                  case "state" -> state;
                  case "stepId" -> stepId;
                  case "step" -> {
                    assertSame(stepId, a[1]);
                    yield step;
                  }
                  case "proof" -> {
                    assertSame(task, a[0]);
                    assertSame(step, a[1]);
                    assertSame(invocation, a[2]);
                    yield "proof";
                  }
                  case "receipts" -> receipts;
                  case "output" -> output;
                  case "outputDigest" -> outputDigest;
                  case "hash" -> "hash";
                  case "preserve" -> null;
                  case "code" -> receipt;
                  case "event" -> {
                    events.add(List.of(a));
                    yield null;
                  }
                  default -> throw new AssertionError(name);
                };
              });
    }

    void replay() {
      receipts = List.of(proxy(WebFetchRecords.Receipt.class));
    }

    void preserve() {
      service.preserve(LEASE, "i", "out");
    }

    void reject(String code, Runnable work) {
      assertEquals(code, assertThrows(TaskFailure.class, work::run).code());
    }
  }

  @Test
  void constructionAndNullObservationDoNotReadLeaseOrRows() {
    var h = new Harness();
    assertTrue(h.trace.isEmpty());
    assertThrows(NullPointerException.class, () -> h.service.observe(null, null, null));
    assertEquals(List.of("transaction", "end"), h.trace);
  }

  @Test
  void observationKeepsRawEqualityAndLazyQualificationOrder() {
    for (int field = 0; field < 3; field++) {
      var h = new Harness();
      if (field == 0) h.tool = new StringBuilder("web_fetch");
      if (field == 1) h.version = null;
      if (field == 2) h.state = 7;
      h.reject(
          "INVOCATION_STATE",
          () -> h.service.observe(LEASE, "i", new WebFetchObservation(null, null)));
      assertEquals(
          List.of("transaction", "fenced", "invocation", "tool", "version", "state", "end")
              .subList(0, 4 + field),
          h.trace.subList(0, 4 + field));
      assertEquals(5 + field, h.trace.size());
    }
  }

  @Test
  void phaseFailureAndNullEnumsRetainOriginalEventText() {
    for (var observation :
        List.of(
            new WebFetchObservation(Phase.BODY, Failure.NONE),
            new WebFetchObservation(Phase.DNS, Failure.DNS),
            new WebFetchObservation(null, null))) {
      var h = new Harness();
      h.service.observe(LEASE, "i", observation);
      boolean failed = observation.failure() != Failure.NONE;
      assertEquals(
          List.of(
              List.of(
                  (failed ? "web-failure:" : "web-phase:") + "i:" + observation.phase(),
                  failed ? "web.fetch_failed" : "web.fetch_phase",
                  observation.phase() + "_" + observation.failure())),
          h.events);
    }
  }

  @Test
  void cancellationLocksThenBindsWithoutFencingAndKeepsFourEventOrder() {
    var h = new Harness();
    h.service.observe(LEASE, "i", CANCEL);
    assertEquals(
        List.of(
            "transaction",
            "lockDiagnosticTask",
            "diagnosticBindings",
            "cancellationRecorded",
            "event",
            "event",
            "event",
            "event",
            "end"),
        h.trace);
    assertEquals(
        List.of(
            List.of("web-cancel:i:3", "web.fetch_cancelled", "BODY_CHECKPOINT_REJECTED"),
            List.of("web-cancel:i:3:elapsed", "web.cancel_elapsed_ms", "123"),
            List.of("web-cancel:i:3:checkpoint", "web.checkpoint_rejected", "FENCE_REJECTED:7"),
            List.of("web-failure:i:BODY", "web.fetch_failed", "BODY_CANCELLED")),
        h.events);
  }

  @Test
  void cancellationRejectsZeroOrMultipleBeforeIdempotenceAndReplayStops() {
    for (int count : List.of(0, 2)) {
      var h = new Harness();
      h.bindings = count;
      h.recorded = true;
      h.reject("WEB_OBSERVATION_BINDING_REJECTED", () -> h.service.observe(LEASE, "i", CANCEL));
      assertEquals(
          List.of("transaction", "lockDiagnosticTask", "diagnosticBindings", "end"), h.trace);
    }
    var h = new Harness();
    h.recorded = true;
    h.service.observe(LEASE, "i", CANCEL);
    assertEquals(
        List.of(
            "transaction",
            "lockDiagnosticTask",
            "diagnosticBindings",
            "cancellationRecorded",
            "end"),
        h.trace);
  }

  @Test
  void preserveChecksStateBeforeStepOrOutput() {
    var h = new Harness();
    h.state = null;
    h.reject("INVOCATION_STATE", h::preserve);
    assertEquals(List.of("transaction", "fenced", "invocation", "state", "end"), h.trace);
  }

  @Test
  void preserveProofPrecedesReceiptThenNullChecksUpdateAndEvent() {
    var h = new Harness();
    h.preserve();
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "invocation",
            "state",
            "stepId",
            "step",
            "proof",
            "receipts",
            "output",
            "outputDigest",
            "hash",
            "preserve",
            "event",
            "end"),
        h.trace);
    assertEquals(List.of(List.of("web-result:i", "web.result_preserved", "proof")), h.events);
  }

  @Test
  void replayComparesThreeFactsLazilyAndUsesOnlyFirstReceipt() {
    var h = new Harness();
    h.replay();
    h.output = "out";
    h.outputDigest = "hash";
    h.receipts =
        List.of(
            h.receipts.getFirst(),
            () -> {
              throw new AssertionError("second receipt");
            });
    h.preserve();
    assertTrue(h.events.isEmpty());
    assertFalse(h.trace.contains("preserve"));
    for (int failure = 0; failure < 3; failure++) {
      h = new Harness();
      h.replay();
      h.output = "out";
      h.outputDigest = "hash";
      if (failure == 0) h.receipt = null;
      if (failure == 1) h.output = new StringBuilder("out");
      if (failure == 2) h.outputDigest = null;
      h.reject("WEB_RESULT_PROOF_INVALID", h::preserve);
      assertEquals(failure > 0, h.trace.contains("output"));
      assertEquals(failure > 1, h.trace.contains("hash"));
    }
  }

  @Test
  void preexistingOutputShortCircuitsDigestAndDoesNotWrite() {
    var h = new Harness();
    h.output = 7;
    h.reject("WEB_RESULT_PROOF_INVALID", h::preserve);
    assertFalse(h.trace.contains("outputDigest"));
    assertFalse(h.trace.contains("hash"));
    h = new Harness();
    h.outputDigest = 7;
    h.reject("WEB_RESULT_PROOF_INVALID", h::preserve);
    assertFalse(h.trace.contains("preserve"));
  }

  @Test
  void portFailuresKeepSameInstanceAndStopBeforeFollowingOperations() {
    for (String point :
        List.of("fenced", "step", "proof", "receipts", "hash", "preserve", "event")) {
      var h = new Harness();
      h.failAt = point;
      h.failure = new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
      assertSame(h.failure, assertThrows(TaskFailure.class, h::preserve));
      assertEquals(point, h.trace.get(h.trace.size() - 2));
    }
  }
}
