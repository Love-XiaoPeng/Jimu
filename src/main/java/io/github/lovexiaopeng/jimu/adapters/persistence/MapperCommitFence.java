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

  private io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork owner() {
    var query = ledger.queryContext();
    return query != null && query.lineage()
        ? io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query)
        : null;
  }

  private java.time.LocalDateTime retain(
      io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork owner) {
    var proof = java.util.Objects.requireNonNull(owner.proof(), "Session proof required");
    var retain = proof.scope().retain();
    for (var source : proof.sourceScopes().values())
      if (source.retain().isBefore(retain)) retain = source.retain();
    return retain;
  }

  private static void accepted(int rows) {
    if (rows != 1)
      throw new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
          409, io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode.FENCE_REJECTED);
  }

  void admission(java.util.Map<String, Object> task, boolean fresh) {
    var owner = owner();
    if (owner == null) return;
    String tenant = JdbcLedger.s(task, "tenant_id"), id = JdbcLedger.s(task, "id");
    String actor = JdbcLedger.s(task, "owner_actor_id"),
        digest = JdbcLedger.s(task, "request_digest"),
        state = JdbcLedger.s(task, "state");
    owner.fence(
        "admission:" + tenant + ":" + id,
        () ->
            accepted(
                ledger
                    .mappers
                    .session()
                    .getMapper(CommitFenceMapper.class)
                    .admissionFence(
                        ledger.queryContext(),
                        tenant,
                        id,
                        actor,
                        digest,
                        state,
                        fresh,
                        retain(owner))));
  }

  void terminal(String state) {
    var owner = owner();
    if (owner == null) return;
    owner.fence(
        lease.tenant()
            + ":"
            + lease.taskId()
            + ":"
            + lease.attemptId()
            + ":"
            + lease.worker()
            + ":"
            + lease.fence(),
        () ->
            accepted(
                ledger
                    .mappers
                    .session()
                    .getMapper(CommitFenceMapper.class)
                    .terminalRetainedFence(
                        ledger.queryContext(),
                        lease.tenant(),
                        lease.taskId(),
                        lease.attemptId(),
                        lease.worker(),
                        lease.fence(),
                        state,
                        state.equals("SUCCEEDED") ? "SUCCEEDED" : "STOPPED",
                        retain(owner))));
  }

  void settlement(String dispatch) {
    var owner = owner();
    if (owner == null) return;
    owner.settlement();
    owner.fence(
        lease.tenant()
            + ":"
            + lease.taskId()
            + ":"
            + lease.attemptId()
            + ":"
            + lease.worker()
            + ":"
            + lease.fence(),
        () ->
            accepted(
                ledger
                    .mappers
                    .session()
                    .getMapper(CommitFenceMapper.class)
                    .settlementFence(
                        ledger.queryContext(),
                        lease.tenant(),
                        lease.taskId(),
                        lease.attemptId(),
                        lease.worker(),
                        lease.fence(),
                        dispatch)));
  }

  public int update() {
    String tenant = lease.tenant();
    String task = lease.taskId();
    String attempt = lease.attemptId();
    String worker = lease.worker();
    long fence = lease.fence();
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null && ledger.queryContext().lineage())
      owner.fence(
          tenant + ":" + task + ":" + attempt + ":" + worker + ":" + fence,
          () -> {
            var proof = owner.proof();
            if (proof != null) {
              var retain = proof.scope().retain();
              for (var source : proof.sourceScopes().values())
                if (source.retain().isBefore(retain)) retain = source.retain();
              if (ledger
                      .mappers
                      .session()
                      .getMapper(CommitFenceMapper.class)
                      .commitRetainedFence(
                          ledger.queryContext(), tenant, task, attempt, worker, fence, retain)
                  != 1) {
                owner.checkLiveDeadline();
                throw new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
                    409,
                    io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode.FENCE_REJECTED);
              }
            }
          });
    return ledger
        .mappers
        .session()
        .getMapper(CommitFenceMapper.class)
        .commitFence(ledger.queryContext(), tenant, task, attempt, worker, fence);
  }
}
