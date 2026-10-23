package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.util.List;
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

  WorkerRecords worker() {
    return new WorkerRecords() {
      @Override
      public void maintainQuestions() {
        ledger.maintainQuestions();
      }

      @Override
      public List<String> tenants() {
        return db.queryForList(
            "SELECT tenant_id FROM tenant_budget ORDER BY tenant_id", String.class);
      }

      @Override
      public java.util.Optional<Candidate> candidate(String tenant) {
        return ledger.claimCandidate(tenant);
      }

      @Override
      public java.util.Optional<Claim> lockCandidate(Candidate candidate) {
        return ledger.lockClaim(candidate);
      }

      @Override
      public boolean nativeWorkerActive(String worker) {
        return !db.queryForList(
                "SELECT root_invocation FROM native_search_run WHERE worker_id=? AND phase<>'STOPPED' LIMIT 1",
                worker)
            .isEmpty();
      }

      @Override
      public List<TaskKey> workerTasks(String worker) {
        return db
            .queryForList(
                "SELECT DISTINCT tenant_id,task_id AS id FROM task_attempt WHERE worker_id=?",
                worker)
            .stream()
            .map(row -> new TaskKey(s(row, "tenant_id"), s(row, "id")))
            .toList();
      }

      @Override
      public void markWorkerStopped(String worker) {
        db.update("UPDATE worker_process SET stopped=TRUE WHERE id=?", worker);
      }

      @Override
      public Task lockTask(TaskKey key) {
        ledger.bucket(key.tenant());
        var row = ledger.locked(key.tenant(), key.taskId());
        return new Task() {
          @Override
          public boolean nativeAttemptActive(Lease lease) {
            return !db.queryForList(
                    "SELECT root_invocation FROM native_search_run WHERE tenant_id=? AND attempt_id=? AND phase<>'STOPPED' LIMIT 1",
                    lease.tenant(),
                    lease.attemptId())
                .isEmpty();
          }

          @Override
          public void markAttemptQuiescent(Lease lease) {
            db.update(
                "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND id=? AND worker_id=? AND fence=?",
                lease.tenant(),
                lease.attemptId(),
                lease.worker(),
                lease.fence());
          }

          @Override
          public void markWorkerAttemptsQuiescent(String worker) {
            db.update(
                "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND task_id=? AND worker_id=?",
                key.tenant(),
                key.taskId(),
                worker);
          }

          @Override
          public boolean ownedBy(String worker) {
            return worker.equals(s(row, "worker_id"));
          }

          @Override
          public void expireLease() {
            db.update(
                "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE tenant_id=? AND id=?",
                key.tenant(),
                key.taskId());
          }

          @Override
          public void reconcileStoppedWriters() {
            ledger.settleStoppedWriters(row);
          }
        };
      }
    };
  }

  ExecutionRecords execution() {
    return lease -> {
      var row = ledger.fenced(lease, false);
      return new ExecutionRecords.Task() {
        @Override
        public boolean hasEvaluationRun() {
          return row.get("evaluation_run_id") != null;
        }

        @Override
        public boolean cancellationRequested() {
          return row.get("cancel_requested_at") != null;
        }

        @Override
        public boolean pendingEffect() {
          return !db.queryForList(
                  "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                  lease.tenant(),
                  lease.taskId())
              .isEmpty();
        }

        @Override
        public void stopEvaluationRun() {
          db.update(
              "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
              lease.tenant(),
              s(row, "evaluation_run_id"));
        }

        @Override
        public void terminal(String state, String code) {
          ledger.terminal(row, state, code);
        }
      };
    };
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
