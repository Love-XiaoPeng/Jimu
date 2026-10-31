package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 原两个Slot的地址交接；不把地址当成持锁资格或raw证明。 */
class SessionHistoryLockHandoffTest {
  private static Object field(Object value, String name) {
    return SessionHistoryStatementIdentityTest.field(value, name);
  }

  private static SessionQueryTransactionTest.Fixture fixture() {
    var f = new SessionQueryTransactionTest.Fixture();
    f.context.restrictLineage();
    return f;
  }

  private static Object start(SessionQueryWork w, boolean event) {
    return event
        ? w.beginEventHistory(new String("t"), new String("x"))
        : w.beginHistory(new String("t"), new String("x"));
  }

  private static void verify(Object slot, boolean event, Object args, Connection c) {
    if (!event) {
      SessionHistoryStatementIdentityTest.verify(
          (SessionHistoryRows.Slot) slot, new String(SessionHistoryRows.SIZE), args, c, true);
      return;
    }
    try {
      var method =
          slot.getClass().getDeclaredMethod("verify", String.class, Object.class, Connection.class);
      method.setAccessible(true);
      method.invoke(slot, new String(SessionEventHistoryRows.SIZE), args, c);
    } catch (InvocationTargetException wrapped) {
      if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
      if (wrapped.getCause() instanceof Error failure) throw failure;
      throw new AssertionError(wrapped.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Map<String, Object> args(SessionQueryContext q) {
    return Map.of("query", q, "tenant", new String("t"), "taskId", new String("x"));
  }

  private static SessionScopeRecords.Caller caller(String tenant, String task) {
    return new SessionScopeRecords.Caller(
        tenant, task, "owner", "caps", "tools", 1, "SUCCEEDED", null, "d");
  }

  private static SessionScopeRecords.SourceFact source(String tenant, String task) {
    return new SessionScopeRecords.SourceFact(
        caller(tenant, task),
        new SessionScopeRecords.History(1, "b", null),
        new SessionScopeRecords.Scope(
            1, 1, "owner", "b", "tools", "{}", "d", 2, 0, "ACTIVE", null, null, null, null, null,
            "PRESENT", true),
        new SessionScopeRecords.Authority(tenant, task, "owner", "tools", "b", "", "", List.of()),
        List.of());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> locks(SessionQueryWork w) {
    return (Map<String, ?>) field(w, "locks");
  }

  @Test
  void equalStringsReuseTheOriginalAddressAndClearBeforeOwnerRefund() {
    for (boolean event : List.of(false, true)) {
      var f = fixture();
      Object[] saved = {null};
      f.transaction.required(
          f.context,
          () -> {
            var w = SessionQueryTransaction.current(f.context);
            w.locked("t", "x");
            long live = f.context.scratchLive();
            var slot = start(w, event);
            saved[0] = slot;
            assertSame(w.projectionLock(f.context, "t", "x"), field(slot, "lock"));
            assertEquals(event ? 4112 : 4128, f.context.scratchLive() - live);
            for (int i = 0; i < 4; i++)
              verify(slot, event, args(f.context), SessionQueryTransaction.connection(f.context));
            return null;
          });
      assertNull(field(saved[0], "lock"));
      assertEquals(65536, f.context.scratchLive());
    }
  }

  @Test
  void everySlotCheckRechecksCallerAndAllNewOrReplacedSourceLocks() {
    for (boolean event : List.of(false, true))
      for (String missing : List.of("x", "s1", "s2", "replacement:s1")) {
        var f = fixture();
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    f.context,
                    () -> {
                      var w = SessionQueryTransaction.current(f.context);
                      w.admission();
                      for (String id : List.of("x", "s1", "s2")) w.locked("t", id);
                      var slot = start(w, event);
                      w.stageSource(caller("t", "x"), source("t", "s1"));
                      verify(
                          slot,
                          event,
                          args(f.context),
                          SessionQueryTransaction.connection(f.context));
                      w.stageSource(caller("t", "x"), source("t", "s2"));
                      if (missing.startsWith("replacement")) {
                        w.locked("other", "s1");
                        w.stageSource(caller("t", "x"), source("other", "s1"));
                        locks(w).remove("t:s1");
                        verify(
                            slot,
                            event,
                            args(f.context),
                            SessionQueryTransaction.connection(f.context));
                        locks(w).remove("other:s1");
                      } else locks(w).remove("t:" + missing);
                      verify(
                          slot,
                          event,
                          args(f.context),
                          SessionQueryTransaction.connection(f.context));
                      return null;
                    }));
        assertEquals(1, f.rollbacks);
        assertEquals(65536, f.context.scratchLive());
      }
  }

  @Test
  void addressCannotBypassCurrentTenantTaskQueryConnectionOrWork() {
    for (boolean event : List.of(false, true))
      for (String mode :
          List.of("tenant", "taskId", "query", "connection", "work", "foreign-lock")) {
        var f = fixture();
        var other = fixture();
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    f.context,
                    () -> {
                      var w = SessionQueryTransaction.current(f.context);
                      w.locked("t", "x");
                      var slot = start(w, event);
                      if (mode.equals("foreign-lock")) {
                        other.transaction.requiresNew(
                            other.context,
                            () -> {
                              var foreign = SessionQueryTransaction.current(other.context);
                              foreign.locked("t", "x");
                              SessionHistoryStatementIdentityTest.put(
                                  slot, "lock", foreign.projectionLock(other.context, "t", "x"));
                              return null;
                            });
                      }
                      if (mode.equals("work")) {
                        other.transaction.requiresNew(
                            other.context,
                            () -> {
                              verify(
                                  slot,
                                  event,
                                  args(f.context),
                                  SessionQueryTransaction.connection(f.context));
                              return null;
                            });
                      } else {
                        var a = new java.util.HashMap<>(args(f.context));
                        if (mode.equals("tenant") || mode.equals("taskId")) a.put(mode, "foreign");
                        if (mode.equals("query")) a.put("query", other.context);
                        verify(
                            slot,
                            event,
                            a,
                            mode.equals("connection")
                                ? other.connection
                                : SessionQueryTransaction.connection(f.context));
                      }
                      return null;
                    }));
        assertEquals(65536, f.context.scratchLive());
      }
  }

  @Test
  void emptyFallbackAndWriteInvalidationNeverReuseAnAddressAsProof() {
    for (boolean event : List.of(false, true))
      for (boolean empty : List.of(false, true)) {
        var f = fixture();
        Object[] saved = {null};
        f.transaction.required(
            f.context,
            () -> {
              var w = SessionQueryTransaction.current(f.context);
              w.locked("t", "x");
              var slot = start(w, event);
              saved[0] = slot;
              if (empty) {
                SessionHistoryStatementIdentityTest.put(slot, "sizeEnded", true);
                if (event) ((SessionEventHistoryRows.Slot) slot).emptySize();
                else ((SessionHistoryRows.Slot) slot).emptySize();
                assertNull(field(slot, "lock"));
              } else {
                w.dependencyMutation(Set.of(new SessionQueryWork.Dependency("t", "x")));
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        verify(
                            slot,
                            event,
                            args(f.context),
                            SessionQueryTransaction.connection(f.context)));
              }
              return null;
            });
        assertNull(field(saved[0], "lock"));
        assertEquals(65536, f.context.scratchLive());
      }
  }

  @Test
  void errorsAndAcquisitionFailurePreserveFirstFailureAndReleaseUnpublishedOwners() {
    for (boolean event : List.of(false, true))
      for (boolean error : List.of(false, true)) {
        var f = fixture();
        var primary = new AssertionError("address lookup");
        Object[] saved = {null};
        if (error) {
          assertSame(
              primary,
              assertThrows(
                  AssertionError.class,
                  () ->
                      f.transaction.required(
                          f.context,
                          () -> {
                            var w = SessionQueryTransaction.current(f.context);
                            w.locked("t", "x");
                            var slot = start(w, event);
                            saved[0] = slot;
                            SessionHistoryStatementIdentityTest.put(
                                w,
                                "locks",
                                new java.util.HashMap<String, Object>() {
                                  @Override
                                  public Object get(Object key) {
                                    throw primary;
                                  }
                                });
                            verify(
                                slot,
                                event,
                                args(f.context),
                                SessionQueryTransaction.connection(f.context));
                            return null;
                          })));
          assertNotNull(f.context.recordedFailure());
          assertSame(primary, f.context.recordedFailure().getCause());
          assertNull(field(saved[0], "lock"));
        } else {
          assertThrows(
              RuntimeException.class,
              () ->
                  f.transaction.required(
                      f.context,
                      () -> {
                        var w = SessionQueryTransaction.current(f.context);
                        start(w, event);
                        return null;
                      }));
        }
        assertEquals(1, f.rollbacks);
        assertEquals(65536, f.context.scratchLive());
      }
  }

  @Test
  void slotAcquisitionRefusalNeverPublishesOrLeaksTheNewAlias() {
    for (boolean event : List.of(false, true))
      for (String bound : List.of("scratch", "operations")) {
        var f = fixture();
        var q = SessionEventHistoryLengthTest.query();
        var held = new Object();
        RuntimeException[] first = {null};
        assertSame(firstFailure(f, q, event, bound, held, first), first[0]);
        assertEquals(65536, q.scratchLive());
        assertEquals(1, f.rollbacks);
      }
  }

  private static RuntimeException firstFailure(
      SessionQueryTransactionTest.Fixture f,
      SessionQueryContext q,
      boolean event,
      String bound,
      Object held,
      RuntimeException[] first) {
    return assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  var w = SessionQueryTransaction.current(q);
                  w.locked("t", "x");
                  if (bound.equals("scratch"))
                    q.retainScratch(held, 1048576 - q.scratchLive() - (event ? 4112 : 4192) + 1);
                  else q.operation(65536 - q.resources().operations() - (event ? 15 : 100));
                  try {
                    first[0] = assertThrows(RuntimeException.class, () -> start(w, event));
                    assertNull(field(w, event ? "eventHistoryLength" : "historyLength"));
                    assertSame(first[0], q.recordedFailure());
                  } finally {
                    q.releaseScratch(held);
                  }
                  return null;
                }));
  }
}
