package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords.PlanAllowance;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 已绑定评估计划下的阶段/模型/样本配额；锁内事实由 Mapper 端口提供。 */
final class ModelEvaluationAdmission {
  private ModelEvaluationAdmission() {}

  static void check(PlanAllowance plan, long cost) {
    if (plan == null) return;
    long phaseCalls = 0,
        phaseCost = 0,
        modelCalls = 0,
        modelCost = 0,
        phaseModelCalls = 0,
        phaseModelCost = 0,
        sampleCalls = 0;
    for (var row : plan.usage()) {
      boolean sameModel = plan.model().equals(row.model());
      if (sameModel) {
        modelCalls += row.calls();
        modelCost += row.cost();
      }
      if (plan.phase().equals(row.phase())) {
        phaseCalls += row.calls();
        phaseCost += row.cost();
        if (sameModel) {
          phaseModelCalls += row.calls();
          phaseModelCost += row.cost();
        }
      }
      if (sameModel && plan.sample().equals(row.sample())) sampleCalls += row.calls();
    }
    int modelPhaseLimit = plan.phase().equals("A") ? 13 : 12;
    if (phaseCalls >= (plan.phase().equals("A") ? 13 * plan.candidates() : 24)
        || phaseCost + cost > plan.phaseCostLimit()
        || modelCalls >= 25
        || modelCost + cost > 25 * cost
        || phaseModelCalls >= modelPhaseLimit
        || phaseModelCost + cost > modelPhaseLimit * cost
        || sampleCalls >= (plan.sample().equals("W1") ? 7 : 6))
      throw new TaskFailure(429, ModelErrorCode.MODEL_BUDGET_EXCEEDED);
  }
}
