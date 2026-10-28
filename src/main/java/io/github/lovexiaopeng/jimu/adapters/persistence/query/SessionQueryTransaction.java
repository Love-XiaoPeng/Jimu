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
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same DS/manager as MyBatis; the absolute deadline includes acquisition, work and commit. */
public final class SessionQueryTransaction {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(SessionQueryTransaction.class);
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

  /** Spring挂起同步器即挂起证明；不以query身份冒充事务身份。 */
  public static SessionQueryWork current(SessionQueryContext context) {
    if (context == null || !TransactionSynchronizationManager.isSynchronizationActive())
      return null;
    for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
      if (sync instanceof ConnectionSettings settings) {
        if (settings.context != context)
          throw new IllegalStateException("Different query in session transaction");
        return settings.work;
      }
    }
    return null;
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
                        if (TransactionSynchronizationManager.getSynchronizations().stream()
                            .anyMatch(
                                s ->
                                    s instanceof ConnectionSettings settings
                                        && settings.context == context)) {
                          // 同一query加入已有事务：原持有者负责设置、恢复与提交截止。
                          T result = work.get();
                          context.remainingMillis();
                          return result;
                        }
                        if (!status.isNewTransaction())
                          throw new IllegalStateException(
                              "Session query requires an owned transaction");
                        var owner = new SessionQueryWork(context);
                        int priorNetwork = connection.getNetworkTimeout();
                        TransactionSynchronizationManager.registerSynchronization(
                            new ConnectionSettings(context, connection, priorNetwork, owner));
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
                        Throwable primary = null;
                        try {
                          // 即使第二条SET或截止检查失败，已修改的第一项也必须恢复。
                          try (var statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                            statement.execute(
                                "SET SESSION max_execution_time=" + context.remainingMillis());
                          }
                          T result = work.get();
                          owner.finish();
                          context.remainingMillis();
                          return result;
                        } catch (java.sql.SQLException | RuntimeException | Error failure) {
                          primary = failure;
                          throw failure;
                        } finally {
                          try (var statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout=" + priorWait);
                            statement.execute("SET SESSION max_execution_time=" + priorMax);
                          } catch (java.sql.SQLException | RuntimeException | Error cleanup) {
                            // 清理失败不能替换业务首错；成功路径的清理失败仍须传播。
                            if (primary == null) throw cleanup;
                            if (cleanup != primary) primary.addSuppressed(cleanup);
                          }
                        }
                      }));
    } catch (RuntimeException failure) {
      // Spring 的 rollback 首错另存在 applicationException，不一定在标准 cause 链中。
      if (failure instanceof TransactionSystemException transactionFailure) {
        Throwable application = transactionFailure.getApplicationException();
        if (application != null && application != failure.getCause())
          failure.addSuppressed(application);
      }
      // MyBatis/Spring may wrap deterministic meter refusals in a persistence exception.
      // A confirmed rollback is not an unknown commit and must not trigger slot retries.
      if (!(failure instanceof TransactionSystemException)) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
          if (cause instanceof TaskFailure refusal) throw refusal;
        }
      }
      if (System.nanoTime() >= context.deadlineNanos()
          && (failure instanceof org.springframework.dao.DataAccessException
              || failure instanceof org.springframework.transaction.TransactionException)) {
        var timeout = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
        timeout.initCause(failure);
        throw timeout;
      }
      throw failure;
    }
  }

  private static final class ConnectionSettings implements TransactionSynchronization {
    private final SessionQueryContext context;
    private final java.sql.Connection connection;
    private final int priorNetwork;
    private final SessionQueryWork work;

    ConnectionSettings(
        SessionQueryContext context,
        java.sql.Connection connection,
        int priorNetwork,
        SessionQueryWork work) {
      this.context = context;
      this.connection = connection;
      this.priorNetwork = priorNetwork;
      this.work = work;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      work.beforeCommit();
      try {
        connection.setNetworkTimeout(Runnable::run, context.remainingMillis());
      } catch (java.sql.SQLException failure) {
        throw new org.springframework.dao.DataAccessResourceFailureException(
            "Session query unavailable", failure);
      }
    }

    @Override
    public void afterCompletion(int completion) {
      work.complete();
      LOG.info(
          "Session query totals query={} deadlineNanos={} reads={} bytes={} operations={} scratchPeak={} groups={} statements={}",
          System.identityHashCode(context),
          context.deadlineNanos(),
          context.resources().reads(),
          context.resources().bytes(),
          context.resources().operations(),
          context.scratchPeak(),
          context.groups(),
          context.statements());
      LOG.info(
          "Session query transaction completion={} totalReads={} remainingMicros={}",
          switch (completion) {
            case STATUS_COMMITTED -> "COMMITTED";
            case STATUS_ROLLED_BACK -> "ROLLED_BACK";
            default -> "UNKNOWN";
          },
          context.resources().reads(),
          (context.deadlineNanos() - System.nanoTime()) / 1000);
      try {
        if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, priorNetwork);
      } catch (java.sql.SQLException failure) {
        throw new org.springframework.dao.DataAccessResourceFailureException(
            "Session connection reset unavailable (afterCompletion)", failure);
      }
    }
  }
}
