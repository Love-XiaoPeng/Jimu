package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called only inside the caller's short run -> budget -> task transaction. */
final class JdbcExecutionScopes {
  private final JdbcTemplate db;
  private final ModelProfiles models;
  private final ExecutionScopes.Requirement bindingRequirement;

  JdbcExecutionScopes(JdbcTemplate db, ModelProfiles models) {
    this.db = db;
    this.models = models;
    this.bindingRequirement = null;
  }

  JdbcExecutionScopes(JdbcTemplate db, ExecutionScopes.Requirement requirement) {
    this.db = db;
    this.models = null;
    this.bindingRequirement = requirement;
  }

  private Map<String, Object> receipt(String tenant) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jimu_provisioning_receipt'",
            Integer.class)
        == 0) return null;
    var rows = db.queryForList("SELECT * FROM jimu_provisioning_receipt WHERE tenant_id=?", tenant);
    if (rows.size() > 1) throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<String> boundRuns(String tenant) {
    return db.queryForList(
        "SELECT run_id FROM evaluation_execution_scope WHERE tenant_id=?", String.class, tenant);
  }

  String targetRun(String tenant, String fallback) {
    try {
      var receipt = receipt(tenant);
      if (receipt != null) return (String) receipt.get("run_id");
      var req = models.scopeRequirement();
      if (req != null && tenant.equals(req.tenantId())) return req.runId();
      var bound = boundRuns(tenant);
      if (bound.isEmpty()) return fallback;
      // A known task keeps its original run; new admission must have unambiguous authority.
      if (fallback != null) {
        if (bound.contains(fallback)) return fallback;
        throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
      }
      if (bound.size() != 1) throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
      return bound.getFirst();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
    }
  }

  boolean needed(String tenant, Map<String, Object> task) {
    if (task != null && task.get("execution_scope_digest") != null
        || receipt(tenant) != null
        || !boundRuns(tenant).isEmpty()) return true;
    var req = models.scopeRequirement();
    if (req != null) return tenant.equals(req.tenantId());
    return models.evaluationSelection() != null && !models.localEvaluation(models.selected(tenant));
  }

  private void fail(String code) {
    throw new TaskFailure(code.equals("EVALUATION_SCOPE_UNAVAILABLE") ? 503 : 409, code);
  }

  ExecutionScopes.Requirement requirement(String tenant) {
    var r = receipt(tenant);
    var req = models == null ? bindingRequirement : models.scopeRequirement();
    if (r == null
        || req == null
        || req.version() != 1
        || !tenant.equals(req.tenantId())
        || req.issueId() == null
        || req.runId() == null
        || req.schema() == null
        || req.instanceUuid() == null
        || !ExecutionScopes.digest(req.approvalDigest())) {
      fail("EVALUATION_SCOPE_UNAVAILABLE");
    }
    String approval = (String) r.get("approval_snapshot");
    if (approval == null
        || r.get("record_version") != null && ((Number) r.get("record_version")).intValue() != 1
        || !Payloads.hash(approval).equals(r.get("approval_digest"))
        || !req.issueId().equals(r.get("issue_id"))
        || !req.runId().equals(r.get("run_id"))
        || !req.approvalDigest().equals(r.get("approval_digest"))
        || !req.instanceUuid().equals(r.get("instance_uuid"))
        || !req.instanceUuid().equals(db.queryForObject("SELECT @@server_uuid", String.class))
        || !req.schema().equals(db.queryForObject("SELECT DATABASE()", String.class)))
      fail("EVALUATION_SCOPE_NOT_ALLOWED");
    var body = Payloads.read(approval, com.fasterxml.jackson.databind.JsonNode.class);
    if (!"qwen3.7-plus-2026-05-26".equals(body.path("activeCandidateOnly").asText()))
      fail("EVALUATION_SCOPE_NOT_ALLOWED");
    return req;
  }

  void businessAnchor(
      io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies.Policy policy,
      Map<String, Object> run) {
    var req = requirement(policy.anchor().tenantId());
    var r = receipt(req.tenantId());
    if (!req.equals(policy.anchor())
        || run == null
        || !req.runId().equals(run.get("id"))
        || !req.runId().equals(models.evaluationRunId())
        || !java.util.Objects.equals(r.get("plan_digest"), run.get("plan_digest")))
      throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
    var plan = EvaluationPlans.plan("qwen-main-v1");
    if (!EvaluationPlans.digest(plan).equals(r.get("plan_digest"))
        || !plan.materialsDigest().equals(r.get("materials_digest")))
      throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
  }

  String require(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    boolean claimed = task != null && task.get("execution_scope_digest") != null;
    if (task != null && !claimed) fail("EVALUATION_SCOPE_LEGACY_UNBOUND");
    try {
      var req = requirement(tenant);
      var receipt = receipt(tenant);
      if (run == null
          || !req.runId().equals(run.get("id"))
          || !req.runId().equals(models.evaluationRunId())) fail("EVALUATION_SCOPE_NOT_ALLOWED");
      var rows =
          db.queryForList(
              "SELECT * FROM evaluation_execution_scope WHERE tenant_id=? AND run_id=?",
              tenant,
              req.runId());
      if (rows.isEmpty()) {
        fail(claimed ? "EVALUATION_SCOPE_MISMATCH" : "EVALUATION_SCOPE_UNAVAILABLE");
      }
      var row = rows.getFirst();
      String json = (String) row.get("scope_snapshot"), digest = (String) row.get("scope_digest");
      var scope = Payloads.read(json, ExecutionScopes.Scope.class);
      if (((Number) row.get("record_version")).intValue() != 1
          || !Payloads.hash(json).equals(digest)
          || !json.equals(ExecutionScopes.canonical(scope))) fail("EVALUATION_SCOPE_MISMATCH");
      if (!matches(req, scope)
          || !scope.planDigest().equals(run.get("plan_digest"))
          || !scope.planDigest().equals(receipt.get("plan_digest"))
          || !scope.materialsDigest().equals(receipt.get("materials_digest")))
        fail("EVALUATION_SCOPE_MISMATCH");
      var selection = models.evaluationSelection();
      if (selection == null
          || !scope.planDigest().equals(EvaluationPlans.digest(selection.plan()))
          || !scope.materialsDigest().equals(selection.plan().materialsDigest())
          || !scope.sourcePlanSha256().equals(selection.plan().sourcePlanSha256()))
        fail("EVALUATION_SCOPE_NOT_ALLOWED");
      var pin = scope.profilePins().get(model.profile());
      if (!scope.allowedModel().equals(model.model())
          || !scope.allowedRevision().equals(model.revision())
          || !model.paid()
          || pin == null
          || pin.configVersion() != model.configVersion()
          || pin.stream() != model.stream()
          || !pin.modelSnapshotDigest().equals(ModelProfiles.digest(model))
          || !model.profile().equals(scope.sampleProfiles().get(selection.sample()))
          || scope.localOnly() != models.localEvaluation(model))
        fail("EVALUATION_SCOPE_NOT_ALLOWED");
      if (claimed
          && (!digest.equals(task.get("execution_scope_digest"))
              || !req.runId().equals(task.get("evaluation_run_id"))))
        fail("EVALUATION_SCOPE_MISMATCH");
      return digest;
    } catch (IllegalArgumentException invalid) {
      fail("EVALUATION_SCOPE_MISMATCH");
    } catch (TaskFailure failure) {
      if (claimed && !failure.code().equals("EVALUATION_SCOPE_MISMATCH"))
        fail("EVALUATION_SCOPE_MISMATCH");
      throw failure;
    }
    throw new IllegalStateException("Unreachable scope validation");
  }

  static boolean matches(ExecutionScopes.Requirement r, ExecutionScopes.Scope s) {
    return r.version() == s.version()
        && r.issueId().equals(s.issueId())
        && r.instanceUuid().equals(s.instanceUuid())
        && r.schema().equals(s.schema())
        && r.tenantId().equals(s.tenantId())
        && r.runId().equals(s.runId())
        && r.approvalDigest().equals(s.approvalDigest());
  }

  void verifyBinding(
      ExecutionScopes.Scope scope,
      Map<String, ModelSnapshot> profiles,
      ExecutionScopes.AccountEvidence evidence,
      Map<String, Object> run) {
    var req = requirement(scope.tenantId());
    var r = receipt(scope.tenantId());
    if (!matches(req, scope)
        || !scope.planDigest().equals(run.get("plan_digest"))
        || !scope.planDigest().equals(r.get("plan_digest"))
        || !scope.materialsDigest().equals(r.get("materials_digest"))
        || !scope.profilePins().keySet().equals(profiles.keySet())
        || evidence == null
        || !ExecutionScopes.digest(evidence.evidenceDigest())
        || !scope.accountEvidenceDigest().equals(Payloads.hash(Payloads.json(evidence)))
        || evidence.region() == null
        || evidence.region().isBlank()
        || evidence.billingScope() == null
        || evidence.billingScope().isBlank()
        || !evidence.exactModelAuthorized()
        || !evidence.egressVerified()) fail("EVALUATION_SCOPE_NOT_ALLOWED");
    var plan = EvaluationPlans.plan("qwen-main-v1");
    if (!scope.planDigest().equals(EvaluationPlans.digest(plan))
        || !scope.sourcePlanSha256().equals(plan.sourcePlanSha256())
        || !scope.materialsDigest().equals(plan.materialsDigest())
        || !Payloads.json(plan).equals(run.get("plan_snapshot")))
      fail("EVALUATION_SCOPE_NOT_ALLOWED");
    for (var entry : profiles.entrySet()) {
      var p = entry.getValue();
      if (p == null) fail("EVALUATION_SCOPE_NOT_ALLOWED");
      var pin = scope.profilePins().get(entry.getKey());
      ModelProfiles.validateStructure(plan, p, scope.localOnly());
      EvaluationPlans.validateModel(plan, p, scope.localOnly());
      if (!scope.allowedModel().equals(p.model())
          || !p.model().equals(p.revision())
          || !entry.getKey().equals(p.profile())
          || !pin.modelSnapshotDigest().equals(ModelProfiles.digest(p))
          || pin.configVersion() != p.configVersion()
          || pin.stream() != p.stream()
          || !URI.create(p.baseUrl()).getHost().equals(evidence.apiHost())
          || scope.localOnly() != (models != null && models.localEvaluation(p)))
        fail("EVALUATION_SCOPE_NOT_ALLOWED");
    }
    if (!scope.localOnly()
        && (!"cn-beijing".equals(evidence.region())
            || !"modelstudio-payg-cny".equals(evidence.billingScope())))
      fail("EVALUATION_SCOPE_NOT_ALLOWED");
  }
}
