package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.WorkerProcess;
import io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      public int renewLease(Lease lease) {
        return db.update(
            "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE tenant_id=? AND id=? AND current_attempt_id=? AND worker_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6) AND state IN ('RUNNING','CANCEL_REQUESTED')",
            lease.tenant(),
            lease.taskId(),
            lease.attemptId(),
            lease.worker(),
            lease.fence());
      }

      public void insertProcess(
          String worker,
          String host,
          long pid,
          long startTicks,
          String bootId,
          String observerPidNamespace) {
        db.update(
            "INSERT INTO worker_process(id,host_id,pid,start_ticks,boot_id,observer_pid_namespace) VALUES(?,?,?,?,?,?)",
            worker,
            host,
            pid,
            startTicks,
            bootId,
            observerPidNamespace);
      }

      public List<WorkerProcess> processes(String host) {
        return ledger
            .rows(
                "SELECT w.*,EXISTS(SELECT 1 FROM task_attempt a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id WHERE a.worker_id=w.id AND a.quiescent=FALSE AND (t.cancel_requested_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND OR (t.state='NEEDS_RECONCILIATION' AND t.updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND AND EXISTS(SELECT 1 FROM native_search_run n WHERE n.tenant_id=t.tenant_id AND n.task_id=t.id AND n.worker_id=w.id AND n.phase<>'STOPPED')))) AS overdue FROM worker_process w WHERE host_id=? AND stopped=FALSE ORDER BY id LIMIT 64",
                host)
            .stream()
            .map(
                w ->
                    new WorkerProcess(
                        ledger.s(w, "id"),
                        ledger.n(w, "pid"),
                        ledger.n(w, "start_ticks"),
                        ledger.n(w, "overdue") == 1,
                        ledger.s(w, "boot_id"),
                        ledger.s(w, "observer_pid_namespace")))
            .toList();
      }

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
      public Optional<Candidate> candidate(String tenant) {
        return ledger.claimCandidate(tenant);
      }

      @Override
      public Optional<Claim> lockCandidate(Candidate candidate) {
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
