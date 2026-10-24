package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Binding;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Qualification;

/** 冻结快照的编码边界；摘要比较与业务拒绝由 Service 决定。 */
public interface EvaluationPlanCodec {
  String hash(String json);

  Plan readPlan(String json);

  Plan canonical(String id);

  String digest(Plan plan);

  Qualification readQualification(String json);

  Binding readBinding(String json);
}
