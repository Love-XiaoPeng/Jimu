package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryStorageLimit;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.directory.service.impl.DirectoryRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DirectoryRetentionServiceTest {
  private static final class Fixture
      implements DirectoryRetentions, DirectoryRetentions.Workspace, LedgerUnitOfWork {
    final List<String> calls = new ArrayList<>();
    final Instant past = Instant.parse("2020-01-01T00:00:00Z");
    Projection projection = new Projection("PRESENT", past, null, null, "/");
    String state = "SUCCEEDED", fail;
    boolean active, available = true, writer = true, unsafe, running, dispatched, changeGeneration;
    long bytes;
    int released = 1, transactions;
    List<RetainedBody> metadata;
    final Candidate candidate = new Candidate("tenant", "task");

    public List<Candidate> candidates() {
      calls.add("candidates");
      return List.of(candidate);
    }

    public void lockBudget(String tenant) {
      assertTrue(active);
      calls.add("budget");
    }

    public Instant now() {
      return Instant.parse("2026-01-01T00:00:00Z");
    }

    public String metadata(List<RetainedBody> value) {
      metadata = List.copyOf(value);
      return "metadata";
    }

    public <T> T required(Supplier<T> work) {
      assertFalse(active);
      transactions++;
      active = true;
      try {
        return work.get();
      } finally {
        active = false;
      }
    }

    public boolean available() {
      return available;
    }

    public String plan(Candidate c) {
      assertFalse(active);
      calls.add("plan");
      if (changeGeneration) projection = new Projection("PURGING", past, "other", null, null);
      return "receipt";
    }

    public void purge(Candidate c, String receipt) {
      assertFalse(active);
      assertEquals("receipt", receipt);
      calls.add("purge");
      if ("purge".equals(fail)) throw new IllegalStateException("private storage failure");
    }

    public LockedTask lockTask(Candidate c) {
      assertTrue(active);
      calls.add("lock");
      return (LockedTask)
          Proxy.newProxyInstance(
              LockedTask.class.getClassLoader(),
              new Class<?>[] {LockedTask.class},
              (proxy, method, args) -> {
                String name = method.getName();
                calls.add(name);
                if (name.equals(fail)) throw new IllegalStateException("private failure");
                return switch (name) {
                  case "projection" -> projection;
                  case "state" -> state;
                  case "writersQuiescent" -> writer;
                  case "unsafeReservations" -> unsafe;
                  case "activeInvocations" -> running;
                  case "dispatchedModels" -> dispatched;
                  case "directoryCapability" -> true;
                  case "storageBytes" -> bytes;
                  case "bodies" ->
                      List.of(new Body("request_snapshot", "汉字"), new Body("goal", null));
                  case "steps" ->
                      List.of(new Content("s", List.of(new Body("response_snapshot", ""))));
                  case "invocations", "paths", "dispatches" -> List.of();
                  case "markPurging" -> {
                    projection = new Projection("PURGING", past, "g", null, null);
                    yield null;
                  }
                  case "storeReceipt" -> {
                    projection = new Projection("PURGING", past, "g", (String) args[0], null);
                    yield null;
                  }
                  case "markPurged" -> {
                    projection = new Projection("PURGED", past, "g", "receipt", null);
                    yield null;
                  }
                  case "releaseHold" -> released;
                  default -> null;
                };
              });
    }

    void run() {
      new DirectoryRetentionServiceImpl(this, this, this, Payloads::hash).cleanup();
    }
  }

  @Test
  void absentWorkspaceHasNoDatabaseDependency() {
    var f = new Fixture();
    f.available = false;
    f.run();
    assertTrue(f.calls.isEmpty());
  }

  @Test
  void threeTerminalStatesPreservePhasesMetadataAndOneRelease() {
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      var f = new Fixture();
      f.state = state;
      f.run();
      assertEquals(3, f.transactions);
      assertEquals("PURGED", f.projection.state());
      assertEquals(
          List.of("task:request_snapshot", "step:s:response_snapshot", "directory:cwd"),
          f.metadata.stream().map(DirectoryRetentions.RetainedBody::key).toList());
      assertEquals(6, f.metadata.getFirst().bytes());
      assertEquals(Payloads.hash("汉字"), f.metadata.getFirst().sha256());
      assertEquals(1, f.calls.stream().filter("releaseBudget"::equals).count());
      assertTrue(f.calls.indexOf("plan") > f.calls.indexOf("markPurging"));
      assertTrue(f.calls.indexOf("purge") > f.calls.indexOf("storeReceipt"));
      assertTrue(f.calls.indexOf("event") < f.calls.indexOf("releaseHold"));
    }
  }

  @Test
  void terminalWriterAndUnknownChecksShortCircuitInOriginalOrder() {
    var f = new Fixture();
    f.state = "NEEDS_RECONCILIATION";
    f.run();
    assertFalse(f.calls.contains("writersQuiescent"));
    f = new Fixture();
    f.writer = false;
    f.run();
    assertFalse(f.calls.contains("unsafeReservations"));
    f = new Fixture();
    f.unsafe = true;
    f.run();
    assertFalse(f.calls.contains("activeInvocations"));
    f = new Fixture();
    f.running = true;
    f.run();
    assertFalse(f.calls.contains("dispatchedModels"));
    f = new Fixture();
    f.dispatched = true;
    f.run();
    assertFalse(f.calls.contains("purgeTask"));
  }

  @Test
  void unexpiredAndPurgedSkipSafetyAndWrites() {
    for (String state : List.of("PRESENT", "PURGED")) {
      var f = new Fixture();
      f.projection =
          new DirectoryRetentions.Projection(
              state, Instant.parse("2099-01-01T00:00:00Z"), "g", "receipt", null);
      f.run();
      assertFalse(f.calls.contains("state"));
      assertFalse(f.calls.contains("plan"));
    }
  }

  @Test
  void physicalFailureKeepsReceiptAndRetriesWithoutNewTombstonesOrPlan() {
    var f = new Fixture();
    f.fail = "purge";
    assertThrows(IllegalStateException.class, f::run);
    assertEquals("PURGING", f.projection.state());
    assertEquals("receipt", f.projection.receipt());
    assertFalse(f.calls.contains("releaseHold"));
    f.calls.clear();
    f.fail = null;
    f.run();
    assertEquals("PURGED", f.projection.state());
    assertFalse(f.calls.contains("plan"));
    assertFalse(f.calls.contains("markPurging"));
  }

  @Test
  void generationChangeAfterPlanRejectsBeforePhysicalDeletion() {
    var f = new Fixture();
    f.changeGeneration = true;
    var failure = assertThrows(TaskFailure.class, f::run);
    assertEquals("SNAPSHOT_CORRUPT", failure.code());
    assertFalse(f.calls.contains("purge"));
  }

  @Test
  void transactionAndReservationFailuresPropagateBeforeBudgetRelease() {
    var f = new Fixture();
    f.fail = "purgeTask";
    assertThrows(IllegalStateException.class, f::run);
    assertFalse(f.calls.contains("plan"));
    f = new Fixture();
    f.released = 0;
    var failure = assertThrows(TaskFailure.class, f::run);
    assertEquals("RESERVATION_STATE", failure.code());
    assertFalse(f.calls.contains("releaseBudget"));
  }

  @Test
  void originalStorageBoundaryAndHeadroomStayFrozen() {
    DirectoryStorageLimit.check(2097152 - 65536, 0);
    var failure =
        assertThrows(TaskFailure.class, () -> DirectoryStorageLimit.check(2097152 - 65536, 1));
    assertEquals("DIRECTORY_STORAGE_LIMIT", failure.code());
  }
}
