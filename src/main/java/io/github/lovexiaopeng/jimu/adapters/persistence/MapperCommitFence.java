package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.CommitFenceMapper;
import io.github.lovexiaopeng.jimu.kernel.execution.port.CommitFence;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** Construction reads neither lease nor session; original member order stays lazy. */
final class MapperCommitFence implements CommitFence {
  private final JdbcLedger ledger;
  private final Lease lease;

  MapperCommitFence(JdbcLedger ledger, Lease lease) {
    this.ledger = ledger;
    this.lease = lease;
  }

  public int update() {
    String tenant = lease.tenant();
    String task = lease.taskId();
    String attempt = lease.attemptId();
    String worker = lease.worker();
    long fence = lease.fence();
    return ledger
        .mappers
        .session()
        .getMapper(CommitFenceMapper.class)
        .commitFence(ledger.queryContext(), tenant, task, attempt, worker, fence);
  }
}
