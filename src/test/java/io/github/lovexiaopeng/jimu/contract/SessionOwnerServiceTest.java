package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionOwners;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionOwnerServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionOwnerServiceTest {
  private static final String ID = "00000000-0000-0000-0000-000000000001";
  private final SessionOwnerServiceImpl service = new SessionOwnerServiceImpl();
  private final Identity identity = new Identity("tenant", "actor");

  static class Port implements SessionOwners<Object> {
    final List<String> calls = new ArrayList<>();
    final Object task = new Object();
    boolean session;
    boolean empty;
    String fail;
    final RuntimeException error = new IllegalStateException("original");

    void hit(String name) {
      calls.add(name);
      if (name.equals(fail)) throw error;
    }

    public void bucket(String tenant) {
      assertEquals("tenant", tenant);
      hit("bucket");
    }

    public void lockScope(String tenant, String task) {
      assertEquals(ID, task);
      hit("scope");
    }

    public List<Object> find(String tenant, String task, String actor) {
      assertEquals("actor", actor);
      hit("find");
      return empty ? List.of() : List.of(this.task, new Object());
    }

    public boolean sessionTask(Object task) {
      assertSame(this.task, task);
      hit("session");
      return session;
    }

    public void identity(Identity identity) {
      hit("identity");
    }

    public void access(Object task) {
      assertSame(this.task, task);
      hit("access");
    }
  }

  @Test
  void nonSessionRetainsFirstReferenceAndLockOrder() {
    var p = new Port();
    assertSame(p.task, service.owner(identity, ID, p));
    assertEquals(List.of("bucket", "scope", "find", "session", "identity", "access"), p.calls);
  }

  @Test
  void sessionDoesNotReadIdentity() {
    var p = new Port();
    p.session = true;
    assertSame(p.task, service.owner(identity, ID, p));
    assertEquals(List.of("bucket", "scope", "find", "session", "access"), p.calls);
  }

  @Test
  void invalidAndNullIdFailBeforeIdentityOrIo() {
    var p = new Port();
    assertEquals(
        "SESSION_EVENT_NOT_FOUND",
        assertThrows(TaskFailure.class, () -> service.owner(null, "bad", p)).code());
    assertThrows(NullPointerException.class, () -> service.owner(null, null, p));
    assertEquals(List.of(), p.calls);
  }

  @Test
  void absentOwnerStopsBeforeIdentity() {
    var p = new Port();
    p.empty = true;
    assertEquals(
        "SESSION_EVENT_NOT_FOUND",
        assertThrows(TaskFailure.class, () -> service.owner(identity, ID, p)).code());
    assertEquals(List.of("bucket", "scope", "find"), p.calls);
  }

  @Test
  void eachFailureStopsAndKeepsOriginal() {
    for (String stage : List.of("bucket", "scope", "find", "session", "identity", "access")) {
      var p = new Port();
      p.fail = stage;
      assertSame(
          p.error, assertThrows(RuntimeException.class, () -> service.owner(identity, ID, p)));
      assertEquals(stage, p.calls.getLast());
    }
  }
}
