package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeMaintenanceRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeMaintenanceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 调度预提交和失效清理分两次短事务，拒绝和恢复不重放外部效果。 */
class SessionScopeMaintenanceServiceTest {
  private static final class Records
      implements SessionScopeMaintenanceRecords, SessionScopeMaintenanceRecords.Locked {
    final List<String> log = new ArrayList<>();
    final List<Long> deadlines = new ArrayList<>();
    String rejection;
    boolean invalidated, safe = true;
    RuntimeException scan;

    public <T> T required(long end, Supplier<T> work) {
      deadlines.add(end);
      log.add("begin");
      try {
        T result = work.get();
        log.add("commit");
        return result;
      } catch (RuntimeException failure) {
        log.add("rollback");
        throw failure;
      }
    }

    public <T> T requiresNew(Supplier<T> work) {
      log.add("requiresNew");
      return work.get();
    }

    public List<Candidate> candidates() {
      log.add("scan");
      if (scan != null) throw scan;
      return List.of(new Candidate("tenant", "caller"));
    }

    public Locked lock(Candidate candidate) {
      log.add("budget/ordered-task:" + candidate.tenant() + ":" + candidate.caller());
      return this;
    }

    public void remaining() {
      log.add("deadline");
    }

    public void deferred(boolean scan, String reason) {
      log.add("deferred:" + scan + ":" + reason);
    }

    public void defer() {
      log.add("schedule");
    }

    public void account() {
      log.add("account");
    }

    public void requireCurrent() {
      log.add("current");
      if (rejection != null) throw new TaskFailure(409, rejection);
    }

    public void invalidate() {
      log.add("invalidate");
      invalidated = true;
    }

    public boolean invalidated() {
      return invalidated;
    }

    public boolean historyPresent() {
      return true;
    }

    public boolean cleanupSafe() {
      return safe;
    }

    public void purge() {
      log.add("purge");
    }

    public void invalidateAndSchedule() {
      log.add("post-invalidate/schedule");
    }
  }

  @Test
  void scheduleIsCommittedBeforeValidation() {
    var r = new Records();
    new SessionScopeMaintenanceServiceImpl(r).validate();
    assertTrue(r.log.indexOf("schedule") < r.log.indexOf("current"));
    assertEquals("commit", r.log.get(r.log.indexOf("current") - 3));
    assertEquals(3, r.deadlines.size());
    assertEquals(1, r.deadlines.stream().distinct().count());
  }

  @Test
  void scopeUnavailablePersistsInvalidationAndPurges() {
    var r = new Records();
    r.rejection = "SESSION_SCOPE_UNAVAILABLE";
    new SessionScopeMaintenanceServiceImpl(r).validate();
    assertTrue(r.log.contains("invalidate"));
    assertTrue(r.log.contains("purge"));
    assertEquals(3, r.log.stream().filter("commit"::equals).count());
  }

  @Test
  void corruptScopeIsDeferredWithoutPurge() {
    var r = new Records();
    r.rejection = "SESSION_HISTORY_CORRUPT";
    new SessionScopeMaintenanceServiceImpl(r).validate();
    assertTrue(r.log.contains("rollback"));
    assertTrue(r.log.contains("deferred:false:SESSION_HISTORY_CORRUPT"));
    assertFalse(r.log.contains("purge"));
    assertFalse(r.log.contains("invalidate"));
  }

  @Test
  void unsafeWriterRetainsBodyAndAccounts() {
    var r = new Records();
    r.invalidated = true;
    r.safe = false;
    new SessionScopeMaintenanceServiceImpl(r).validate();
    assertFalse(r.log.contains("purge"));
    assertEquals(2, r.log.stream().filter("account"::equals).count());
  }

  @Test
  void scanTimeoutRemainsBoundedDeferral() {
    var r = new Records();
    r.scan = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    assertDoesNotThrow(() -> new SessionScopeMaintenanceServiceImpl(r).validate());
    assertFalse(r.log.contains("schedule"));
  }

  @Test
  void unexpectedScanFailurePropagates() {
    var r = new Records();
    r.scan = new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> new SessionScopeMaintenanceServiceImpl(r).validate())
            .code());
  }

  @Test
  void storageScanErrorLogsOnlyExceptionClass() {
    var r = new Records();
    r.scan =
        new SessionScopeMaintenanceRecords.StorageFailure(
            new IllegalStateException("private SQL and body"));
    new SessionScopeMaintenanceServiceImpl(r).validate();
    assertTrue(r.log.contains("deferred:true:IllegalStateException"));
  }

  @Test
  void afterCompletionStartsDistinctTransactionAndChecksDeadline() {
    var r = new Records();
    new SessionScopeMaintenanceServiceImpl(r).persistInvalidation("tenant", "caller");
    assertEquals(
        List.of(
            "requiresNew",
            "budget/ordered-task:tenant:caller",
            "post-invalidate/schedule",
            "account",
            "deadline"),
        r.log);
  }
}
