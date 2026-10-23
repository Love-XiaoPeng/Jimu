package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.WorkerProcess;
import io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** WorkerService 的真实 Mapper 记录端口；宿主元数据跨租户，任务变更总是重锁完整租户键。 */
final class MapperWorkerRecords implements WorkerRecords {
  private final JdbcLedger ledger;

  MapperWorkerRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  private static String s(Map<String, Object> row, String name) {
    return (String) row.get(name);
  }

  public int renewLease(Lease lease) {
    return ledger
        .mappers
        .tasks()
        .renewOwnedLease(
            ledger.queryContext(),
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
    var row =
        new io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity.WorkerProcessEntity();
    row.setId(worker);
    row.setHostId(host);
    row.setPid(pid);
    row.setStartTicks(startTicks);
    row.setBootId(bootId);
    row.setObserverPidNamespace(observerPidNamespace);
    ledger.mappers.workers().insert(row);
  }

  public List<WorkerProcess> processes(String host) {
    return ledger.mappers.workers().selectObservedProcesses(ledger.queryContext(), host).stream()
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
    return ledger.mappers.budgets().selectClaimTenants(ledger.queryContext()).stream()
        .map(row -> s(row, "tenant_id"))
        .toList();
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
    return !ledger
        .mappers
        .workers()
        .selectActiveNativeWorker(ledger.queryContext(), worker)
        .isEmpty();
  }

  @Override
  public List<TaskKey> workerTasks(String worker) {
    return ledger.mappers.attempts().selectWorkerTasks(ledger.queryContext(), worker).stream()
        .map(row -> new TaskKey(s(row, "tenant_id"), s(row, "id")))
        .toList();
  }

  @Override
  public void markWorkerStopped(String worker) {
    ledger
        .mappers
        .workers()
        .update(
            null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                    io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity
                        .WorkerProcessEntity>()
                .set(
                    io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity
                            .WorkerProcessEntity
                        ::getStopped,
                    true)
                .eq(
                    io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity
                            .WorkerProcessEntity
                        ::getId,
                    worker));
  }

  @Override
  public Task lockTask(TaskKey key) {
    ledger.bucket(key.tenant());
    var row = ledger.locked(key.tenant(), key.taskId());
    return new Task() {
      @Override
      public boolean nativeAttemptActive(Lease lease) {
        return !ledger
            .mappers
            .attempts()
            .selectActiveNativeAttempt(ledger.queryContext(), lease.tenant(), lease.attemptId())
            .isEmpty();
      }

      @Override
      public void markAttemptQuiescent(Lease lease) {
        ledger
            .mappers
            .attempts()
            .markOwnedAttemptQuiescent(
                ledger.queryContext(),
                lease.tenant(),
                lease.attemptId(),
                lease.worker(),
                lease.fence());
      }

      @Override
      public void markWorkerAttemptsQuiescent(String worker) {
        ledger
            .mappers
            .attempts()
            .markWorkerTaskAttemptsQuiescent(
                ledger.queryContext(), key.tenant(), key.taskId(), worker);
      }

      @Override
      public boolean ownedBy(String worker) {
        return worker.equals(s(row, "worker_id"));
      }

      @Override
      public void expireLease() {
        ledger
            .mappers
            .tasks()
            .expireStoppedWorkerLease(ledger.queryContext(), key.tenant(), key.taskId());
      }

      @Override
      public void reconcileStoppedWriters() {
        ledger.settleStoppedWriters(row);
      }
    };
  }
}
