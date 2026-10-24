package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRetentions;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TodoRetentionServiceTest {
  private static final class Fixture implements TodoRetentions, LedgerUnitOfWork {
    final List<String> calls = new ArrayList<>();
    List<Candidate> candidates = List.of(new Candidate("tenant", "task"));
    final List<RetainedBody> retained = new ArrayList<>();
    String cleanup = "PRESENT",
        state = "SUCCEEDED",
        failure,
        encoded = "metadata",
        binding = "binding",
        expected = "binding";
    String bindingHash = Payloads.hash("binding");
    long recordVersion = 1, todoVersion = 1;
    BindingVersions versions = new BindingVersions(1, 1);
    Instant until = Instant.EPOCH;
    boolean active, quiet = true, unsafe, invocations, models;
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

    public Instant now() {
      calls.add("now");
      return Instant.ofEpochSecond(1);
    }

    public String expectedBinding() {
      calls.add("expectedBinding");
      return expected;
    }

    public BindingVersions bindingVersions(String value) {
      calls.add("bindingVersions");
      assertEquals(binding, value);
      return versions;
    }

    public String metadata(List<RetainedBody> value) {
      retained.addAll(value);
      calls.add("metadata");
      return encoded;
    }

    public LockedTask lockTask(Candidate candidate) {
      calls.add("task");
      assertEquals(new Candidate("tenant", "task"), candidate);
      return (LockedTask)
          Proxy.newProxyInstance(
              LockedTask.class.getClassLoader(),
              new Class<?>[] {LockedTask.class},
              (p, m, a) -> {
                assertTrue(active);
                String name = m.getName();
                calls.add(name);
                if (name.equals(failure)) throw new IllegalStateException("private failure");
                if (name.startsWith("purge"))
                  assertEquals(
                      "{\"version\":1,\"state\":\"TODO_PURGED\"}",
                      a[name.equals("purgeTask") ? 0 : 1]);
                return switch (name) {
                  case "projection" ->
                      new Projection(
                          recordVersion, todoVersion, binding, bindingHash, cleanup, until);
                  case "state" -> state;
                  case "writersQuiescent" -> quiet;
                  case "unsafeReservations" -> unsafe;
                  case "activeInvocations" -> invocations;
                  case "dispatchedModels" -> models;
                  case "bodies" -> List.of(new Body("goal", "中文😀"), new Body("summary", null));
                  case "steps" ->
                      List.of(
                          new Content(
                              "s",
                              List.of(
                                  new Body("request_snapshot", "step"),
                                  new Body("response_snapshot", null))));
                  case "invocations" ->
                      List.of(
                          new Content(
                              "i",
                              List.of(
                                  new Body("input_snapshot", "invoke"),
                                  new Body("output_snapshot", null))));
                  case "dispatches" ->
                      List.of(new Content("d", List.of(new Body("usage_snapshot", "usage"))));
                  case "releaseHold" -> released;
                  default -> null;
                };
              });
    }

    void run() {
      new TodoRetentionServiceImpl(this, this, Payloads::hash).cleanup();
    }
  }

  private static void code(String wanted, Fixture f) {
    assertEquals(wanted, assertThrows(TaskFailure.class, f::run).code());
  }

  @Test
  void orderAndUtf8MetadataKeepEventBeforeStrictReservationRelease() {
    var f = new Fixture();
    f.run();
    assertEquals(1, f.transactions);
    assertEquals(
        List.of(
            "budget",
            "task",
            "projection",
            "bindingVersions",
            "expectedBinding",
            "now",
            "state",
            "writersQuiescent",
            "unsafeReservations",
            "activeInvocations",
            "dispatchedModels",
            "bodies",
            "steps",
            "purgeStep",
            "invocations",
            "purgeInvocation",
            "dispatches",
            "purgeDispatch",
            "metadata",
            "purgeTask",
            "markPurged",
            "event",
            "releaseHold",
            "releaseBudget"),
        f.calls);
    assertEquals(
        List.of(
            "task:goal",
            "step:s:request_snapshot",
            "invocation:i:input_snapshot",
            "dispatch:d:usage_snapshot"),
        f.retained.stream().map(TodoRetentions.RetainedBody::key).toList());
    assertEquals(10, f.retained.getFirst().bytes());
    assertEquals(Payloads.hash("中文😀"), f.retained.getFirst().sha256());
  }

  @Test
  void bindingValidationKeepsOriginalRefusalOrder() {
    var f = new Fixture();
    f.recordVersion = 2;
    f.bindingHash = "bad";
    code("TODO_BINDING_UNSUPPORTED", f);
    assertFalse(f.calls.contains("bindingVersions"));
    f = new Fixture();
    f.bindingHash = "bad";
    code("SNAPSHOT_CORRUPT", f);
    assertFalse(f.calls.contains("bindingVersions"));
    f = new Fixture();
    f.versions = new TodoRetentions.BindingVersions(1, 2);
    f.expected = "other";
    code("TODO_BINDING_UNSUPPORTED", f);
    assertFalse(f.calls.contains("expectedBinding"));
    f = new Fixture();
    f.expected = "other";
    code("SNAPSHOT_CORRUPT", f);
    assertFalse(f.calls.contains("now"));
  }

  @Test
  void purgedFutureNonterminalAndLiveWritersShortCircuitInOrder() {
    var f = new Fixture();
    f.cleanup = "PURGED";
    f.run();
    assertFalse(f.calls.contains("now"));
    f = new Fixture();
    f.until = Instant.ofEpochSecond(2);
    f.run();
    assertFalse(f.calls.contains("state"));
    for (String state : List.of("RUNNING", "NEEDS_RECONCILIATION")) {
      f = new Fixture();
      f.state = state;
      f.run();
      assertFalse(f.calls.contains("writersQuiescent"));
    }
    f = new Fixture();
    f.quiet = false;
    f.run();
    assertFalse(f.calls.contains("unsafeReservations"));
  }

  @Test
  void uncertaintyBranchesRetainAndShortCircuit() {
    var f = new Fixture();
    f.unsafe = true;
    f.run();
    assertFalse(f.calls.contains("activeInvocations"));
    f = new Fixture();
    f.invocations = true;
    f.run();
    assertFalse(f.calls.contains("dispatchedModels"));
    f = new Fixture();
    f.models = true;
    f.run();
    assertFalse(f.calls.contains("bodies"));
  }

  @Test
  void metadataLimitUsesUtf8AfterChildUpdatesAndBeforeTaskWrite() {
    var f = new Fixture();
    f.encoded = "中".repeat(21846);
    code("TODO_STORAGE_LIMIT", f);
    assertTrue(f.calls.contains("purgeDispatch"));
    assertFalse(f.calls.contains("purgeTask"));
    f = new Fixture();
    f.encoded = "x".repeat(65536);
    f.run();
    assertTrue(f.calls.contains("releaseBudget"));
  }

  @Test
  void zeroHoldUpdateIsRefusalAfterEventAndDoesNotRefund() {
    var f = new Fixture();
    f.released = 0;
    code("RESERVATION_STATE", f);
    assertTrue(f.calls.contains("event"));
    assertFalse(f.calls.contains("releaseBudget"));
  }

  @Test
  void earlyAndLateFailuresPropagateWithoutSecondTransaction() {
    for (String stage : List.of("projection", "purgeStep", "event", "releaseBudget")) {
      var f = new Fixture();
      f.failure = stage;
      assertThrows(IllegalStateException.class, f::run);
      assertEquals(1, f.transactions);
    }
  }

  @Test
  void emptyCandidatesDoNotOpenTransactions() {
    var f = new Fixture();
    f.candidates = List.of();
    f.run();
    assertEquals(0, f.transactions);
    assertTrue(f.calls.isEmpty());
  }
}
