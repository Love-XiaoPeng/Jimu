package io.github.lovexiaopeng.jimu.kernel.model.service;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Binding;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Qualification;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;

/** task 受理与模型执行共用的绑定/资格策略，加入调用者已开启的账本事务。 */
public interface EvaluationBindingService {
  record StoredBinding(String snapshot, String digest, String phase, String sample, String model) {}

  Plan plan(EvaluationBindingRecords records);

  Binding bind(EvaluationBindingRecords records, ModelSnapshot model);

  void check(EvaluationBindingRecords records, StoredBinding stored, ModelSnapshot model);

  Qualification qualify(Plan plan, String snapshot, String digest);
}
