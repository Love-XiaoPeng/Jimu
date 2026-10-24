package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Binding;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Qualification;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationPlanCodec;

/** 复用既有 JSON 与冻结计划资源；不在 codec 中做准入或资格判定。 */
public final class RuntimeEvaluationPlanCodec implements EvaluationPlanCodec {
  public String hash(String json) {
    return Payloads.hash(json);
  }

  public Plan readPlan(String json) {
    return Payloads.read(json, Plan.class);
  }

  public Plan canonical(String id) {
    return EvaluationPlans.plan(id);
  }

  public String digest(Plan plan) {
    return EvaluationPlans.digest(plan);
  }

  public Qualification readQualification(String json) {
    return Payloads.read(json, Qualification.class);
  }

  public Binding readBinding(String json) {
    return Payloads.read(json, Binding.class);
  }
}
