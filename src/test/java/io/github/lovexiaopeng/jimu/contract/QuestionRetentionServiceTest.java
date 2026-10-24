package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionRetentions;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QuestionRetentionServiceTest {
  private static final class Fixture implements QuestionRetentions, LedgerUnitOfWork {
    final List<String> calls = new ArrayList<>();
    final List<List<RetainedBody>> metadata = new ArrayList<>();
    List<Candidate> candidates = List.of(new Candidate("tenant", "task"));
    String state = "SUCCEEDED", cleanup = "RETAINED", failure;
    boolean active, quiet = true, unsafe, unknown;
    int released = 1, transactions;

    public List<Candidate> candidates() {
      assertFalse(active);
      return candidates;
    }

    public <T> T required(Supplier<T> work) {
      assertFalse(active);
      active = true;
      transactions++;
      try {
        return work.get();
      } finally {
        active = false;
      }
    }

    public void lockBudget(String tenant) {
      assertTrue(active);
      assertEquals("tenant", tenant);
      calls.add("budget");
    }

    public String metadata(List<RetainedBody> value) {
      metadata.add(List.copyOf(value));
      return "metadata";
    }

    public LockedTask lockTask(Candidate candidate) {
      calls.add("task");
      return (LockedTask)
          Proxy.newProxyInstance(
              LockedTask.class.getClassLoader(),
              new Class<?>[] {LockedTask.class},
              (p, m, args) -> {
                assertTrue(active);
                String name = m.getName();
                calls.add(name);
                if (name.equals(failure)) throw new IllegalStateException("private failure");
                if (name.startsWith("purge")) {
                  String tombstone = (String) args[name.equals("purgeTask") ? 0 : 1];
                  assertEquals("{\"version\":1,\"state\":\"QUESTION_PURGED\"}", tombstone);
                }
                return switch (name) {
                  case "cleanupState" -> cleanup;
                  case "view" ->
                      new Tasks.View(
                          "task",
                          state,
                          1,
                          Instant.EPOCH,
                          Instant.EPOCH,
                          null,
                          null,
                          null,
                          List.of());
                  case "writersQuiescent" -> quiet;
                  case "unsafeReservations" -> unsafe;
                  case "unknownInvocations" -> unknown;
                  case "bodies" ->
                      List.of(
                          new Body("request_snapshot", "request"),
                          new Body("goal", "中文😀"),
                          new Body("summary", null));
                  case "steps" ->
                      List.of(
                          new Content(
                              "step",
                              List.of(
                                  new Body("request_snapshot", "step"),
                                  new Body("response_snapshot", null))));
                  case "invocations" ->
                      List.of(
                          new Content(
                              "invoke",
                              List.of(
                                  new Body("input_snapshot", "input"),
                                  new Body("output_snapshot", "输出"))));
                  case "questions" ->
                      List.of(
                          new Content(
                              "question",
                              List.of(
                                  new Body("request_snapshot", "question"),
                                  new Body("answer_snapshot", "回答"),
                                  new Body("receipt_snapshot", null))));
                  case "releaseHold" -> released;
                  default -> null;
                };
              });
    }

    void run() {
      new QuestionRetentionServiceImpl(this, this, Payloads::hash).cleanup();
    }
  }

  @Test
  void singleTransactionKeepsBodyMetadataSeparateAndUsesUtf8Bytes() {
    var f = new Fixture();
    f.run();
    assertEquals(1, f.transactions);
    assertEquals(
        List.of(
            "budget",
            "task",
            "cleanupState",
            "view",
            "writersQuiescent",
            "unsafeReservations",
            "unknownInvocations",
            "bodies",
            "steps",
            "purgeStep",
            "invocations",
            "purgeInvocation",
            "questions",
            "purgeQuestion",
            "purgeTask",
            "releaseHold",
            "releaseBudget",
            "event"),
        f.calls);
    assertEquals(
        List.of("request_snapshot", "answer_snapshot"),
        f.metadata.getFirst().stream().map(QuestionRetentions.RetainedBody::key).toList());
    var goal =
        f.metadata.getLast().stream()
            .filter(v -> v.key().equals("task:goal"))
            .findFirst()
            .orElseThrow();
    assertEquals(10, goal.bytes());
    assertEquals(Payloads.hash("中文😀"), goal.sha256());
    assertFalse(
        f.metadata.getLast().stream()
            .anyMatch(v -> v.key().contains("summary") || v.key().contains("receipt")));
  }

  @Test
  void cleanupStateAndTerminalViewShortCircuitBeforeWriter() {
    var purged = new Fixture();
    purged.cleanup = "PURGED";
    purged.run();
    assertEquals(List.of("budget", "task", "cleanupState"), purged.calls);
    var running = new Fixture();
    running.state = "RUNNING";
    running.run();
    assertEquals(List.of("budget", "task", "cleanupState", "view"), running.calls);
  }

  @Test
  void originalViewTerminalPredicateIncludesReconciliationIfCandidateChangesAfterScan() {
    for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_RECONCILIATION")) {
      var f = new Fixture();
      f.state = terminal;
      f.run();
      assertTrue(f.calls.contains("event"));
    }
  }

  @Test
  void writerAndUnknownChecksKeepOriginalShortCircuitOrder() {
    var writer = new Fixture();
    writer.quiet = false;
    writer.run();
    assertFalse(writer.calls.contains("unsafeReservations"));
    var hold = new Fixture();
    hold.unsafe = true;
    hold.run();
    assertFalse(hold.calls.contains("unknownInvocations"));
    var invocation = new Fixture();
    invocation.unknown = true;
    invocation.run();
    assertFalse(invocation.calls.contains("bodies"));
  }

  @Test
  void zeroHoldUpdateStillCommitsContentAndEventWithoutBudgetRelease() {
    var f = new Fixture();
    f.released = 0;
    f.run();
    assertTrue(f.calls.contains("purgeTask"));
    assertTrue(f.calls.contains("event"));
    assertFalse(f.calls.contains("releaseBudget"));
  }

  @Test
  void earlyPersistenceFailurePropagatesBeforeHoldAndEvent() {
    var f = new Fixture();
    f.failure = "purgeQuestion";
    assertThrows(IllegalStateException.class, f::run);
    assertFalse(f.calls.contains("releaseHold"));
    assertFalse(f.calls.contains("event"));
    assertFalse(f.active);
  }

  @Test
  void lateFailureAbortsBatchInsteadOfSwallowingOrProcessingAnotherCandidate() {
    var f = new Fixture();
    f.failure = "event";
    f.candidates =
        List.of(
            new QuestionRetentions.Candidate("tenant", "one"),
            new QuestionRetentions.Candidate("tenant", "two"));
    assertThrows(IllegalStateException.class, f::run);
    assertEquals(1, f.transactions);
    assertFalse(f.active);
  }

  @Test
  void emptyCandidateInventoryStartsNoTransaction() {
    var f = new Fixture();
    f.candidates = List.of();
    f.run();
    assertEquals(0, f.transactions);
    assertTrue(f.calls.isEmpty());
  }
}
