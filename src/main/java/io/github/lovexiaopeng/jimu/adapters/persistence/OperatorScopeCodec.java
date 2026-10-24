package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Scope;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingCodec;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;

/** 复用既有冻结计划/模型验证和JSON编码，不访问数据库或凭据。 */
final class OperatorScopeCodec implements ScopeBindingCodec {
  private final ModelProfiles models;

  OperatorScopeCodec(ModelProfiles models) {
    this.models = models;
  }

  public String canonical(Scope s) {
    return ExecutionScopes.canonical(Payloads.read(Payloads.json(s), ExecutionScopes.Scope.class));
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public boolean digest(String value) {
    return ExecutionScopes.digest(value);
  }

  public String approvedCandidate(String value) {
    return Payloads.read(value, JsonNode.class).path("activeCandidateOnly").asText();
  }

  public Plan plan() {
    var p = EvaluationPlans.plan("qwen-main-v1");
    return new Plan(
        Payloads.json(p), EvaluationPlans.digest(p), p.sourcePlanSha256(), p.materialsDigest());
  }

  public void validateProfile(ModelSnapshot model, boolean localOnly) {
    var p = EvaluationPlans.plan("qwen-main-v1");
    ModelProfiles.validateStructure(p, model, localOnly);
    EvaluationPlans.validateModel(p, model, localOnly);
  }

  public String profileDigest(ModelSnapshot model) {
    return ModelProfiles.digest(model);
  }

  public boolean localEvaluation(ModelSnapshot model) {
    return models != null && models.localEvaluation(model);
  }
}
