package io.github.lovexiaopeng.jimu.kernel.operator.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords.Policy;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords.Requirement;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeConsumptionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.Map;

/** Complete original qualification and consumption; caller retains all transaction ownership. */
public final class ExecutionScopeConsumptionServiceImpl
    implements ExecutionScopeConsumptionService {
  private final ExecutionScopeConsumptionRecords records;

  public ExecutionScopeConsumptionServiceImpl(ExecutionScopeConsumptionRecords records) {
    this.records = records;
  }

  private Map<String, Object> receipt(String tenant) {
    if (records.receiptTable() == 0) return null;
    var rows = records.receipts(tenant);
    if (rows.size() > 1) throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<String> boundRuns(String tenant) {
    return records.boundRuns(tenant);
  }

  public String targetRun(String tenant, String fallback) {
    try {
      return records.dataAccess(
          () -> {
            var receipt = receipt(tenant);
            if (receipt != null) return (String) receipt.get("run_id");
            var req = records.scopeRequirement();
            if (req != null && tenant.equals(req.tenantId())) return req.runId();
            var bound = boundRuns(tenant);
            if (bound.isEmpty()) return fallback;
            // A known task keeps its original run; new admission must have unambiguous authority.
            if (fallback != null) {
              if (bound.contains(fallback)) return fallback;
              throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
            }
            if (bound.size() != 1)
              throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
            return bound.getFirst();
          });
    } catch (ExecutionScopeConsumptionRecords.Unavailable unavailable) {
      throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
    }
  }

  public boolean needed(String tenant, Map<String, Object> task) {
    if (task != null && task.get("execution_scope_digest") != null
        || receipt(tenant) != null
        || !boundRuns(tenant).isEmpty()) return true;
    var req = records.scopeRequirement();
    if (req != null) return tenant.equals(req.tenantId());
    return records.evaluationSelection() != null
        && !records.localEvaluation(records.selected(tenant));
  }

  private void fail(String code) {
    throw new TaskFailure(
        code.equals(ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE) ? 503 : 409, code);
  }

  public Requirement requirement(String tenant) {
    var r = receipt(tenant);
    var req = records.scopeRequirement();
    if (r == null
        || req == null
        || req.version() != 1
        || !tenant.equals(req.tenantId())
        || req.issueId() == null
        || req.runId() == null
        || req.schema() == null
        || req.instanceUuid() == null
        || !records.digest(req.approvalDigest())) {
      fail(ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
    }
    String approval = (String) r.get("approval_snapshot");
    if (approval == null
        || r.get("record_version") != null && ((Number) r.get("record_version")).intValue() != 1
        || !records.hash(approval).equals(r.get("approval_digest"))
        || !req.issueId().equals(r.get("issue_id"))
        || !req.runId().equals(r.get("run_id"))
        || !req.approvalDigest().equals(r.get("approval_digest"))
        || !req.instanceUuid().equals(r.get("instance_uuid"))
        || !req.instanceUuid().equals(records.serverUuid())
        || !req.schema().equals(records.schema()))
      fail(ModelErrorCode.EVALUATION_SCOPE_NOT_ALLOWED);
    String candidate = records.activeCandidate(approval);
    if (!"qwen3.7-plus-2026-05-26".equals(candidate))
      fail(ModelErrorCode.EVALUATION_SCOPE_NOT_ALLOWED);
    return req;
  }

  public void businessAnchor(Policy policy, Map<String, Object> run) {
    var req = requirement(policy.anchor().tenantId());
    var r = receipt(req.tenantId());
    if (!req.same(policy.anchor())
        || run == null
        || !req.runId().equals(run.get("id"))
        || !req.runId().equals(records.evaluationRunId())
        || !java.util.Objects.equals(r.get("plan_digest"), run.get("plan_digest")))
      throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
    var plan = records.plan("qwen-main-v1");
    if (!records.planDigest(plan).equals(r.get("plan_digest"))
        || !plan.materialsDigest().equals(r.get("materials_digest")))
      throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
  }

  public String require(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    boolean claimed = task != null && task.get("execution_scope_digest") != null;
    if (task != null && !claimed) fail(ModelErrorCode.EVALUATION_SCOPE_LEGACY_UNBOUND);
    try {
      var req = requirement(tenant);
      var receipt = receipt(tenant);
      if (run == null
          || !req.runId().equals(run.get("id"))
          || !req.runId().equals(records.evaluationRunId()))
        fail(ModelErrorCode.EVALUATION_SCOPE_NOT_ALLOWED);
      var rows = records.scopes(tenant, req.runId());
      if (rows.isEmpty()) {
        fail(
            claimed
                ? ModelErrorCode.EVALUATION_SCOPE_MISMATCH
                : ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
      }
      var row = rows.getFirst();
      String json = (String) row.get("scope_snapshot"), digest = (String) row.get("scope_digest");
      var scope = records.readScope(json);
      if (((Number) row.get("record_version")).intValue() != 1
          || !records.hash(json).equals(digest)
          || !json.equals(records.canonical(scope))) fail(ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
      if (!matches(req, scope)
          || !scope.planDigest().equals(run.get("plan_digest"))
          || !scope.planDigest().equals(receipt.get("plan_digest"))
          || !scope.materialsDigest().equals(receipt.get("materials_digest")))
        fail(ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
      var selection = records.evaluationSelection();
      if (selection == null
          || !scope.planDigest().equals(records.planDigest(selection.plan()))
          || !scope.materialsDigest().equals(selection.plan().materialsDigest())
          || !scope.sourcePlanSha256().equals(selection.plan().sourcePlanSha256()))
        fail(ModelErrorCode.EVALUATION_SCOPE_NOT_ALLOWED);
      var pin = scope.pin(model.profile());
      if (!scope.allowedModel().equals(model.model())
          || !scope.allowedRevision().equals(model.revision())
          || !model.paid()
          || pin == null
          || pin.configVersion() != model.configVersion()
          || pin.stream() != model.stream()
          || !pin.modelSnapshotDigest().equals(records.modelDigest(model))
          || !model.profile().equals(scope.sampleProfiles().get(selection.sample()))
          || scope.localOnly() != records.localEvaluation(model))
        fail(ModelErrorCode.EVALUATION_SCOPE_NOT_ALLOWED);
      if (claimed
          && (!digest.equals(task.get("execution_scope_digest"))
              || !req.runId().equals(task.get("evaluation_run_id"))))
        fail(ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
      return digest;
    } catch (IllegalArgumentException invalid) {
      fail(ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
    } catch (TaskFailure failure) {
      if (claimed && !failure.code().equals(ModelErrorCode.EVALUATION_SCOPE_MISMATCH))
        fail(ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
      throw failure;
    }
    throw new IllegalStateException("Unreachable scope validation");
  }

  public static boolean matches(Requirement r, Scope s) {
    return r.version() == s.version()
        && r.issueId().equals(s.issueId())
        && r.instanceUuid().equals(s.instanceUuid())
        && r.schema().equals(s.schema())
        && r.tenantId().equals(s.tenantId())
        && r.runId().equals(s.runId())
        && r.approvalDigest().equals(s.approvalDigest());
  }

  public String requireScope(
      String tenant,
      Map<String, Object> evaluation,
      Map<String, Object> task,
      ModelSnapshot initialModel) {
    try {
      return records.dataAccess(
          () -> {
            ModelSnapshot model = initialModel;
            if (task == null && records.businessPolicy() != null
                || task != null && businessTask(task)) {
              requireBusiness(tenant, evaluation, task, model);
              return null;
            }
            if (!needed(tenant, task)) return null;
            if (task != null) {
              if (task.get("execution_scope_digest") == null)
                throw new TaskFailure(409, ModelErrorCode.EVALUATION_SCOPE_LEGACY_UNBOUND);
              try {
                model = records.historicalSnapshot(task);
              } catch (TaskFailure | IllegalArgumentException corrupt) {
                throw new TaskFailure(409, ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
              }
            }
            String digest = require(tenant, evaluation, task, model);
            if (task != null) {
              try {
                records.available(model);
                records.checkEvaluation(task, evaluation, model);
              } catch (TaskFailure | IllegalArgumentException corrupt) {
                throw new TaskFailure(409, ModelErrorCode.EVALUATION_SCOPE_MISMATCH);
              }
            }
            return digest;
          });
    } catch (ExecutionScopeConsumptionRecords.Unavailable unavailable) {
      throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
    } catch (TaskFailure failure) {
      if (List.of(ModelErrorCode.EVALUATION_SCOPE_MISMATCH, ModelErrorCode.BUSINESS_POLICY_CONFLICT)
              .contains(failure.code())
          && evaluation != null) {
        records.stopRun(tenant, s(evaluation, "id"));
        if (task != null)
          records.event(
              task,
              "scope-rejected:" + n(task, "fence"),
              "evaluation.scope_rejected",
              failure.code());
      }
      throw failure;
    }
  }

  public boolean businessTask(Map<String, Object> task) {
    return n(task, "record_version") == 2
        || task.get("business_policy_snapshot") != null
        || task.get("business_policy_digest") != null;
  }

  public void requireBusiness(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    Policy policy;
    try {
      if (task == null) policy = records.businessPolicy();
      else {
        String json = s(task, "business_policy_snapshot");
        if (!(n(task, "record_version") == 2 || n(task, "record_version") == 3)
            || json == null
            || !records.hash(json).equals(task.get("business_policy_digest"))
            || task.get("execution_scope_digest") != null
            || task.get("evaluation_snapshot") != null
            || !java.util.Objects.equals(
                task.get("evaluation_run_id"), run == null ? null : run.get("id")))
          throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
        policy = records.readPolicy(json);
        if (!(n(task, "record_version") == 2 && policy.version() == 1
            || n(task, "record_version") == 3 && policy.version() == 2))
          throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
        if (!records.canonical(policy).equals(json))
          throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
        model = records.historicalSnapshot(task);
      }
      records.validate(policy);
      records.retainedBusiness(policy);
      if (!tenant.equals(policy.anchor().tenantId()))
        throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
      businessAnchor(policy, run);
      if (records.boundPlan(run) == null)
        throw new TaskFailure(503, ModelErrorCode.BUSINESS_POLICY_UNAVAILABLE);
      records.profile(policy, model, records.localEvaluation(model));
      records.available(model);
    } catch (IllegalArgumentException corrupt) {
      throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
    } catch (TaskFailure invalid) {
      if (invalid.status() == 409)
        throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
      throw invalid;
    }
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }
}
