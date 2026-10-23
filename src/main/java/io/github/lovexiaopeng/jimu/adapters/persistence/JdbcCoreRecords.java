package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
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

  ModelDispatchRecords model() {
    return (lease, dispatch) -> {
      var evaluation = ledger.evaluation(lease, false);
      var task = ledger.fenced(lease, false);
      var model = ledger.settlementModel(lease, task, dispatch);
      return new ModelDispatchRecords.Dispatch() {
        @Override
        public boolean paid() {
          return model.paid();
        }

        @Override
        public boolean session() {
          return JdbcSessionRecords.task(task);
        }

        @Override
        public void settleCost(ModelUsage usage) {
          ledger.settleCost(lease, task, evaluation, dispatch, usage);
        }

        @Override
        public void markFailed(String code) {
          db.update(
              "UPDATE model_dispatch SET state='FAILED',code=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state IN ('DISPATCHED','RESPONDED')",
              code,
              lease.tenant(),
              dispatch,
              lease.taskId());
        }

        @Override
        public long reservedTokens() {
          return ((Number)
                  ledger
                      .one(
                          "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                          lease.tenant(),
                          dispatch)
                      .get("amount"))
              .longValue();
        }

        @Override
        public void settleTokens(long actual, long released) {
          db.update(
              "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
              released,
              lease.tenant());
          db.update(
              "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
              released,
              lease.tenant(),
              lease.taskId());
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
              actual,
              lease.tenant(),
              dispatch);
        }

        @Override
        public void holdUnknown() {
          db.update(
              "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id=? AND dispatch_id=? AND category='MODEL'",
              lease.tenant(),
              dispatch);
        }

        @Override
        public void accountSession() {
          ledger.accountSession(task);
        }
      };
    };
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
