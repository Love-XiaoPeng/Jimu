package io.github.lovexiaopeng.jimu.kernel.operator.port;

import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Scope;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;

/** 固定JSON/计划资源和模型结构适配；不读取账本、凭据或发起网络请求。 */
public interface ScopeBindingCodec {
  record Plan(String snapshot, String digest, String sourceSha, String materialsDigest) {}

  String canonical(Scope scope);

  String json(Object value);

  String hash(String value);

  boolean digest(String value);

  String approvedCandidate(String approval);

  Plan plan();

  void validateProfile(ModelSnapshot model, boolean localOnly);

  String profileDigest(ModelSnapshot model);

  boolean localEvaluation(ModelSnapshot model);
}
