package io.github.lovexiaopeng.jimu.kernel.worker.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.WorkerProcess;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
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
  public boolean renew(Lease lease) {
    return records.renewLease(lease) == 1;
  }

  @Override
  public void register(
      String worker,
      String host,
      long pid,
      long startTicks,
      String bootId,
      String observerPidNamespace) {
    records.insertProcess(worker, host, pid, startTicks, bootId, observerPidNamespace);
  }

  @Override
  public List<WorkerProcess> processes(String host) {
    return records.processes(host);
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
                    && TaskState.RUNNING.equals(task.state())
                    && !task.writersQuiescent()) return Optional.empty();
                boolean queued = TaskState.QUEUED.equals(task.state()),
                    resume = TaskState.RESUME_READY.equals(task.state());
                if (resume && (!task.writersQuiescent() || task.runReserved()))
                  return Optional.empty();
                if ((queued || resume) && task.runningLimitReached()) return Optional.empty();
                if (task.pendingEffect()) {
                  if (task.modelRejection() == null && task.runRejection() == null) {
                    try {
                      task.requireScope();
                    } catch (TaskFailure failure) {
                      if (!failure.code().equals(ModelErrorCode.EVALUATION_SCOPE_LEGACY_UNBOUND))
                        throw failure;
                    }
                  }
                  task.markRunningInvocationsUnknown();
                  task.terminal(
                      TaskState.NEEDS_RECONCILIATION, TaskErrorCode.EFFECT_OUTCOME_UNKNOWN);
                  return Optional.empty();
                }
                String rejection = task.modelRejection();
                if (rejection == null) rejection = task.runRejection();
                if (rejection != null) {
                  task.terminal(TaskState.FAILED, rejection);
                  return Optional.empty();
                }
                try {
                  task.requireScope();
                } catch (TaskFailure failure) {
                  if (List.of(
                          ModelErrorCode.EVALUATION_SCOPE_MISMATCH,
                          ModelErrorCode.BUSINESS_POLICY_CONFLICT)
                      .contains(failure.code())) throw failure;
                  task.terminal(TaskState.FAILED, failure.code());
                  return Optional.empty();
                }
                if (!queued) task.expireCurrentAttempt();
                if (!task.withinDeadline()) {
                  task.terminal(TaskState.FAILED, TaskErrorCode.DEADLINE_EXCEEDED);
                  return Optional.empty();
                }
                try {
                  task.checkAdmission(resume);
                } catch (TaskFailure failure) {
                  task.terminal(TaskState.FAILED, failure.code());
                  return Optional.empty();
                }
                if (task.cancellationRequested()) {
                  task.terminal(TaskState.CANCELLED, TaskErrorCode.CANCELLED);
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
