package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.util.Map;

/** 原模型事务中的固定 Mapper 操作；历史 JSON 字节及 session 体积交给既有模块端口。 */
final class MapperModelCosts implements ModelCostRecords {
  private final JdbcLedger ledger;
  private final Lease lease;
  private final Map<String, Object> task;
  private final Map<String, Object> evaluation;
  private final String dispatch;
  private Map<String, Object> proof;

  MapperModelCosts(
      JdbcLedger ledger,
      Lease lease,
      Map<String, Object> task,
      Map<String, Object> evaluation,
      String dispatch) {
    this.ledger = ledger;
    this.lease = lease;
    this.task = task;
    this.evaluation = evaluation;
    this.dispatch = dispatch;
  }

  @Override
  public String state() {
    proof =
        JdbcLedger.first(
            ledger
                .mappers
                .dispatches()
                .selectSettlementProof(
                    ledger.queryContext(), lease.tenant(), dispatch, lease.taskId()));
    return ledger.s(proof, "state");
  }

  @Override
  public Proof proof() {
    var model = ledger.historicalSnapshot(task);
    return new Proof(
        model,
        ledger.s(task, "model_digest"),
        ledger.s(proof, "config_digest"),
        ModelProfiles.digest(model),
        ledger.s(proof, "price_version"),
        ledger.s(proof, "currency"),
        model.paid() ? ledger.n(proof, "reserved_cost") : 0);
  }

  @Override
  public void invalidateProof() {
    ledger
        .mappers
        .reservations()
        .holdInvalidModelProof(ledger.queryContext(), lease.tenant(), dispatch);
    if (task.get("evaluation_run_id") != null)
      ledger
          .mappers
          .evaluations()
          .stopRun(ledger.queryContext(), lease.tenant(), ledger.s(task, "evaluation_run_id"));
  }

  @Override
  public void accountUsage(ModelUsage usage) {
    if (JdbcSessionRecords.task(task)) {
      String old =
          ledger
              .mappers
              .dispatches()
              .selectUsageSnapshotScalar(ledger.queryContext(), lease.tenant(), dispatch);
      ledger.sessions.body(
          task,
          SessionContracts.bytes(Payloads.json(usage))
              - (old == null ? 0 : SessionContracts.bytes(old)));
    }
  }

  @Override
  public long reservedCost() {
    return ledger.n(
        JdbcLedger.first(
            ledger
                .mappers
                .dispatches()
                .selectReservedCost(ledger.queryContext(), lease.tenant(), dispatch)),
        "reserved_cost");
  }

  @Override
  public void adjustCost(long delta, boolean stopRun) {
    ledger
        .mappers
        .evaluations()
        .adjustSettledModelCost(
            ledger.queryContext(), delta, stopRun, lease.tenant(), ledger.s(evaluation, "id"));
    ledger.mappers.budgets().adjustModelCost(ledger.queryContext(), delta, lease.tenant());
    ledger
        .mappers
        .tasks()
        .adjustModelCost(ledger.queryContext(), delta, lease.tenant(), lease.taskId());
  }

  @Override
  public void settleActualCost(long actual) {
    ledger
        .mappers
        .reservations()
        .settleActualModelCost(ledger.queryContext(), actual, lease.tenant(), dispatch + ":cost");
  }

  @Override
  public void holdCostUnknown() {
    ledger
        .mappers
        .reservations()
        .holdCostUnknown(ledger.queryContext(), lease.tenant(), dispatch + ":cost");
  }

  @Override
  public void recordUsage(
      ModelUsage usage, String state, String code, Long actual, String quality) {
    ledger
        .mappers
        .dispatches()
        .recordActualUsage(
            ledger.queryContext(),
            state,
            code,
            usage.inputTokens(),
            usage.outputTokens(),
            usage.totalTokens(),
            Payloads.json(usage),
            actual,
            quality,
            lease.tenant(),
            dispatch);
  }

  @Override
  public void stopFurtherDispatch() {
    ledger
        .mappers
        .tasks()
        .stopFurtherModelDispatch(ledger.queryContext(), lease.tenant(), lease.taskId());
  }
}
