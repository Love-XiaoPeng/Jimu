package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Selection;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.Map;

/** 将同事务已锁定的 run/tenant 与具名 Mapper 查询转换成事实；不作业务资格判定。 */
final class MapperEvaluationBindings implements EvaluationBindingRecords {
  private final JdbcLedger ledger;
  private final Map<String, Object> evaluation;

  MapperEvaluationBindings(JdbcLedger ledger, Map<String, Object> evaluation) {
    this.ledger = ledger;
    this.evaluation = evaluation;
  }

  public Run run() {
    if (evaluation == null) return null;
    return new Run(
        s("id"),
        s("plan_snapshot"),
        s("plan_digest"),
        n("policy_version"),
        n("max_requests"),
        n("max_cost_micros"),
        n("requests_used"),
        n("cost_held"),
        s("state"),
        s("phase_b_snapshot"),
        s("phase_b_digest"));
  }

  public Selection selection() {
    return ledger.models.evaluationSelection();
  }

  public void validateModel(Plan plan, ModelSnapshot model) {
    ledger.models.checkEvaluationModel(plan, model);
  }

  public boolean conflictingBinding() {
    return !ledger
        .mappers
        .tasks()
        .selectConflictingEvaluationBinding(
            ledger.queryContext(), s("tenant_id"), s("id"), s("plan_digest"), s("phase_b_digest"))
        .isEmpty();
  }

  public boolean hasTask() {
    return !ledger
        .mappers
        .tasks()
        .selectExistingEvaluationTask(ledger.queryContext(), s("tenant_id"), s("id"))
        .isEmpty();
  }

  public boolean hasUnknown() {
    return !ledger
        .mappers
        .reservations()
        .selectEvaluationUnknown(ledger.queryContext(), s("tenant_id"), s("id"))
        .isEmpty();
  }

  public List<Screening> screening() {
    return ledger
        .mappers
        .tasks()
        .selectEvaluationScreening(ledger.queryContext(), s("tenant_id"), s("id"))
        .stream()
        .map(
            row ->
                new Screening(
                    ledger.s(row, "evaluation_model"),
                    ledger.s(row, "evaluation_sample"),
                    ledger.s(row, "state")))
        .toList();
  }

  public boolean hasPhaseBTask() {
    return !ledger
        .mappers
        .tasks()
        .selectPhaseBEvaluationTask(ledger.queryContext(), s("tenant_id"), s("id"))
        .isEmpty();
  }

  public Room lockTenantRoom() {
    var tenant = ledger.bucket(s("tenant_id"));
    return new Room(
        ledger.n(tenant, "cost_limit") - ledger.n(tenant, "cost_held"),
        ledger.n(tenant, "token_limit") - ledger.n(tenant, "token_held"),
        ledger.n(tenant, "model_limit") - ledger.n(tenant, "model_used"));
  }

  private String s(String column) {
    return ledger.s(evaluation, column);
  }

  private long n(String column) {
    return ledger.n(evaluation, column);
  }
}
