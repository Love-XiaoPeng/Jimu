package io.github.lovexiaopeng.jimu.kernel.operator.service;

import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.AccountEvidence;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Scope;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;

/** 受信运维绑定：仅证明并绑定既有账本，不创建run或恢复任务。 */
public interface ExecutionScopeService {
  record Input(
      Scope scope,
      Map<String, ModelSnapshot> profiles,
      AccountEvidence accountEvidence,
      String boundBy) {}

  Map<String, Object> bindOnce(Input input);
}
