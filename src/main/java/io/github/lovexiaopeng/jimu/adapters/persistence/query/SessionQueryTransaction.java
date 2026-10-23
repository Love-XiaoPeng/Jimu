package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same DS/manager as MyBatis; the absolute deadline includes acquisition, work and commit. */
public final class SessionQueryTransaction {
  private final DataSourceTransactionManager manager;
  private final JdbcTemplate db;

  public SessionQueryTransaction(DataSource source, DataSourceTransactionManager manager) {
    this.manager = manager;
    db = new JdbcTemplate(source);
  }

  public <T> T required(SessionQueryContext context, Supplier<T> work) {
    return execute(context, work, TransactionDefinition.PROPAGATION_REQUIRED);
  }

  public <T> T requiresNew(SessionQueryContext context, Supplier<T> work) {
    return execute(context, work, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private <T> T execute(SessionQueryContext context, Supplier<T> work, int propagation) {
    var transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(propagation);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(2);
    context.remainingMillis();
    try (var deadline = new SessionQueryDeadline(context.deadlineNanos())) {
      return transaction.execute(
          status ->
              db.execute(
                  (ConnectionCallback<T>)
                      connection -> {
                        int priorNetwork = connection.getNetworkTimeout();
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public void beforeCommit(boolean readOnly) {
                                try {
                                  connection.setNetworkTimeout(
                                      Runnable::run, context.remainingMillis());
                                } catch (java.sql.SQLException failure) {
                                  throw new org.springframework.dao
                                      .DataAccessResourceFailureException(
                                      "Session query unavailable", failure);
                                }
                              }

                              @Override
                              public void afterCompletion(int completion) {
                                try {
                                  if (!connection.isClosed())
                                    connection.setNetworkTimeout(Runnable::run, priorNetwork);
                                } catch (java.sql.SQLException failure) {
                                  throw new org.springframework.dao
                                      .DataAccessResourceFailureException(
                                      "Session connection reset unavailable", failure);
                                }
                              }
                            });
                        connection.setNetworkTimeout(Runnable::run, context.remainingMillis());
                        int priorWait, priorMax;
                        try (var statement = connection.createStatement();
                            var result =
                                statement.executeQuery(
                                    "SELECT @@SESSION.innodb_lock_wait_timeout,@@SESSION.max_execution_time")) {
                          result.next();
                          priorWait = result.getInt(1);
                          priorMax = result.getInt(2);
                        }
                        try (var statement = connection.createStatement()) {
                          statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                          statement.execute(
                              "SET SESSION max_execution_time=" + context.remainingMillis());
                        }
                        try {
                          T result = work.get();
                          context.remainingMillis();
                          return result;
                        } finally {
                          try (var statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout=" + priorWait);
                            statement.execute("SET SESSION max_execution_time=" + priorMax);
                          }
                        }
                      }));
    } catch (RuntimeException failure) {
      if (System.nanoTime() >= context.deadlineNanos()
          && (failure instanceof org.springframework.dao.DataAccessException
              || failure instanceof org.springframework.transaction.TransactionException))
        throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
      throw failure;
    }
  }
}
