package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    var caught =
        assertThrows(
            io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed
                .class,
            () -> fixture.fail(primary));
    var original = (TransactionSystemException) caught.getCause();
    assertSame(fixture.rollback, original.getCause());
    assertSame(primary, caught.getApplicationException());
    assertSame(primary, original.getSuppressed()[0]);
    assertSame(fixture.reset, primary.getSuppressed()[0]);
  }

  @Test
  void timeoutCannotOverrideUnknownRollback() {
    var fixture = new Fixture();
    var primary = new DataAccessResourceFailureException("primary");
    fixture.reset = new SQLException("reset", "08003", 9001);
    fixture.rollback = new SQLException("rollback ACK", "08006", 9003);
    var caught =
        assertThrows(
            io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed
                .class,
            () ->
                fixture.execute(
                    () -> {
                      fixture.context.restrictDeadline(System.nanoTime() - 1);
                      fixture.active.set(true);
                      throw primary;
                    }));
    assertEquals(
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome.Completion
            .UNKNOWN,
        caught.outcome().completion());
    assertEquals(
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome.Phase
            .BUSINESS,
        caught.outcome().phase());
    var original = (TransactionSystemException) caught.getCause();
    assertSame(fixture.rollback, original.getCause());
    assertSame(primary, original.getApplicationException());
    assertSame(primary, original.getSuppressed()[0]);
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
    assertSame(fixture.afterCompletion, primary.getSuppressed()[0]);
  }

  @Test
  void wrappedMeterRefusalAfterConfirmedRollbackKeepsOriginalCode() {
    var fixture = new Fixture();
    var refusal = new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    var wrapped = new DataAccessResourceFailureException("mapper wrapper", refusal);
    assertSame(refusal, assertThrows(TaskFailure.class, () -> fixture.fail(wrapped)));
    assertEquals(1, fixture.rollbacks);
    assertEquals(0, fixture.commits);
  }

  @Test
  void successfulWorkCommitsOnceAndReturnsOriginalResult() {
    var fixture = new Fixture();
    Object result = new Object();
    assertSame(result, fixture.execute(() -> result));
    assertEquals(1, fixture.commits);
    assertEquals(0, fixture.rollbacks);
    assertEquals(7000, fixture.network);
    assertEquals(50, fixture.lockWait);
    assertEquals(50, fixture.maxExecution);
  }

  @Test
  void partialSetupFailureRestoresEarlierSettingAndRollsBackWithoutWork() {
    var fixture = new Fixture();
    fixture.setup = new SQLException("second SET failed", "42000", 9004);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            fixture.execute(
                () -> {
                  throw new AssertionError("work after setup failure");
                }));
    assertEquals(50, fixture.lockWait);
    assertEquals(50, fixture.maxExecution);
    assertEquals(7000, fixture.network);
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
  }

  @Test
  void deadlineAfterWorkRollsBackAndCannotCommitStagedChanges() {
    var fixture = new Fixture();
    var failure =
        assertThrows(
            TaskFailure.class,
            () ->
                fixture.execute(
                    () -> {
                      fixture.context.restrictDeadline(System.nanoTime() - 1);
                      return "staged";
                    }));
    assertEquals("SESSION_QUERY_TIMEOUT", failure.code());
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
    assertEquals(7000, fixture.network);
  }

  @Test
  void commitAcknowledgementLossIsNotReportedAsConfirmedRollback() {
    var fixture = new Fixture();
    fixture.commit = new SQLException("commit ACK", "08006", 9005);
    var failure =
        assertThrows(TransactionSystemException.class, () -> fixture.execute(() -> "staged"));
    assertSame(fixture.commit, failure.getCause().getCause());
    assertEquals(1, fixture.commits);
    assertEquals(0, fixture.rollbacks);
    assertEquals(7000, fixture.network);
  }

  @Test
  void nestedRequiredUsesOneSettingsOwnerAndOneCommit() {
    var fixture = new Fixture();
    assertEquals(
        "nested",
        fixture.execute(
            () ->
                fixture.execute(
                    () -> {
                      assertTrue(fixture.network <= 2000);
                      assertEquals(1, fixture.lockWait);
                      return "nested";
                    })));
    assertEquals(1, fixture.commits);
    assertEquals(0, fixture.rollbacks);
    assertEquals(50, fixture.lockWait);
    assertEquals(50, fixture.maxExecution);
    assertEquals(7000, fixture.network);
  }

  @Test
  void deadlineBeforeCommitRollsBackInsteadOfCommittingEarlierSuccessfulWork() {
    var fixture = new Fixture();
    assertEquals(
        "SESSION_QUERY_TIMEOUT",
        assertThrows(
                TaskFailure.class,
                () ->
                    fixture.execute(
                        () -> {
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .registerSynchronization(
                                  new org.springframework.transaction.support
                                      .TransactionSynchronization() {
                                    public int getOrder() {
                                      return Integer.MIN_VALUE;
                                    }

                                    public void beforeCommit(boolean readOnly) {
                                      fixture.context.restrictDeadline(System.nanoTime() - 1);
                                    }
                                  });
                          return "staged";
                        }))
            .code());
    assertEquals(0, fixture.commits);
    assertEquals(1, fixture.rollbacks);
    assertEquals(7000, fixture.network);
  }

  static final class Fixture {
    final AtomicBoolean active = new AtomicBoolean();
    final SessionQueryContext context =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
    Throwable reset;
    SQLException rollback, afterCompletion, setup, commit;
    int commits, rollbacks, afterHits;
    int network = 7000, lockWait = 50, maxExecution = 50;
    final Connection connection =
        proxy(
            Connection.class,
            (object, method, args) -> {
              return switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "getNetworkTimeout" -> network;
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
                            case "execute" -> {
                              String sql = (String) parameters[0];
                              if (sql.startsWith("SET SESSION innodb_lock_wait_timeout="))
                                lockWait = Integer.parseInt(sql.substring(sql.indexOf('=') + 1));
                              if (sql.startsWith("SET SESSION max_execution_time=")) {
                                int value = Integer.parseInt(sql.substring(sql.indexOf('=') + 1));
                                if (setup != null && value != 50) throw setup;
                                maxExecution = value;
                              }
                              yield false;
                            }
                            default -> null;
                          });
                }
                case "commit" -> {
                  commits++;
                  if (commit != null) throw commit;
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
                  network = (int) args[1];
                  yield null;
                }
                default -> null;
              };
            });

    final AbstractDataSource source =
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
    final SessionQueryTransaction transaction =
        new SessionQueryTransaction(source, new DataSourceTransactionManager(source));

    <T> T execute(Supplier<T> work) {
      return transaction.required(context, work);
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
