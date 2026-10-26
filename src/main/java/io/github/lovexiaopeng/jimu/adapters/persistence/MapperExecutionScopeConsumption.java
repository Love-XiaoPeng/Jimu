package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.ExecutionScopeMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Same DS/UoW; preserve the caller JDBC scalar and explicit Mapper query context. */
final class MapperExecutionScopeConsumption implements ExecutionScopeConsumptionRecords {
  static ExecutionScopes.Requirement unwrap(Requirement value) {
    return value == null ? null : ((RequirementView) value).value();
  }

  private final JdbcLedger ledger;
  private final org.springframework.jdbc.core.JdbcTemplate db;

  MapperExecutionScopeConsumption(
      JdbcLedger ledger, org.springframework.jdbc.core.JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  private record RequirementView(ExecutionScopes.Requirement value) implements Requirement {
    public int version() {
      return value.version();
    }

    public String issueId() {
      return value.issueId();
    }

    public String instanceUuid() {
      return value.instanceUuid();
    }

    public String schema() {
      return value.schema();
    }

    public String tenantId() {
      return value.tenantId();
    }

    public String runId() {
      return value.runId();
    }

    public String approvalDigest() {
      return value.approvalDigest();
    }

    public boolean same(Requirement other) {
      return value.equals(other == null ? null : ((RequirementView) other).value());
    }
  }

  static Requirement wrap(ExecutionScopes.Requirement value) {
    return value == null ? null : new RequirementView(value);
  }

  private record PinView(ExecutionScopes.Pin value) implements Pin {
    public int configVersion() {
      return value.configVersion();
    }

    public boolean stream() {
      return value.stream();
    }

    public String modelSnapshotDigest() {
      return value.modelSnapshotDigest();
    }
  }

  static Pin wrap(ExecutionScopes.Pin value) {
    return value == null ? null : new PinView(value);
  }

  private record ScopeView(ExecutionScopes.Scope value) implements Scope {
    public int version() {
      return value.version();
    }

    public String issueId() {
      return value.issueId();
    }

    public String instanceUuid() {
      return value.instanceUuid();
    }

    public String schema() {
      return value.schema();
    }

    public String tenantId() {
      return value.tenantId();
    }

    public String runId() {
      return value.runId();
    }

    public String approvalDigest() {
      return value.approvalDigest();
    }

    public String planDigest() {
      return value.planDigest();
    }

    public String materialsDigest() {
      return value.materialsDigest();
    }

    public String sourcePlanSha256() {
      return value.sourcePlanSha256();
    }

    public String allowedModel() {
      return value.allowedModel();
    }

    public String allowedRevision() {
      return value.allowedRevision();
    }

    public boolean localOnly() {
      return value.localOnly();
    }

    public Map<String, String> sampleProfiles() {
      return value.sampleProfiles();
    }

    public Pin pin(String profile) {
      return wrap(value.profilePins().get(profile));
    }
  }

  static Scope wrap(ExecutionScopes.Scope value) {
    return value == null ? null : new ScopeView(value);
  }

  private record PolicyView(BusinessPolicies.Policy value) implements Policy {
    public int version() {
      return value.version();
    }

    public Requirement anchor() {
      return wrap(value.anchor());
    }
  }

  static Policy wrap(BusinessPolicies.Policy value) {
    return value == null ? null : new PolicyView(value);
  }

  public <T> T dataAccess(Supplier<T> work) {
    try {
      return work.get();
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw new Unavailable();
    }
  }

  public Integer receiptTable() {
    return db.queryForObject(
        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jimu_provisioning_receipt'",
        Integer.class);
  }

  public String serverUuid() {
    return db.queryForObject("SELECT @@server_uuid", String.class);
  }

  public String schema() {
    return db.queryForObject("SELECT DATABASE()", String.class);
  }

  private ExecutionScopeMapper mapper() {
    return ledger.mappers.session().getMapper(ExecutionScopeMapper.class);
  }

  public List<Map<String, Object>> receipts(String tenant) {
    return mapper().consumptionReceipts(ledger.queryContext(), tenant);
  }

  public List<String> boundRuns(String tenant) {
    return mapper().boundRuns(ledger.queryContext(), tenant);
  }

  public List<Map<String, Object>> scopes(String tenant, String run) {
    return mapper().consumptionScopes(ledger.queryContext(), tenant, run);
  }

  public Requirement scopeRequirement() {
    return wrap(ledger.models.scopeRequirement());
  }

  public EvaluationPlan.Selection evaluationSelection() {
    return ledger.models.evaluationSelection();
  }

  public ModelSnapshot selected(String tenant) {
    return ledger.models.selected(tenant);
  }

  public boolean localEvaluation(ModelSnapshot model) {
    return ledger.models.localEvaluation(model);
  }

  public String evaluationRunId() {
    return ledger.models.evaluationRunId();
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public boolean digest(String raw) {
    return ExecutionScopes.digest(raw);
  }

  public String activeCandidate(String raw) {
    return Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class)
        .path("activeCandidateOnly")
        .asText();
  }

  public Scope readScope(String raw) {
    return wrap(Payloads.read(raw, ExecutionScopes.Scope.class));
  }

  public String canonical(Scope scope) {
    return ExecutionScopes.canonical(scope == null ? null : ((ScopeView) scope).value());
  }

  public String modelDigest(ModelSnapshot model) {
    return ModelProfiles.digest(model);
  }

  public EvaluationPlan.Plan plan(String id) {
    return EvaluationPlans.plan(id);
  }

  public String planDigest(EvaluationPlan.Plan plan) {
    return EvaluationPlans.digest(plan);
  }

  public Policy businessPolicy() {
    return wrap(ledger.models.businessPolicy());
  }

  public Policy readPolicy(String raw) {
    return wrap(Payloads.read(raw, BusinessPolicies.Policy.class));
  }

  public String canonical(Policy policy) {
    return BusinessPolicies.canonical(policy == null ? null : ((PolicyView) policy).value());
  }

  public void validate(Policy policy) {
    BusinessPolicies.validate(policy == null ? null : ((PolicyView) policy).value());
  }

  public void retainedBusiness(Policy policy) {
    ledger.models.retainedBusiness(policy == null ? null : ((PolicyView) policy).value());
  }

  public void profile(Policy policy, ModelSnapshot model, boolean local) {
    BusinessPolicies.profile(((PolicyView) policy).value(), model, local);
  }

  public ModelSnapshot historicalSnapshot(Map<String, Object> task) {
    return ledger.historicalSnapshot(task);
  }

  public void available(ModelSnapshot model) {
    ledger.models.available(model);
  }

  public void checkEvaluation(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model) {
    ledger.checkEvaluation(task, evaluation, model);
  }

  public EvaluationPlan.Plan boundPlan(Map<String, Object> run) {
    return ledger.plan(run);
  }

  public void stopRun(String tenant, String run) {
    ledger.mappers.evaluations().stopRun(ledger.queryContext(), tenant, run);
  }

  public void event(Map<String, Object> task, String key, String type, String payload) {
    ledger.event(task, key, type, payload);
  }
}
