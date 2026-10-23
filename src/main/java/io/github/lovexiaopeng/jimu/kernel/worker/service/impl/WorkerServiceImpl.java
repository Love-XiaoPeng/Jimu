package io.github.lovexiaopeng.jimu.kernel.worker.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords;
import io.github.lovexiaopeng.jimu.kernel.worker.service.WorkerService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Writer receipts are authority, not an inference from an expired lease or missing result. */
public final class WorkerServiceImpl implements WorkerService {
  private final WorkerRecords records;
  private final LedgerUnitOfWork work;

  public WorkerServiceImpl(WorkerRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public Optional<Lease> claim(String worker) {
    records.maintainQuestions();
    for (String tenant : records.tenants()) {
      var candidate = records.candidate(tenant);
      if (candidate.isEmpty()) continue;
      Optional<Lease> lease =
          work.required(
              () -> {
                var locked = records.lockCandidate(candidate.get());
                if (locked.isEmpty()) return Optional.empty();
                var task = locked.get();
                if (!task.runUnchanged()) return Optional.empty();
                if (task.requiresStoppedWriters()
                    && "RUNNING".equals(task.state())
                    && !task.writersQuiescent()) return Optional.empty();
                boolean queued = "QUEUED".equals(task.state()),
                    resume = "RESUME_READY".equals(task.state());
                if (resume && (!task.writersQuiescent() || task.runReserved()))
                  return Optional.empty();
                if ((queued || resume) && task.runningLimitReached()) return Optional.empty();
                if (task.pendingEffect()) {
                  if (task.modelRejection() == null && task.runRejection() == null) {
                    try {
                      task.requireScope();
                    } catch (TaskFailure failure) {
                      if (!failure.code().equals("EVALUATION_SCOPE_LEGACY_UNBOUND")) throw failure;
                    }
                  }
                  task.markRunningInvocationsUnknown();
                  task.terminal("NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
                  return Optional.empty();
                }
                String rejection = task.modelRejection();
                if (rejection == null) rejection = task.runRejection();
                if (rejection != null) {
                  task.terminal("FAILED", rejection);
                  return Optional.empty();
                }
                try {
                  task.requireScope();
                } catch (TaskFailure failure) {
                  if (List.of("EVALUATION_SCOPE_MISMATCH", "BUSINESS_POLICY_CONFLICT")
                      .contains(failure.code())) throw failure;
                  task.terminal("FAILED", failure.code());
                  return Optional.empty();
                }
                if (!queued) task.expireCurrentAttempt();
                if (!task.withinDeadline()) {
                  task.terminal("FAILED", "DEADLINE_EXCEEDED");
                  return Optional.empty();
                }
                try {
                  task.checkAdmission(resume);
                } catch (TaskFailure failure) {
                  task.terminal("FAILED", failure.code());
                  return Optional.empty();
                }
                if (task.cancellationRequested()) {
                  task.terminal("CANCELLED", "CANCELLED");
                  return Optional.empty();
                }
                String attempt = UUID.randomUUID().toString();
                long fence = task.fence() + 1;
                if (queued) task.moveQueueToRunning();
                if (resume) task.resumeRunning();
                task.insertAttempt(worker, attempt, fence);
                task.assignLease(worker, attempt, fence);
                task.startedEvent(fence);
                return Optional.of(task.lease(worker, attempt, fence));
              });
      if (lease.isPresent()) return lease;
    }
    return Optional.empty();
  }

  @Override
  public void quiescent(Lease lease) {
    work.required(
        () -> {
          var task = records.lockTask(new WorkerRecords.TaskKey(lease.tenant(), lease.taskId()));
          if (task.nativeAttemptActive(lease)) return null;
          task.markAttemptQuiescent(lease);
          task.reconcileStoppedWriters();
          return null;
        });
  }

  @Override
  public void processStopped(String worker) {
    if (records.nativeWorkerActive(worker)) return;
    for (var candidate : records.workerTasks(worker)) {
      work.required(
          () -> {
            var task = records.lockTask(candidate);
            task.markWorkerAttemptsQuiescent(worker);
            if (task.ownedBy(worker)) task.expireLease();
            task.reconcileStoppedWriters();
            return null;
          });
    }
    // Last, outside the per-task transactions: interruption must leave reconciliation retryable.
    records.markWorkerStopped(worker);
  }
}
