package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** B1 transition only: named record operations; B2 replaces these SQL bodies with Mapper calls. */
final class JdbcCoreRecords {
  private final JdbcLedger ledger;
  private final JdbcTemplate db;

  JdbcCoreRecords(JdbcLedger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  private static String s(Map<String, Object> row, String name) {
    return (String) row.get(name);
  }

  ExecutionRecords execution() {
    return new JdbcExecutionRecords(ledger, db);
  }

  InvocationRecords invocation() {
    return new InvocationRecords() {
      @Override
      public void requireOrdinaryCommit(Lease lease, String invocation) {
        ledger.rejectTodoBypass(lease, invocation);
      }

      @Override
      public Invocation lockSettlement(Lease lease, String invocation) {
        var row = ledger.fenced(lease, false);
        return new Invocation() {
          @Override
          public boolean session() {
            return JdbcSessionRecords.task(row);
          }

          @Override
          public int failRunning() {
            return db.update(
                "UPDATE invocation SET state='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                lease.tenant(),
                invocation,
                lease.taskId());
          }

          @Override
          public void settleUnit() {
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
                lease.tenant(),
                invocation);
          }

          @Override
          public void failureEvent(String code) {
            ledger.event(row, "failure:" + invocation, "tool.failed", code);
          }

          @Override
          public void markUnknown() {
            db.update(
                "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                lease.tenant(),
                invocation,
                lease.taskId());
          }

          @Override
          public void holdUnknown() {
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=? AND state='RESERVED'",
                lease.tenant(),
                invocation);
          }

          @Override
          public void reconciliationRequired() {
            ledger.terminal(row, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
          }
        };
      }
    };
  }
}
