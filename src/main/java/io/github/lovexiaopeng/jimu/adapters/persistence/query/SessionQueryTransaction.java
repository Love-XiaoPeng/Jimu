package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome.Completion;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome.Phase;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed;
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
    boolean borrowed =
        propagation == TransactionDefinition.PROPAGATION_REQUIRED && current(context) != null;
    if (!borrowed
        && propagation == TransactionDefinition.PROPAGATION_REQUIRED
        && TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Session query requires an owned transaction");
    var receipt = new Receipt(context);
    context.remainingMillis();
    try (var deadline = new SessionQueryDeadline(context.deadlineNanos())) {
      receipt.phase = Phase.BEGIN;
      receipt.completion = Completion.BEGIN_UNCONFIRMED;
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
                        receipt.owner = System.identityHashCode(owner.token());
                        receipt.connection = System.identityHashCode(connection);
                        receipt.phase = Phase.CONNECTION_SETUP;
                        receipt.completion = Completion.UNKNOWN;
                        var settings = new ConnectionSettings(context, connection, owner, receipt);
                        TransactionSynchronizationManager.registerSynchronization(settings);
                        try {
                          settings.priorNetwork = connection.getNetworkTimeout();
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
                            receipt.phase = Phase.BUSINESS;
                            T result = work.get();
                            receipt.phase = Phase.FINALIZE;
                            owner.finish();
                            context.remainingMillis();
                            return result;
                          } catch (java.sql.SQLException | RuntimeException | Error failure) {
                            primary = failure;
                            throw failure;
                          } finally {
                            try (var statement = connection.createStatement()) {
                              statement.execute(
                                  "SET SESSION innodb_lock_wait_timeout=" + priorWait);
                              statement.execute("SET SESSION max_execution_time=" + priorMax);
                            } catch (java.sql.SQLException | RuntimeException | Error cleanup) {
                              // 清理失败不能替换业务首错；成功路径的清理失败仍须传播。
                              if (primary == null) throw cleanup;
                              if (cleanup != primary) primary.addSuppressed(cleanup);
                            }
                          }
                        } catch (java.sql.SQLException | RuntimeException | Error failure) {
                          receipt.primary = failure;
                          throw failure;
                        }
                      }));
    } catch (RuntimeException failure) {
      // Spring 的 rollback 首错另存在 applicationException，不一定在标准 cause 链中。
      if (failure instanceof TransactionSystemException transactionFailure) {
        Throwable application = transactionFailure.getApplicationException();
        if (application != null && application != failure.getCause())
          failure.addSuppressed(application);
      }
      if (!borrowed
          && receipt.completion != Completion.COMMITTED
          && receipt.completion != Completion.ROLLED_BACK) {
        if (receipt.primary != null
            && receipt.primary != failure
            && receipt.primary != failure.getCause()
            && java.util.Arrays.stream(failure.getSuppressed())
                .noneMatch(t -> t == receipt.primary)) failure.addSuppressed(receipt.primary);
        LOG.warn(
            "Session transaction unconfirmed outcome={} failure={} primary={}",
            receipt.outcome(),
            failure.getClass().getSimpleName(),
            receipt.primary == null ? "none" : receipt.primary.getClass().getSimpleName());
        throw new SessionTransactionUnconfirmed(failure, receipt.outcome());
      }
      // MyBatis/Spring may wrap deterministic meter refusals in a persistence exception.
      // A confirmed rollback is not an unknown commit and must not trigger slot retries.
      if (!borrowed && receipt.completion == Completion.ROLLED_BACK) {
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

  private static final class Receipt {
    private final SessionQueryContext context;
    private Completion completion = Completion.NOT_STARTED;
    private Phase phase = Phase.BEFORE_EXECUTE;
    private int owner, connection;
    private Throwable primary;

    Receipt(SessionQueryContext context) {
      this.context = context;
    }

    SessionTransactionOutcome outcome() {
      return new SessionTransactionOutcome(
          completion,
          phase,
          System.identityHashCode(context),
          owner,
          connection,
          context.deadlineNanos());
    }
  }

  private static final class ConnectionSettings implements TransactionSynchronization {
    private final SessionQueryContext context;
    private final java.sql.Connection connection;
    private Integer priorNetwork;
    private final SessionQueryWork work;
    private final Receipt receipt;

    ConnectionSettings(
        SessionQueryContext context,
        java.sql.Connection connection,
        SessionQueryWork work,
        Receipt receipt) {
      this.context = context;
      this.connection = connection;
      this.work = work;
      this.receipt = receipt;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      receipt.phase = Phase.BEFORE_COMMIT;
      try {
        work.beforeCommit();
        try {
          connection.setNetworkTimeout(Runnable::run, context.remainingMillis());
        } catch (java.sql.SQLException failure) {
          throw new org.springframework.dao.DataAccessResourceFailureException(
              "Session query unavailable", failure);
        }
      } catch (RuntimeException | Error failure) {
        receipt.primary = failure;
        throw failure;
      }
    }

    @Override
    public void afterCompletion(int completion) {
      work.complete();
      receipt.completion =
          switch (completion) {
            case STATUS_COMMITTED -> Completion.COMMITTED;
            case STATUS_ROLLED_BACK -> Completion.ROLLED_BACK;
            default -> Completion.UNKNOWN;
          };
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
          "Session query transaction outcome={} totalReads={} remainingMicros={}",
          receipt.outcome(),
          context.resources().reads(),
          (context.deadlineNanos() - System.nanoTime()) / 1000);
      try {
        if (priorNetwork != null && !connection.isClosed())
          connection.setNetworkTimeout(Runnable::run, priorNetwork);
      } catch (java.sql.SQLException | RuntimeException cleanup) {
        if (receipt.primary != null && receipt.primary != cleanup)
          receipt.primary.addSuppressed(cleanup);
        LOG.warn(
            "Session connection cleanup failed outcome={} failure={}",
            receipt.outcome(),
            cleanup.getClass().getSimpleName());
      }
    }
  }
}
