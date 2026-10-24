package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRevocations;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeRevocationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 拒绝优先级、UNKNOWN／终态短路及取消／计账失败必须退出原事务。 */
class SessionScopeRevocationServiceTest {
  private static final Identity OWNER = new Identity("tenant", "actor");
  private final Records records = new Records();
  private final SessionScopeRevocationServiceImpl service =
      new SessionScopeRevocationServiceImpl(records);

  private static final class Records
      implements SessionScopeRevocations, SessionScopeRevocations.Owner {
    final List<String> log = new ArrayList<>();
    boolean present = true, scope = true;
    String state = "RUNNING", failure;
    long deadline;

    public <T> T required(long end, Supplier<T> work) {
      deadline = end;
      log.add("begin");
      try {
        T result = work.get();
        log.add("commit");
        return result;
      } catch (RuntimeException f) {
        log.add("rollback");
        throw f;
      }
    }

    public void lockBudget(String tenant) {
      log.add("budget:" + tenant);
    }

    public void lockScope(String tenant, String caller) {
      log.add("scope:" + tenant + ":" + caller);
    }

    public List<Owner> owners(Identity who, String caller) {
      log.add("owner:" + who.actor());
      return present ? List.of(this) : List.of();
    }

    public void requireIdentity(Identity who) {
      log.add("identity");
      fail("identity");
    }

    public boolean scopeTask() {
      return scope;
    }

    public String state() {
      return state;
    }

    public void invalidateAfterTransaction() {
      log.add("post");
    }

    public void revoke() {
      log.add("revoke");
      fail("revoke");
    }

    public void cancel(Identity who) {
      log.add("cancel:" + who.actor());
      fail("cancel");
    }

    public void account() {
      log.add("account");
      fail("account");
    }

    private void fail(String at) {
      if (at.equals(failure)) throw new TaskFailure(403, "ACCESS_REVOKED");
    }
  }

  private void rejected(String code, boolean valid) {
    assertEquals(
        code,
        assertThrows(TaskFailure.class, () -> service.revoke(OWNER, "caller", valid, 12345))
            .code());
    assertEquals("rollback", records.log.getLast());
  }

  @Test
  void ownerPrecedesMalformedInput() {
    records.present = false;
    rejected("SESSION_SCOPE_UNAVAILABLE", false);
    assertEquals(
        List.of("begin", "budget:tenant", "scope:tenant:caller", "owner:actor", "rollback"),
        records.log);
  }

  @Test
  void nonScopePrecedesCurrentIdentity() {
    records.scope = false;
    records.failure = "identity";
    rejected("SESSION_SCOPE_UNAVAILABLE", false);
    assertTrue(!records.log.contains("identity") && !records.log.contains("post"));
  }

  @Test
  void identityLossPostsInvalidationBeforeInputRefusal() {
    records.failure = "identity";
    rejected("SESSION_SCOPE_UNAVAILABLE", false);
    assertEquals(
        List.of(
            "begin",
            "budget:tenant",
            "scope:tenant:caller",
            "owner:actor",
            "identity",
            "post",
            "rollback"),
        records.log);
  }

  @Test
  void malformedInputDoesNotRevokeOrPost() {
    rejected("TOOL_INPUT_INVALID", false);
    assertTrue(!records.log.contains("revoke") && !records.log.contains("post"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"QUEUED", "RUNNING", "WAITING_USER", "CANCEL_REQUESTED"})
  void activeStatesCancelThenAccount(String state) {
    records.state = state;
    service.revoke(OWNER, "caller", true, 12345);
    assertEquals(12345, records.deadline);
    assertEquals(
        List.of(
            "begin",
            "budget:tenant",
            "scope:tenant:caller",
            "owner:actor",
            "identity",
            "revoke",
            "cancel:actor",
            "account",
            "commit"),
        records.log);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_RECONCILIATION"})
  void terminalAndUnknownRevokeWithoutCancel(String state) {
    records.state = state;
    service.revoke(OWNER, "caller", true, 12345);
    assertTrue(!records.log.contains("cancel:actor"));
    assertEquals(List.of("revoke", "account", "commit"), records.log.subList(5, 8));
  }

  @ParameterizedTest
  @ValueSource(strings = {"revoke", "cancel", "account"})
  void writeFailureEscapesForAtomicRollback(String at) {
    records.failure = at;
    rejected("ACCESS_REVOKED", true);
    assertTrue(!records.log.contains("post"));
  }
}
