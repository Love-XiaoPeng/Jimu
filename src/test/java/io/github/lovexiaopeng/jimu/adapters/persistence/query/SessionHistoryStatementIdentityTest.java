package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;

/** 只验证身份候选新增边界；真实MyBatis配脚本JDBC，不执行旧测试矩阵。 */
class SessionHistoryStatementIdentityTest {
  static Object field(Object object, String name) {
    try {
      var field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(object);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  static void put(Object object, String name, Object value) {
    try {
      var field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(object, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  static void verify(
      SessionHistoryRows.Slot slot, String id, Object args, Connection connection, boolean size) {
    try {
      var method =
          slot.getClass()
              .getDeclaredMethod(
                  "verify", String.class, Object.class, Connection.class, boolean.class);
      method.setAccessible(true);
      method.invoke(slot, id, args, connection, size);
    } catch (InvocationTargetException wrapped) {
      if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
      if (wrapped.getCause() instanceof Error failure) throw failure;
      throw new AssertionError(wrapped.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  static Map<String, Object> args(SessionQueryContext q) {
    return Map.of("query", q, "tenant", "t", "taskId", "x");
  }

  @Test
  void realMappedStatementIdsAreRetainedUntilClearAndRawOwnerNeverShrinksEarly() throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    SessionHistoryRows.Slot[] saved = {null};
    f.work(
        q,
        () -> {
          long live = q.scratchLive(), operations = q.resources().operations();
          var rows = f.load(q);
          var slot =
              (SessionHistoryRows.Slot) field(SessionQueryTransaction.current(q), "historyLength");
          saved[0] = slot;
          var config = f.sql.getSqlSessionFactory().getConfiguration();
          assertSame(
              config.getMappedStatement(SessionHistoryRows.SIZE).getId(),
              field(slot, "confirmedSizeId"));
          assertSame(
              config.getMappedStatement(SessionHistoryRows.RAW).getId(),
              field(slot, "confirmedRawId"));
          assertNotSame(SessionHistoryRows.SIZE, field(slot, "confirmedSizeId"));
          assertNotSame(SessionHistoryRows.RAW, field(slot, "confirmedRawId"));
          assertEquals(true, field(slot, "invalid"));
          assertSame(rows, field(slot, "rows"));
          assertEquals(16400 + 8L * ((String) f.row[4]).length(), q.scratchLive() - live);
          assertEquals(1844, q.resources().operations() - operations);
          assertEquals(2, q.resources().reads());
          assertEquals(2633, q.resources().bytes());
          System.out.println(
              "MYBATIS_IDENTITY load=1844 rawOwner="
                  + (q.scratchLive() - live)
                  + " sizeIdIsLiteral=false rawIdIsLiteral=false reads=2 bytes=2633");
          return null;
        });
    assertNull(field(saved[0], "confirmedSizeId"));
    assertNull(field(saved[0], "confirmedRawId"));
    assertNull(field(saved[0], "rows"));
    assertNull(field(saved[0], "query"));
    assertEquals(65536, q.scratchLive());
    assertEquals(1, f.database.commits);
  }

  @Test
  void everySuccessfulIdentityHitStillReadsAllThreeDynamicValues() throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    var calls = new ArrayList<String>();
    f.work(
        q,
        () -> {
          var owner = SessionQueryTransaction.current(q);
          long live = q.scratchLive(), before = q.resources().operations();
          var slot = owner.beginHistory("t", "x");
          assertEquals(4112, q.scratchLive() - live);
          assertEquals(93, q.resources().operations() - before);
          var dynamic =
              new HashMap<String, Object>(args(q)) {
                @Override
                public Object get(Object key) {
                  calls.add((String) key);
                  return super.get(key);
                }
              };
          for (boolean size : new boolean[] {true, false}) {
            var id = new String(size ? SessionHistoryRows.SIZE : SessionHistoryRows.RAW);
            var fresh = new String(id);
            var connection = SessionQueryTransaction.connection(q);
            int index = 0;
            for (String value : new String[] {id, id, fresh}) {
              before = q.resources().operations();
              verify(slot, value, dynamic, connection, size);
              assertEquals(index == 1 ? 29 : size ? 146 : 135, q.resources().operations() - before);
              index++;
            }
            assertSame(fresh, field(slot, size ? "confirmedSizeId" : "confirmedRawId"));
          }
          assertEquals(18, calls.size());
          for (int i = 0; i < calls.size(); i += 3)
            assertEquals(List.of("query", "tenant", "taskId"), calls.subList(i, i + 3));
          return null;
        });
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void sameIdentityCannotBypassCurrentParametersConnectionTokenLocksOrInvalidation()
      throws Exception {
    for (boolean size : new boolean[] {true, false}) {
      for (String mode :
          List.of("query", "tenant", "taskId", "connection", "token", "locks", "invalid")) {
        var f = new SessionHistoryLengthTest.Fixture();
        var q = SessionEventHistoryLengthTest.query();
        RuntimeException[] first = {null};
        var caught =
            assertThrows(
                RuntimeException.class,
                () ->
                    f.work(
                        q,
                        () -> {
                          var owner = SessionQueryTransaction.current(q);
                          var slot = owner.beginHistory("t", "x");
                          var params = new HashMap<>(args(q));
                          var id =
                              new String(size ? SessionHistoryRows.SIZE : SessionHistoryRows.RAW);
                          var connection = SessionQueryTransaction.connection(q);
                          verify(slot, id, params, connection, size);
                          switch (mode) {
                            case "query", "tenant", "taskId" -> params.put(mode, new Object());
                            case "token" -> put(slot, "token", new Object());
                            case "locks" -> ((Set<?>) field(owner, "locks")).clear();
                            case "invalid" -> slot.invalidate();
                            default -> {}
                          }
                          var refusal =
                              assertThrows(
                                  IllegalStateException.class,
                                  () ->
                                      verify(
                                          slot,
                                          id,
                                          params,
                                          mode.equals("connection") ? null : connection,
                                          size));
                          first[0] = owner.poisonProof(refusal);
                          return null;
                        }));
        assertSame(first[0], caught, mode);
        assertSame(first[0], q.recordedFailure());
        assertEquals(0, f.database.commits);
        assertEquals(1, f.database.rollbacks);
        assertEquals(65536, q.scratchLive());
      }
    }
  }

  @Test
  void nullWrongOppositeAndUnconfirmedSlotAlwaysUseTheFullSourceCheck() throws Exception {
    for (boolean size : new boolean[] {true, false}) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      f.work(
          q,
          () -> {
            var owner = SessionQueryTransaction.current(q);
            var slot = owner.beginHistory("t", "x");
            var id = new String(size ? SessionHistoryRows.SIZE : SessionHistoryRows.RAW);
            var connection = SessionQueryTransaction.connection(q);
            verify(slot, id, args(q), connection, size);
            for (String bad :
                new String[] {
                  null, "wrong", size ? SessionHistoryRows.RAW : SessionHistoryRows.SIZE
                }) {
              long before = q.resources().operations();
              assertThrows(
                  IllegalStateException.class, () -> verify(slot, bad, args(q), connection, size));
              assertEquals(
                  27 + (size ? SessionHistoryRows.SIZE.length() : SessionHistoryRows.RAW.length()),
                  q.resources().operations() - before);
              assertSame(id, field(slot, size ? "confirmedSizeId" : "confirmedRawId"));
            }
            put(slot, "finished", true); // 仅构造第二个独立槽；不制造raw或长度能力。
            var next = owner.beginHistory("t", "x");
            assertNull(field(next, "confirmedSizeId"));
            assertNull(field(next, "confirmedRawId"));
            long before = q.resources().operations();
            verify(next, id, args(q), connection, size);
            assertEquals(size ? 146 : 135, q.resources().operations() - before);
            return null;
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void requiresNewAndLaterTransactionCannotBorrowTheConfirmedSlot() throws Exception {
    for (String mode : List.of("requiresNew", "later")) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      var id = new String(SessionHistoryRows.SIZE);
      SessionHistoryRows.Slot[] saved = {null};
      if (mode.equals("requiresNew")) {
        assertThrows(
            RuntimeException.class,
            () ->
                f.work(
                    q,
                    () -> {
                      var owner = SessionQueryTransaction.current(q);
                      var slot = owner.beginHistory("t", "x");
                      verify(slot, id, args(q), SessionQueryTransaction.connection(q), true);
                      assertThrows(
                          RuntimeException.class,
                          () ->
                              f.transaction.requiresNew(
                                  q,
                                  () -> {
                                    try {
                                      verify(
                                          slot,
                                          id,
                                          args(q),
                                          SessionQueryTransaction.connection(q),
                                          true);
                                    } catch (RuntimeException refusal) {
                                      // 反射直调绕过Executor；在当前真实Work按生产调用边界登记首错。
                                      throw SessionQueryTransaction.current(q).poisonProof(refusal);
                                    }
                                    return null;
                                  }));
                      return null;
                    }));
        assertEquals(0, f.database.commits);
        assertEquals(2, f.database.rollbacks);
      } else {
        f.work(
            q,
            () -> {
              saved[0] = SessionQueryTransaction.current(q).beginHistory("t", "x");
              verify(saved[0], id, args(q), SessionQueryTransaction.connection(q), true);
              return null;
            });
        var later = SessionEventHistoryLengthTest.query();
        f.work(
            later,
            () -> {
              assertNull(field(saved[0], "confirmedSizeId"));
              assertThrows(
                  RuntimeException.class,
                  () ->
                      verify(
                          saved[0],
                          id,
                          args(later),
                          SessionQueryTransaction.connection(later),
                          true));
              var next = SessionQueryTransaction.current(later).beginHistory("t", "x");
              long start = later.resources().operations();
              verify(next, id, args(later), SessionQueryTransaction.connection(later), true);
              assertEquals(146, later.resources().operations() - start);
              return null;
            });
        assertEquals(65536, later.scratchLive());
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void firstAndRepeatedVerificationHaveExactPrepaidThresholdsAndNoPartialPublication()
      throws Exception {
    for (boolean size : new boolean[] {true, false}) {
      for (boolean repeated : new boolean[] {false, true}) {
        long needed = repeated ? 29 : size ? 146 : 135;
        for (long free : new long[] {needed - 1, needed, needed + 1}) {
          var f = new SessionHistoryLengthTest.Fixture();
          var q = SessionEventHistoryLengthTest.query();
          var rollback = new IllegalStateException("test rollback after successful boundary");
          RuntimeException[] first = {null};
          SessionHistoryRows.Slot[] saved = {null};
          var id = new String(size ? SessionHistoryRows.SIZE : SessionHistoryRows.RAW);
          var caught =
              assertThrows(
                  RuntimeException.class,
                  () ->
                      f.work(
                          q,
                          () -> {
                            var owner = SessionQueryTransaction.current(q);
                            var slot = owner.beginHistory("t", "x");
                            saved[0] = slot;
                            var connection = SessionQueryTransaction.connection(q);
                            if (repeated) verify(slot, id, args(q), connection, size);
                            q.operation(65536 - q.resources().operations() - free);
                            if (free < needed) {
                              first[0] =
                                  assertThrows(
                                      TaskFailure.class,
                                      () -> verify(slot, id, args(q), connection, size));
                              assertSame(
                                  repeated ? id : null,
                                  field(slot, size ? "confirmedSizeId" : "confirmedRawId"));
                              assertSame(first[0], owner.poisonProof(rollback));
                              return null;
                            }
                            long start = q.resources().operations();
                            verify(slot, id, args(q), connection, size);
                            assertEquals(needed, q.resources().operations() - start);
                            assertSame(
                                id, field(slot, size ? "confirmedSizeId" : "confirmedRawId"));
                            throw rollback;
                          }));
          assertSame(free < needed ? first[0] : rollback, caught);
          assertEquals(0, f.database.commits);
          assertEquals(1, f.database.rollbacks);
          assertEquals(65536, q.scratchLive());
          assertNull(field(saved[0], "confirmedSizeId"));
          assertNull(field(saved[0], "confirmedRawId"));
        }
      }
    }
    System.out.println("THRESHOLDS firstSize=146 firstRaw=135 repeat=29 N-1/N/N+1 verified");
  }

  @Test
  void slotOwnerAndCleanupPrepaymentRejectBeforeAllocationAndRefundOnce() throws Exception {
    for (String mode : List.of("scratch", "operations")) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      var held = new Object();
      RuntimeException[] first = {null};
      var caught =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.work(
                      q,
                      () -> {
                        var owner = SessionQueryTransaction.current(q);
                        if (mode.equals("scratch"))
                          q.retainScratch(held, 1048576 - q.scratchLive() - 64 - 4112 + 1);
                        else q.operation(65536 - q.resources().operations() - 82);
                        try {
                          first[0] =
                              assertThrows(TaskFailure.class, () -> owner.beginHistory("t", "x"));
                          assertNull(field(owner, "historyLength"));
                          assertEquals(0, field(owner, "historyLoads"));
                          assertSame(first[0], q.recordedFailure());
                        } finally {
                          q.releaseScratch(held);
                        }
                        return null;
                      }));
      assertSame(first[0], caught);
      assertEquals(0, f.database.commits);
      assertEquals(1, f.database.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Intercepts({
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class})
  })
  static class FaultAtPrepare implements Interceptor {
    final Throwable failure;

    FaultAtPrepare(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      throw failure;
    }
  }

  @Test
  void realMyBatisCaughtErrorAndSqlFailureKeepFirstCauseAndRollback() throws Exception {
    for (boolean fatal : new boolean[] {false, true}) {
      var f = new SessionHistoryLengthTest.Fixture();
      var q = SessionEventHistoryLengthTest.query();
      Throwable original =
          fatal
              ? new AssertionError("test-only prepare error")
              : new SQLException("test-only prepare SQL failure", "HY777");
      var cleanup = new IllegalStateException("test-only suppressed cleanup");
      original.addSuppressed(cleanup);
      f.sql.getSqlSessionFactory().getConfiguration().addInterceptor(new FaultAtPrepare(original));
      var caught =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.work(
                      q,
                      () -> {
                        assertThrows(Throwable.class, () -> f.load(q));
                        assertNotNull(q.recordedFailure());
                        return null;
                      }));
      assertSame(caught, q.recordedFailure());
      boolean found = false;
      for (Throwable cursor = caught; cursor != null; cursor = cursor.getCause())
        if (cursor == original) found = true;
      assertTrue(found);
      assertSame(cleanup, original.getSuppressed()[0]);
      if (!fatal) assertEquals("HY777", ((SQLException) original).getSQLState());
      assertEquals(0, f.database.commits);
      assertEquals(1, f.database.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }
}
