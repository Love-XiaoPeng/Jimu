package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeMapper;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionContentState;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeMaintenanceRecords;
import java.util.List;
import java.util.function.Supplier;

/** Spring事务异常的窄桥接；不改原异常传播／安全日志和最后截止检查。 */
final class MapperSessionScopeMaintenanceRecords implements SessionScopeMaintenanceRecords {
  private final JdbcLedger ledger;
  private final SessionScopeMapper sql;

  MapperSessionScopeMaintenanceRecords(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionScopeMapper.class);
  }

  public <T> T required(long end, Supplier<T> work) {
    try {
      return ledger.sessionTransaction(work, end);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException failure) {
      throw new StorageFailure(failure);
    }
  }

  public <T> T requiresNew(Supplier<T> work) {
    return ledger.scopeRequiresNew(work);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(JdbcLedger.s(r, "tenant_id"), JdbcLedger.s(r, "caller_task_id")))
        .toList();
  }

  public Locked lock(Candidate c) {
    ledger.bucket(c.tenant());
    var task = ledger.locked(c.tenant(), c.caller());
    return new Locked() {
      public void defer() {
        sql.defer(ledger.queryContext(), c.tenant(), c.caller());
      }

      public void account() {
        ledger.sessions.account(task, true);
      }

      public void requireCurrent() {
        ledger.access(task);
      }

      public void invalidate() {
        sql.invalidate(ledger.queryContext(), c.tenant(), c.caller());
      }

      public boolean invalidated() {
        return ledger
                .sessionScopes
                .records
                .scope(MapperSessionScopeRecords.caller(task))
                .invalidated()
            != null;
      }

      public boolean historyPresent() {
        return SessionContentState.PRESENT.equals(
            JdbcLedger.s(ledger.sessions.history(task, false), "content_state"));
      }

      public boolean cleanupSafe() {
        return ledger.sessionScopes.eligibility.cleanupSafe(MapperSessionScopeRecords.caller(task));
      }

      public void purge() {
        ledger.historyRetention.purge(ledger.historyRetentionRecords.bind(task));
      }

      public void invalidateAndSchedule() {
        sql.invalidateAndSchedule(ledger.queryContext(), c.tenant(), c.caller());
      }
    };
  }

  public void remaining() {
    ledger.queryContext().remainingMillis();
  }

  public void deferred(boolean scan, String reason) {
    System.getLogger(JdbcLedger.class.getName())
        .log(
            System.Logger.Level.WARNING,
            "Session scope validation " + (scan ? "scan " : "") + "deferred: " + reason);
  }
}
