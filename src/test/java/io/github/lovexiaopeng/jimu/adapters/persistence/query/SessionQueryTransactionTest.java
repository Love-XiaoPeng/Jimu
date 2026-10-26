package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;

/** 真实 Spring 事务管理器加确定性 JDBC 清理故障；真实 MySQL 对照由独立夹具覆盖。 */
class SessionQueryTransactionTest {
  @Test
  void workFailureKeepsSqlCleanupAsSuppressed() {
    var fixture = new Fixture();
    var primary = new IllegalStateException("primary");
    fixture.reset = new SQLException("reset", "08003", 9001);
    assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.fail(primary)));
    assertSame(fixture.reset, primary.getSuppressed()[0]);
    assertEquals(1, fixture.rollbacks);
    assertEquals(0, fixture.commits);
  }

  @Test
  void workErrorKeepsRuntimeCleanupAsSuppressed() {
    var fixture = new Fixture();
    var primary = new AssertionError("primary");
    fixture.reset = new IllegalStateException("reset");
    assertSame(primary, assertThrows(AssertionError.class, () -> fixture.fail(primary)));
    assertSame(fixture.reset, primary.getSuppressed()[0]);
  }

  @Test
  void selfSuppressionDoesNotReplacePrimary() {
    var fixture = new Fixture();
    var primary = new IllegalStateException("primary");
    fixture.reset = primary;
    assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.fail(primary)));
    assertEquals(0, primary.getSuppressed().length);
  }

  @Test
  void successfulWorkStillPropagatesCleanupFailure() {
    var fixture = new Fixture();
    var cleanup = new IllegalStateException("reset");
    fixture.reset = cleanup;
    assertSame(
        cleanup,
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.execute(
                    () -> {
                      fixture.active.set(true);
                      return "ok";
                    })));
    assertEquals(1, fixture.rollbacks);
    assertEquals(0, fixture.commits);
  }

  @Test
  void rollbackFailureExposesApplicationInStandardThrowableGraph() {
    var fixture = new Fixture();
    var primary = new IllegalStateException("primary");
    fixture.reset = new SQLException("reset", "08003", 9001);
    fixture.rollback = new SQLException("rollback ACK", "08006", 9003);
    var caught = assertThrows(TransactionSystemException.class, () -> fixture.fail(primary));
    assertSame(fixture.rollback, caught.getCause());
    assertSame(primary, caught.getApplicationException());
    assertSame(primary, caught.getSuppressed()[0]);
    assertSame(fixture.reset, primary.getSuppressed()[0]);
  }

  @Test
  void timeoutKeepsCauseAndRollbackApplicationExceptionWithoutChangingCode() {
    var fixture = new Fixture();
    var primary = new DataAccessResourceFailureException("primary");
    fixture.reset = new SQLException("reset", "08003", 9001);
    fixture.rollback = new SQLException("rollback ACK", "08006", 9003);
    var caught =
        assertThrows(
            TaskFailure.class,
            () ->
                fixture.execute(
                    () -> {
                      fixture.context.restrictDeadline(System.nanoTime() - 1);
                      fixture.active.set(true);
                      throw primary;
                    }));
    assertEquals(504, caught.status());
    assertEquals("SESSION_QUERY_TIMEOUT", caught.code());
    assertEquals("SESSION_QUERY_TIMEOUT", caught.getMessage());
    var transaction = (TransactionSystemException) caught.getCause();
    assertSame(fixture.rollback, transaction.getCause());
    assertSame(primary, transaction.getApplicationException());
    assertSame(primary, transaction.getSuppressed()[0]);
    assertSame(fixture.reset, primary.getSuppressed()[0]);
    assertEquals(1, fixture.rollbacks);
  }

  @Test
  void afterCompletionAloneKeepsPrimary() {
    var fixture = new Fixture();
    var primary = new IllegalStateException("primary");
    fixture.afterCompletion = new SQLException("afterCompletion", "08003", 9002);
    assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.fail(primary)));
    assertEquals(1, fixture.afterHits);
    assertEquals(0, primary.getSuppressed().length);
  }

  @Test
  void successfulWorkCommitsOnceAndReturnsOriginalResult() {
    var fixture = new Fixture();
    Object result = new Object();
    assertSame(result, fixture.execute(() -> result));
    assertEquals(1, fixture.commits);
    assertEquals(0, fixture.rollbacks);
  }

  private static final class Fixture {
    final AtomicBoolean active = new AtomicBoolean();
    final SessionQueryContext context =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
    Throwable reset;
    SQLException rollback, afterCompletion;
    int commits, rollbacks, afterHits;
    final Connection connection =
        proxy(
            Connection.class,
            (object, method, args) -> {
              return switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "getNetworkTimeout" -> 0;
                case "isClosed", "isReadOnly", "isWrapperFor" -> false;
                case "createStatement" -> {
                  if (active.get() && reset != null) throw reset;
                  yield proxy(
                      Statement.class,
                      (statement, operation, parameters) ->
                          switch (operation.getName()) {
                            case "executeQuery" ->
                                proxy(
                                    ResultSet.class,
                                    (row, column, index) ->
                                        switch (column.getName()) {
                                          case "next" -> true;
                                          case "getInt" -> 50;
                                          default -> null;
                                        });
                            case "execute" -> false;
                            default -> null;
                          });
                }
                case "commit" -> {
                  commits++;
                  yield null;
                }
                case "rollback" -> {
                  rollbacks++;
                  if (rollback != null) throw rollback;
                  yield null;
                }
                case "setNetworkTimeout" -> {
                  if (active.get() && afterCompletion != null) {
                    afterHits++;
                    throw afterCompletion;
                  }
                  yield null;
                }
                default -> null;
              };
            });

    <T> T execute(Supplier<T> work) {
      var source =
          new AbstractDataSource() {
            @Override
            public Connection getConnection() {
              return connection;
            }

            @Override
            public Connection getConnection(String user, String password) {
              return connection;
            }
          };
      return new SessionQueryTransaction(source, new DataSourceTransactionManager(source))
          .required(context, work);
    }

    void fail(Throwable primary) {
      execute(
          () -> {
            active.set(true);
            if (primary instanceof Error error) throw error;
            throw (RuntimeException) primary;
          });
    }
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
