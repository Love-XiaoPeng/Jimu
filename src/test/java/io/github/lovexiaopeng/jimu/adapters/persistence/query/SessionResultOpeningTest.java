package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;

/** 语句已经执行但cursor尚未交给consumer时，拒绝仍须关闭真实JDBC所有者。 */
class SessionResultOpeningTest {
  public record Parameters(Object parameterObject) {
    public Object getParameterObject() {
      return parameterObject;
    }
  }

  public static final class Handler implements ResultSetHandler {
    public final Parameters parameterHandler;
    final RuntimeException failure;
    int calls;

    Handler(SessionQueryContext query, RuntimeException failure) {
      parameterHandler = new Parameters(Map.of("query", query));
      this.failure = failure;
    }

    public <E> List<E> handleResultSets(Statement statement) {
      calls++;
      throw failure;
    }

    public <E> Cursor<E> handleCursorResultSets(Statement statement) {
      calls++;
      throw failure;
    }

    public void handleOutputParameters(CallableStatement statement) {
      throw new AssertionError();
    }
  }

  @Test
  void deadlineBeforeResultHandlerClosesExecutedStatementAndPreservesCleanupCause()
      throws Exception {
    for (String method : List.of("handleResultSets", "handleCursorResultSets")) {
      var query =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      query.restrictLineage();
      query.restrictDeadline(System.nanoTime() - 1);
      var handler = new Handler(query, new IllegalStateException("must not enter"));
      int[] closed = {0};
      var cleanup = new SQLException("close marker", "08003");
      var statement =
          (Statement)
              Proxy.newProxyInstance(
                  Statement.class.getClassLoader(),
                  new Class<?>[] {Statement.class},
                  (proxy, called, args) -> {
                    if (!called.getName().equals("close")) throw new AssertionError(called);
                    closed[0]++;
                    throw cleanup;
                  });
      var call =
          new Invocation(
              handler,
              ResultSetHandler.class.getMethod(method, Statement.class),
              new Object[] {statement});
      var failure =
          assertThrows(TaskFailure.class, () -> new SessionQueryInterceptor().intercept(call));
      assertSame(query.recordedFailure(), failure);
      assertSame(cleanup, failure.getSuppressed()[0]);
      assertEquals("08003", ((SQLException) failure.getSuppressed()[0]).getSQLState());
      assertEquals(1, closed[0]);
      assertEquals(0, handler.calls);
    }
  }

  @Test
  void failureAfterHandlerEntryClosesStatementWithoutReplacingOriginal() throws Exception {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    var marker = new IllegalStateException("handler marker");
    var handler = new Handler(query, marker);
    int[] closed = {0};
    var cleanup = new SQLException("handler cleanup marker", "08003");
    var statement =
        (Statement)
            Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("close")) throw new AssertionError(method);
                  closed[0]++;
                  throw cleanup;
                });
    var call =
        new Invocation(
            handler,
            ResultSetHandler.class.getMethod("handleCursorResultSets", Statement.class),
            new Object[] {statement});
    assertSame(
        marker,
        assertThrows(RuntimeException.class, () -> new SessionQueryInterceptor().intercept(call)));
    assertSame(cleanup, marker.getSuppressed()[0]);
    assertEquals(1, handler.calls);
    assertEquals(1, closed[0]);
  }
}
