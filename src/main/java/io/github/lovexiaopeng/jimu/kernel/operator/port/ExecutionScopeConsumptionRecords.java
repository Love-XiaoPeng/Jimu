package io.github.lovexiaopeng.jimu.kernel.operator.port;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lazy original-reference views, individual I/O and frozen codec/config adaptations. */
public interface ExecutionScopeConsumptionRecords {
  interface Requirement {
    int version();

    String issueId();

    String instanceUuid();

    String schema();

    String tenantId();

    String runId();

    String approvalDigest();

    boolean same(Requirement other);
  }

  interface Pin {
    int configVersion();

    boolean stream();

    String modelSnapshotDigest();
  }

  interface Scope {
    int version();

    String issueId();

    String instanceUuid();

    String schema();

    String tenantId();

    String runId();

    String approvalDigest();

    String planDigest();

    String materialsDigest();

    String sourcePlanSha256();

    String allowedModel();

    String allowedRevision();

    Pin pin(String profile);

    Map<String, String> sampleProfiles();

    boolean localOnly();
  }

  interface Policy {
    int version();

    Requirement anchor();
  }

  /** Only the adapter's exact Spring DataAccess catch produces this boundary marker. */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  <T> T dataAccess(Supplier<T> work);

  Integer receiptTable();

  List<Map<String, Object>> receipts(String tenant);

  List<String> boundRuns(String tenant);

  List<Map<String, Object>> scopes(String tenant, String run);

  String serverUuid();

  String schema();

  Requirement scopeRequirement();

  EvaluationPlan.Selection evaluationSelection();

  ModelSnapshot selected(String tenant);

  boolean localEvaluation(ModelSnapshot model);

  String evaluationRunId();

  String hash(String raw);

  boolean digest(String raw);

  String activeCandidate(String raw);

  Scope readScope(String raw);

  String canonical(Scope scope);

  String modelDigest(ModelSnapshot model);

  EvaluationPlan.Plan plan(String id);

  String planDigest(EvaluationPlan.Plan plan);

  Policy businessPolicy();

  Policy readPolicy(String raw);

  String canonical(Policy policy);

  void validate(Policy policy);

  void retainedBusiness(Policy policy);

  void profile(Policy policy, ModelSnapshot model, boolean local);

  ModelSnapshot historicalSnapshot(Map<String, Object> task);

  void available(ModelSnapshot model);

  void checkEvaluation(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model);

  EvaluationPlan.Plan boundPlan(Map<String, Object> run);

  void stopRun(String tenant, String run);

  void event(Map<String, Object> task, String key, String type, String payload);
}
