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
  static final long METADATA_BINDING_BYTES = 4096;
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

  static java.sql.Connection connection(SessionQueryContext context) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
    for (var sync : TransactionSynchronizationManager.getSynchronizations())
      if (sync instanceof ConnectionSettings settings && settings.context == context)
        return settings.connection
                instanceof org.springframework.jdbc.datasource.ConnectionProxy proxy
            ? proxy.getTargetConnection()
            : settings.connection;
    return null;
  }

  /** 先认实际事务，再验显式query；缺参不能把已有owner变为旧版无query路径。 */
  public static SessionQueryWork currentOwner() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
    for (var sync : TransactionSynchronizationManager.getSynchronizations())
      if (sync instanceof ConnectionSettings settings) return settings.work;
    return null;
  }

  // A private Spring resource identifies only the actual, unsuspended transaction.
  // Unlike getSynchronizations(), getResource() creates no collection snapshot.
  // No caller supplied query, authority, rows or ancestor ThreadLocal is used as a key.
  static SessionQueryWork metadataOwner() {
    Object resource = TransactionSynchronizationManager.getResource(ConnectionSettings.class);
    if (!(resource instanceof ConnectionSettings settings)) return null;
    // In an active owned transaction its Spring resource Map already exists. Recover the
    // payer before later provider calls; return its identity solely for failure latching.
    try {
      settings.context.rejectedOperation(64);
      if (!settings.metadataBound
          || !settings.work.metadataIdentity(settings.context, settings.metadataToken)
          || !TransactionSynchronizationManager.isSynchronizationActive()
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.getResource(settings.source) != settings.holder
          || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                  settings.holder.getConnection())
              != settings.physicalConnection)
        settings.work.recordMetadataFailure(settings.metadataFailure);
    } catch (RuntimeException failure) {
      settings.work.recordMetadataFailure(failure);
    } catch (Error failure) {
      settings.work.recordMetadataError(failure);
      throw failure;
    }
    return settings.work;
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
                        var settings =
                            new ConnectionSettings(
                                context, connection, owner, receipt, db.getDataSource());
                        TransactionSynchronizationManager.registerSynchronization(settings);
                        try {
                          settings.installMetadata();
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
      if (receipt.completion == Completion.NOT_STARTED) throw failure;
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
        if (context.recordedFailure() != null) throw context.recordedFailure();
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

  private static final class MetadataAccessFailure extends IllegalStateException {
    MetadataAccessFailure() {
      super("Invalid metadata transaction");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private static final class ConnectionSettings implements TransactionSynchronization {
    private final SessionQueryContext context;
    private final java.sql.Connection connection;
    private Integer priorNetwork;
    private final SessionQueryWork work;
    private final Receipt receipt;
    private final DataSource source;
    private org.springframework.jdbc.datasource.ConnectionHolder holder;
    private java.sql.Connection physicalConnection;
    private Object metadataToken;
    private boolean metadataBound, metadataRetained;
    private RuntimeException metadataFailure;

    ConnectionSettings(
        SessionQueryContext context,
        java.sql.Connection connection,
        SessionQueryWork work,
        Receipt receipt,
        DataSource source) {
      this.context = context;
      this.connection = connection;
      this.work = work;
      this.receipt = receipt;
      this.source = source;
    }

    private void installMetadata() {
      // New binding node/provider aliases and stackless rejection; reserve before allocation.
      context.operation(96);
      context.retainScratch(this, METADATA_BINDING_BYTES);
      metadataRetained = true;
      metadataFailure = new MetadataAccessFailure();
      metadataToken = work.token();
      physicalConnection =
          org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(connection);
      Object resource = TransactionSynchronizationManager.getResource(source);
      if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder current)
          || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                  current.getConnection())
              != physicalConnection
          || TransactionSynchronizationManager.hasResource(ConnectionSettings.class))
        throw work.recordMetadataFailure(metadataFailure);
      holder = current;
      TransactionSynchronizationManager.bindResource(ConnectionSettings.class, this);
      metadataBound = true;
    }

    @Override
    public void suspend() {
      if (!metadataBound) return;
      // Pay every suspend/resume pair, not an unlimited construction-time allowance.
      context.operation(64);
      detachMetadata();
    }

    @Override
    public void resume() {
      if (!metadataRetained) return;
      TransactionSynchronizationManager.bindResource(ConnectionSettings.class, this);
      metadataBound = true;
    }

    private void detachMetadata() {
      if (!metadataBound) return;
      // No second unbind or cleanup-time exception when an earlier fault removed the key.
      if (TransactionSynchronizationManager.getResource(ConnectionSettings.class) == this)
        TransactionSynchronizationManager.unbindResourceIfPossible(ConnectionSettings.class);
      metadataBound = false;
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
      // Unbind even on setup failure/rollback/unknown completion; never expose an ended owner.
      detachMetadata();
      holder = null;
      physicalConnection = null;
      metadataToken = null;
      metadataFailure = null;
      if (metadataRetained) {
        metadataRetained = false;
        context.releaseScratch(this);
      }
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
