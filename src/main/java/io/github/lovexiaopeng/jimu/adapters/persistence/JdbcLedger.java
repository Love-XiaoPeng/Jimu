package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * InnoDB is the only authority. Every write locks budget before task and checks ownership inside
 * the transaction.
 */
public final class JdbcLedger implements Tasks, ExecutionLedger {
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final ModelProfiles models;
  private final JdbcExecutionScopes executionScopes;

  public JdbcLedger(DataSource source, ModelProfiles models) {
    this.models = models;
    db = new JdbcTemplate(source);
    db.setQueryTimeout(5);
    executionScopes = new JdbcExecutionScopes(db, models);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
  }

  private <T> T tx(Supplier<T> work) {
    AdmissionResult<T> result =
        transactions.execute(
            status -> {
              try {
                return new AdmissionResult<T>(work.get(), null);
              } catch (TaskFailure failure) {
                // Scope checks run before admission writes. Only the deliberate persistent
                // integrity rejection (or already admitted settlement) commits here.
                if (!List.of(
                        "EVALUATION_SCOPE_MISMATCH",
                        "MODEL_SETTLEMENT_PROOF_INVALID",
                        "BUSINESS_POLICY_CONFLICT")
                    .contains(failure.code())) throw failure;
                return new AdmissionResult<T>(null, failure);
              }
            });
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private String requireScope(
      String tenant,
      Map<String, Object> evaluation,
      Map<String, Object> task,
      ModelSnapshot model) {
    try {
      if (task == null && models.businessPolicy() != null || task != null && businessTask(task)) {
        requireBusiness(tenant, evaluation, task, model);
        return null;
      }
      if (!executionScopes.needed(tenant, task)) return null;
      if (task != null) {
        if (task.get("execution_scope_digest") == null)
          throw new TaskFailure(409, "EVALUATION_SCOPE_LEGACY_UNBOUND");
        try {
          model = historicalSnapshot(task);
        } catch (TaskFailure | IllegalArgumentException corrupt) {
          throw new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH");
        }
      }
      String digest = executionScopes.require(tenant, evaluation, task, model);
      if (task != null) {
        try {
          models.available(model);
          checkEvaluation(task, evaluation, model);
        } catch (TaskFailure | IllegalArgumentException corrupt) {
          throw new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH");
        }
      }
      return digest;
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
    } catch (TaskFailure failure) {
      if (List.of("EVALUATION_SCOPE_MISMATCH", "BUSINESS_POLICY_CONFLICT").contains(failure.code())
          && evaluation != null) {
        db.update(
            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
            tenant,
            s(evaluation, "id"));
        if (task != null)
          event(
              task,
              "scope-rejected:" + n(task, "fence"),
              "evaluation.scope_rejected",
              failure.code());
      }
      throw failure;
    }
  }

  private boolean businessTask(Map<String, Object> task) {
    return n(task, "record_version") == 2
        || task.get("business_policy_snapshot") != null
        || task.get("business_policy_digest") != null;
  }

  private void requireBusiness(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    BusinessPolicies.Policy policy;
    try {
      if (task == null) policy = models.businessPolicy();
      else {
        String json = s(task, "business_policy_snapshot");
        if (n(task, "record_version") != 2
            || json == null
            || !Payloads.hash(json).equals(task.get("business_policy_digest"))
            || task.get("execution_scope_digest") != null
            || task.get("evaluation_snapshot") != null
            || !java.util.Objects.equals(
                task.get("evaluation_run_id"), run == null ? null : run.get("id")))
          throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
        policy = Payloads.read(json, BusinessPolicies.Policy.class);
        if (!BusinessPolicies.canonical(policy).equals(json))
          throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
        model = historicalSnapshot(task);
      }
      BusinessPolicies.validate(policy);
      models.retainedBusiness(policy);
      if (!tenant.equals(policy.anchor().tenantId()))
        throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
      executionScopes.businessAnchor(policy, run);
      if (plan(run) == null) throw new TaskFailure(503, "BUSINESS_POLICY_UNAVAILABLE");
      BusinessPolicies.profile(policy, model, models.localEvaluation(model));
      models.available(model);
    } catch (IllegalArgumentException corrupt) {
      throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
    } catch (TaskFailure invalid) {
      if (invalid.status() == 409) throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
      throw invalid;
    }
  }

  private record AdmissionResult<T>(T value, TaskFailure rejection) {}

  // Material checks precede quota/IO admission. Commit STOPPED with the rejection fact,
  // then throw outside the transaction; a process exit before task failure cannot undo it.
  private <T> T leaseTx(Lease lease, Supplier<T> work) {
    AdmissionResult<T> result =
        tx(
            () -> {
              try {
                return new AdmissionResult<T>(work.get(), null);
              } catch (TaskFailure rejection) {
                if (!rejection.code().equals("EVALUATION_MATERIAL_MISMATCH")) throw rejection;
                db.update(
                    "UPDATE evaluation_budget b JOIN task t ON t.tenant_id=b.tenant_id AND t.evaluation_run_id=b.id SET b.state='STOPPED' WHERE t.tenant_id=? AND t.id=?",
                    lease.tenant(),
                    lease.taskId());
                return new AdmissionResult<T>(null, rejection);
              }
            });
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  private Map<String, Object> one(String sql, Object... args) {
    var result = rows(sql, args);
    if (result.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return result.getFirst();
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Instant time(Map<String, Object> row, String key) {
    return row.get(key) == null
        ? null
        : ((java.time.LocalDateTime) row.get(key)).toInstant(java.time.ZoneOffset.UTC);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private Map<String, Object> bucket(String tenant) {
    return one("SELECT * FROM tenant_budget WHERE tenant_id=? FOR UPDATE", tenant);
  }

  private void identity(Identity identity) {
    var result =
        rows(
            "SELECT enabled FROM actor_access WHERE tenant_id=? AND actor_id=? FOR SHARE",
            identity.tenant(),
            identity.actor());
    if (result.isEmpty() || !Boolean.TRUE.equals(result.getFirst().get("enabled")))
      throw new TaskFailure(403, "ACCESS_REVOKED");
  }

  private long epoch(Identity identity, List<String> capabilities) {
    identity(identity);
    long epoch = 0;
    for (String capability : capabilities) {
      var installed =
          rows(
              "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE",
              identity.tenant(),
              capability);
      if (installed.isEmpty() || !Boolean.TRUE.equals(installed.getFirst().get("enabled")))
        throw new TaskFailure(403, "CAPABILITY_REVOKED");
      // Each enabled capability has its own version; sum is monotonic because epochs never
      // decrease.
      epoch += n(installed.getFirst(), "epoch");
    }
    return epoch;
  }

  @Override
  public List<String> capabilities(Identity identity) {
    return tx(
        () -> {
          identity(identity);
          return db.queryForList(
              "SELECT plugin_id FROM installation WHERE tenant_id=? AND enabled=TRUE ORDER BY plugin_id",
              String.class,
              identity.tenant());
        });
  }

  @Override
  public View submit(Identity identity, String key, Request request) {
    return tx(
        () -> {
          // InnoDB also locks the evaluation parent while checking task's FK. Acquire that
          // parent before tenant_budget, including acceptance and idempotent retries.
          var binding =
              rows(
                  "SELECT evaluation_run_id FROM task WHERE tenant_id=? AND owner_actor_id=? AND idempotency_key=?",
                  identity.tenant(),
                  identity.actor(),
                  key);
          String run;
          if (binding.isEmpty()) {
            run = executionScopes.targetRun(identity.tenant(), null);
            if (run == null) {
              var candidate = models.selected(identity.tenant());
              run = candidate.paid() ? models.evaluationRunId() : null;
            }
          } else run = s(binding.getFirst(), "evaluation_run_id");
          var evaluation = evaluation(identity.tenant(), run);
          var budget = bucket(identity.tenant());
          identity(identity);
          String snapshot = Payloads.json(request), digest = Payloads.hash(snapshot);
          var old =
              rows(
                  "SELECT * FROM task WHERE tenant_id=? AND owner_actor_id=? AND idempotency_key=? FOR UPDATE",
                  identity.tenant(),
                  identity.actor(),
                  key);
          if (!old.isEmpty()) {
            if (!Payloads.hash(s(old.getFirst(), "request_snapshot"))
                .equals(s(old.getFirst(), "request_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            if (!Payloads.read(s(old.getFirst(), "request_snapshot"), Request.class)
                .equals(request)) throw new TaskFailure(409, "IDEMPOTENCY_CONFLICT");
            return view(old.getFirst());
          }
          long installationEpoch = epoch(identity, request.capabilityIds());
          var pins = new java.util.LinkedHashMap<String, PluginPin>();
          for (String capability : request.capabilityIds()) {
            var installed =
                one(
                    "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                    identity.tenant(),
                    capability);
            pins.put(
                capability,
                new PluginPin(
                    s(installed, "plugin_version"),
                    s(installed, "plugin_digest"),
                    n(installed, "epoch")));
          }
          String toolSnapshot = Payloads.json(new ToolSnapshot(1, pins));
          var model = models.selected(identity.tenant());
          String scopeDigest = requireScope(identity.tenant(), evaluation, null, model);
          var businessPolicy = models.businessPolicy();
          if (request.capabilityIds().contains("compose_report")
              && request.capabilityIds().contains("write"))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (businessPolicy != null
              && request.capabilityIds().contains("present")
              && !request
                  .capabilityIds()
                  .equals(List.of("web_fetch", "read", "compose_report", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          Long explicitCost = request.budget().maxCostMicros();
          if (explicitCost != null
              && (explicitCost < 1 || businessPolicy == null && explicitCost > 1000000))
            throw new TaskFailure(400, "INVALID_BUDGET");
          if (request.budget().maxCostMicros() != null && !model.paid())
            throw new TaskFailure(400, "CURRENCY_BUDGET_NOT_CONFIGURED");
          models.admit(model);
          long taskCost = explicitCost == null ? 1000000 : explicitCost;
          long taskTokens = 65536;
          if (businessPolicy != null) {
            long singleCost;
            long hostCost;
            try {
              long singleTokens =
                  Math.addExact(model.price().inputTokenUpper(), model.maxOutputTokens());
              singleCost =
                  ModelCosts.amount(
                      model.price(), model.price().inputTokenUpper(), model.maxOutputTokens());
              taskTokens = Math.multiplyExact(singleTokens, request.budget().maxModelRequests());
              hostCost =
                  Math.min(
                      Math.multiplyExact(singleCost, request.budget().maxModelRequests()),
                      n(evaluation, "max_cost_micros"));
            } catch (ArithmeticException unrepresentable) {
              throw new TaskFailure(503, "MODEL_COST_BOUND_UNAVAILABLE");
            }
            if (explicitCost != null && explicitCost > hostCost)
              throw new TaskFailure(400, "INVALID_BUDGET");
            taskCost = explicitCost == null ? hostCost : explicitCost;
            if (taskCost < singleCost) throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          }
          var evaluationBinding = businessPolicy == null ? bindEvaluation(evaluation, model) : null;
          var material =
              evaluationBinding == null
                  ? null
                  : EvaluationMaterials.load(
                      models.evaluationSelection().plan(), evaluationBinding.sample());
          if (material != null) EvaluationMaterials.require(request.goal().equals(material.goal()));
          if (evaluationBinding != null
              && !request.capabilityIds().equals(List.of("web_fetch", "read", "write", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (evaluationBinding != null
              && !rows(
                      "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_model=? AND evaluation_sample=? LIMIT 1",
                      identity.tenant(),
                      run,
                      model.model(),
                      evaluationBinding.sample())
                  .isEmpty()) throw new TaskFailure(409, "EVALUATION_SAMPLE_ALREADY_BOUND");
          String modelSnapshot = Payloads.json(model);
          if (n(budget, "queued") >= n(budget, "queue_limit"))
            throw new TaskFailure(429, "QUEUE_LIMIT");
          String task = id();
          db.update(
              "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),?,?)",
              identity.tenant(),
              task,
              identity.actor(),
              key,
              digest,
              snapshot,
              request.goal(),
              String.join(",", request.capabilityIds()),
              installationEpoch,
              request.budget().deadlineSeconds(),
              request.budget().maxModelRequests(),
              request.budget().maxToolInvocations());
          db.update(
              "UPDATE task SET model_snapshot=?,model_digest=?,evaluation_run_id=?,max_cost_micros=?,tools_snapshot=?,tools_digest=?,execution_scope_digest=?,report_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              modelSnapshot,
              Payloads.hash(modelSnapshot),
              model.paid() ? run : null,
              taskCost,
              toolSnapshot,
              Payloads.hash(toolSnapshot),
              scopeDigest,
              identity.tenant(),
              task);
          if (businessPolicy != null) {
            String policyJson = BusinessPolicies.canonical(businessPolicy);
            db.update(
                "UPDATE task SET record_version=2,business_policy_snapshot=?,business_policy_digest=?,max_tokens=? WHERE tenant_id=? AND id=?",
                policyJson,
                Payloads.hash(policyJson),
                taskTokens,
                identity.tenant(),
                task);
          }
          if (evaluationBinding != null) {
            var selection = models.evaluationSelection();
            var plan = selection.plan();
            String bindingSnapshot = Payloads.json(evaluationBinding);
            db.update(
                "UPDATE task SET evaluation_snapshot=?,evaluation_digest=?,evaluation_phase=?,evaluation_sample=?,evaluation_model=?,max_models=LEAST(max_models,?),max_tokens=?,max_cost_micros=LEAST(max_cost_micros,?) WHERE tenant_id=? AND id=?",
                bindingSnapshot,
                Payloads.hash(bindingSnapshot),
                selection.phase(),
                selection.sample(),
                model.model(),
                selection.sample().equals("W1") ? 7 : 6,
                (selection.sample().equals("W1") ? 7L : 6L) * 1004096,
                request.budget().maxCostMicros() == null
                    ? EvaluationPlans.taskCost(plan, model.model(), selection.sample())
                    : Math.min(
                        request.budget().maxCostMicros(),
                        EvaluationPlans.taskCost(plan, model.model(), selection.sample())),
                identity.tenant(),
                task);
            String materialSnapshot = Payloads.json(material);
            var accepted = locked(identity.tenant(), task);
            String system =
                material
                    .systemTemplate()
                    .replace("{{HOST_REPORT_TIME}}", time(accepted, "report_time").toString());
            db.update(
                "UPDATE task SET material_snapshot=?,material_digest=?,material_system_digest=? WHERE tenant_id=? AND id=?",
                materialSnapshot,
                Payloads.hash(materialSnapshot),
                Payloads.hash(system),
                identity.tenant(),
                task);
            // The HTTP budget only tightens this host-selected private task ceiling.
            if (request.budget().maxCostMicros() == null)
              db.update(
                  "UPDATE task SET max_cost_micros=? WHERE tenant_id=? AND id=?",
                  EvaluationPlans.taskCost(plan, model.model(), selection.sample()),
                  identity.tenant(),
                  task);
          }
          db.update(
              "UPDATE tenant_budget SET queued=queued+1 WHERE tenant_id=?", identity.tenant());
          reserve(identity.tenant(), task, task + ":queue", null, "QUEUE", 1, "RESERVED", "KNOWN");
          var row = locked(identity.tenant(), task);
          event(row, "accepted", "task.accepted", null);
          return view(locked(identity.tenant(), task));
        });
  }

  private Map<String, Object> locked(String tenant, String task) {
    return one(
        "SELECT *,lease_until>CURRENT_TIMESTAMP(6) AS live,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND id=? FOR UPDATE",
        tenant,
        task);
  }

  private Map<String, Object> visible(Identity identity, String task) {
    identity(identity);
    return one(
        "SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=?",
        identity.tenant(),
        task,
        identity.actor());
  }

  private List<String> caps(Map<String, Object> row) {
    return Arrays.asList(s(row, "capabilities").split(","));
  }

  private void access(Map<String, Object> row) {
    if (epoch(new Identity(s(row, "tenant_id"), s(row, "owner_actor_id")), caps(row))
        != n(row, "installation_epoch")) throw new TaskFailure(403, "CAPABILITY_REVOKED");
  }

  @Override
  public View get(Identity identity, String task) {
    return tx(
        () -> {
          var row = visible(identity, task);
          access(row);
          return view(row);
        });
  }

  private View view(Map<String, Object> row) {
    var artifacts =
        rows(
                "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND state='PUBLISHED' ORDER BY id",
                s(row, "tenant_id"),
                s(row, "id"))
            .stream()
            .map(this::artifact)
            .toList();
    return new View(
        s(row, "id"),
        s(row, "state"),
        n(row, "revision"),
        time(row, "created_at"),
        time(row, "updated_at"),
        time(row, "cancel_requested_at"),
        s(row, "code"),
        s(row, "summary"),
        artifacts);
  }

  private Artifact artifact(Map<String, Object> row) {
    return new Artifact(
        s(row, "id"),
        s(row, "media_type"),
        n(row, "size"),
        s(row, "sha256"),
        time(row, "expires_at"));
  }

  @Override
  public View cancel(Identity identity, String task) {
    return tx(
        () -> {
          bucket(identity.tenant());
          visible(identity, task);
          var row = locked(identity.tenant(), task);
          if (row.get("cancel_requested_at") != null) return view(row);
          if (view(row).terminal()) {
            // Unknown outcome is terminal for business execution, but does not prove the
            // writer exited. Persist a stop intent without changing that outcome or its cost.
            if (!"NEEDS_RECONCILIATION".equals(s(row, "state")) || writersQuiescent(row))
              return view(row);
            db.update(
                "UPDATE task SET cancel_requested_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                identity.tenant(),
                task);
            event(row, "cancel", "task.cancel_requested", null);
            return view(locked(identity.tenant(), task));
          }
          db.update(
              "UPDATE task SET cancel_requested_at=CURRENT_TIMESTAMP(6), state='CANCEL_REQUESTED' WHERE tenant_id=? AND id=?",
              identity.tenant(),
              task);
          event(row, "cancel", "task.cancel_requested", null);
          if ("QUEUED".equals(s(row, "state"))) terminal(row, "CANCELLED", "CANCELLED");
          return view(locked(identity.tenant(), task));
        });
  }

  @Override
  public List<Event> events(Identity identity, String task, long after, int limit) {
    return tx(
        () -> {
          var row = visible(identity, task);
          access(row);
          if (after > n(row, "next_event_seq")) throw new TaskFailure(400, "FUTURE_CURSOR");
          long oldest =
              db.queryForObject(
                  "SELECT COALESCE(MIN(seq),0) FROM event WHERE tenant_id=? AND task_id=? AND occurred_at>CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY",
                  Long.class,
                  identity.tenant(),
                  task);
          if ((oldest > 0 && after < oldest - 1) || (oldest == 0 && n(row, "next_event_seq") > 0))
            throw new TaskFailure(410, "CURSOR_EXPIRED");
          return rows(
                  "SELECT * FROM event WHERE tenant_id=? AND task_id=? AND seq>? ORDER BY seq LIMIT ?",
                  identity.tenant(),
                  task,
                  after,
                  Math.min(limit, 64))
              .stream()
              .map(
                  e ->
                      new Event(
                          s(e, "event_id"),
                          n(e, "seq"),
                          s(e, "type"),
                          task,
                          time(e, "occurred_at"),
                          s(e, "code"),
                          s(e, "status"),
                          e.get("artifact_id") == null ? List.of() : List.of(s(e, "artifact_id"))))
              .toList();
        });
  }

  @Override
  public Download download(Identity identity, String artifactId) {
    return tx(
        () -> {
          identity(identity);
          var row =
              one(
                  "SELECT a.* FROM artifact a JOIN task t ON a.tenant_id=t.tenant_id AND a.task_id=t.id WHERE a.tenant_id=? AND a.id=? AND t.owner_actor_id=? AND a.state IN ('PUBLISHED','EXPIRED')",
                  identity.tenant(),
                  artifactId,
                  identity.actor());
          access(visible(identity, s(row, "task_id")));
          if (time(row, "expires_at").isBefore(Instant.now()))
            throw new TaskFailure(410, "ARTIFACT_EXPIRED");
          return new Download(artifact(row), identity.tenant(), s(row, "snapshot_key"));
        });
  }

  private void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String quality) {
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,dispatch_id,category,amount,state,usage_quality) VALUES(?,?,?,?,?,?,?,?)",
        tenant,
        reservation,
        task,
        dispatch,
        category,
        amount,
        state,
        quality);
  }

  private void event(Map<String, Object> task, String fact, String type, String code) {
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    if (!rows(
            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
            tenant,
            taskId,
            fact)
        .isEmpty()) return;
    db.update(
        "UPDATE task SET next_event_seq=next_event_seq+1, revision=revision+1, updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
        tenant,
        taskId);
    long seq =
        db.queryForObject(
            "SELECT next_event_seq FROM task WHERE tenant_id=? AND id=?",
            Long.class,
            tenant,
            taskId);
    db.update(
        "INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,code,status,artifact_id) VALUES(?,?,?,?,?,?,?,?,?)",
        tenant,
        taskId,
        seq,
        taskId + ":" + seq,
        fact,
        type,
        code,
        db.queryForObject(
            "SELECT state FROM task WHERE tenant_id=? AND id=?", String.class, tenant, taskId),
        "artifact.created".equals(type) ? fact.substring("published:".length()) : null);
  }

  @Override
  public Optional<Lease> claim(String worker) {
    for (String tenant :
        db.queryForList("SELECT tenant_id FROM tenant_budget ORDER BY tenant_id", String.class)) {
      // Select without a lock, then recheck this exact candidate under the shared run lock.
      // Every worker transaction takes run -> tenant -> task, including recovery claims.
      var ready =
          rows(
              "SELECT id,evaluation_run_id FROM task WHERE tenant_id=? AND record_version IN (1,2) AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY ready_at,id LIMIT 1",
              tenant);
      if (ready.isEmpty()) continue;
      var candidate = ready.getFirst();
      Optional<Lease> lease =
          tx(
              () -> {
                var evaluation =
                    evaluation(
                        tenant,
                        executionScopes.targetRun(tenant, s(candidate, "evaluation_run_id")));
                var budget = bucket(tenant);
                var candidates =
                    rows(
                        "SELECT *,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND id=? AND record_version IN (1,2) AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))) FOR UPDATE SKIP LOCKED",
                        tenant,
                        s(candidate, "id"));
                if (candidates.isEmpty()) return Optional.empty();
                var task = candidates.getFirst();
                if (!java.util.Objects.equals(
                    candidate.get("evaluation_run_id"), task.get("evaluation_run_id")))
                  return Optional
                      .empty(); // Retry under the correct parent lock; never change lock order.
                String taskId = s(task, "id");
                boolean queued = "QUEUED".equals(s(task, "state"));
                if (queued && n(budget, "running") >= n(budget, "running_limit"))
                  return Optional.empty();
                // An expired writer might have caused an effect. Never infer safety from an absent
                // result.
                if (!rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                        tenant,
                        taskId)
                    .isEmpty()) {
                  if (modelRejection(task) == null && runRejection(evaluation) == null) {
                    try {
                      requireScope(tenant, evaluation, task, null);
                    } catch (TaskFailure failure) {
                      // Legacy unbound writers still require reconciliation. A claimed corrupt
                      // proof must first durably stop the shared run, before any recovery writes.
                      if (!failure.code().equals("EVALUATION_SCOPE_LEGACY_UNBOUND")) throw failure;
                    }
                  }
                  db.update(
                      "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND task_id=? AND state='RUNNING'",
                      tenant,
                      taskId);
                  terminal(task, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
                  return Optional.empty();
                }
                String rejection = modelRejection(task);
                if (rejection == null) rejection = runRejection(evaluation);
                if (rejection != null) {
                  terminal(task, "FAILED", rejection);
                  return Optional.empty();
                }
                try {
                  requireScope(tenant, evaluation, task, null);
                } catch (TaskFailure failure) {
                  if (List.of("EVALUATION_SCOPE_MISMATCH", "BUSINESS_POLICY_CONFLICT")
                      .contains(failure.code())) throw failure;
                  terminal(task, "FAILED", failure.code());
                  return Optional.empty();
                }
                if (!queued)
                  db.update(
                      "UPDATE task_attempt SET state='EXPIRED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='ACTIVE'",
                      tenant,
                      s(task, "current_attempt_id"));
                if (n(task, "within_deadline") != 1) {
                  terminal(task, "FAILED", "DEADLINE_EXCEEDED");
                  return Optional.empty();
                }
                try {
                  access(task);
                } catch (TaskFailure e) {
                  terminal(task, "FAILED", e.code());
                  return Optional.empty();
                }
                if (task.get("cancel_requested_at") != null) {
                  terminal(task, "CANCELLED", "CANCELLED");
                  return Optional.empty();
                }
                String attempt = id();
                long fence = n(task, "fence") + 1;
                if (queued) {
                  db.update(
                      "UPDATE tenant_budget SET queued=queued-1,running=running+1 WHERE tenant_id=?",
                      tenant);
                  db.update(
                      "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                      tenant,
                      taskId + ":queue");
                  reserve(tenant, taskId, taskId + ":run", null, "RUNNING", 1, "RESERVED", "KNOWN");
                }
                db.update(
                    "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES(?,?,?,?,?,'ACTIVE')",
                    tenant,
                    attempt,
                    taskId,
                    worker,
                    fence);
                db.update(
                    "UPDATE task SET state='RUNNING',fence=?,current_attempt_id=?,worker_id=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE tenant_id=? AND id=?",
                    fence,
                    attempt,
                    worker,
                    tenant,
                    taskId);
                event(task, "attempt:" + fence, "task.started", null);
                return Optional.of(
                    new Lease(
                        tenant,
                        s(task, "owner_actor_id"),
                        taskId,
                        attempt,
                        fence,
                        worker,
                        time(task, "deadline"),
                        s(task, "goal"),
                        caps(task),
                        n(task, "installation_epoch")));
              });
      if (lease.isPresent()) return lease;
    }
    return Optional.empty();
  }

  private Map<String, Object> fenced(Lease lease, boolean admission) {
    // Hold through the committing transaction: STOPPED cannot race a cached admission,
    // publication or successful finish. Settlement of previously admitted effects stays allowed.
    var evaluation = evaluation(lease, admission);
    bucket(lease.tenant());
    var task = locked(lease.tenant(), lease.taskId());
    if (n(task, "fence") != lease.fence()
        || !lease.attemptId().equals(s(task, "current_attempt_id"))
        || !lease.worker().equals(s(task, "worker_id"))
        || n(task, "live") != 1
        || !List.of("RUNNING", "CANCEL_REQUESTED").contains(s(task, "state")))
      throw new TaskFailure(409, "FENCE_REJECTED");
    if (admission) {
      String rejection = modelRejection(task);
      if (rejection == null) rejection = runRejection(evaluation);
      if (rejection != null) throw new TaskFailure(409, rejection);
      requireScope(lease.tenant(), evaluation, task, null);
      checkMaterials(task);
      access(task);
      if (task.get("cancel_requested_at") != null) throw new TaskFailure(409, "CANCEL_REQUESTED");
      if (n(task, "within_deadline") != 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
    }
    if (db.update(
            "UPDATE task SET revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND revision=? AND current_attempt_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6)",
            lease.tenant(),
            lease.taskId(),
            n(task, "revision"),
            lease.attemptId(),
            lease.fence())
        != 1) throw new TaskFailure(409, "FENCE_REJECTED");
    return task;
  }

  private EvaluationMaterials.Material material(Map<String, Object> task) {
    if (task.get("evaluation_snapshot") == null) return null;
    String json = s(task, "material_snapshot");
    EvaluationMaterials.require(
        json != null && Payloads.hash(json).equals(s(task, "material_digest")));
    var stored = Payloads.read(json, EvaluationMaterials.Material.class);
    var binding = Payloads.read(s(task, "evaluation_snapshot"), EvaluationPlans.Binding.class);
    var expected =
        EvaluationMaterials.load(EvaluationPlans.plan(binding.planId()), binding.sample());
    EvaluationMaterials.require(
        expected.equals(stored)
            && stored.goal().equals(s(task, "goal"))
            && Payloads.hash(
                    stored
                        .systemTemplate()
                        .replace("{{HOST_REPORT_TIME}}", time(task, "report_time").toString()))
                .equals(s(task, "material_system_digest")));
    return stored;
  }

  private void checkMaterials(Map<String, Object> task) {
    var material = material(task);
    if (material == null) return;
    for (var invocation :
        rows(
            "SELECT input_snapshot,output_snapshot,output_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='web_fetch' AND state='SUCCEEDED'",
            s(task, "tenant_id"),
            s(task, "id"))) {
      String output = s(invocation, "output_snapshot");
      EvaluationMaterials.require(
          output != null && Payloads.hash(output).equals(s(invocation, "output_digest")));
      var input =
          Payloads.read(
              s(invocation, "input_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
      EvaluationMaterials.source(material, input.path("url").asText(), output);
    }
    if (task.get("material_file_snapshot") != null) {
      var initial = initialFile(task);
      boolean written = false;
      for (var invocation :
          rows(
              "SELECT i.tool,i.output_snapshot,i.output_digest FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.tenant_id=? AND i.task_id=? AND i.state='SUCCEEDED' AND i.tool IN ('read','write') ORDER BY s.ordinal,i.slot",
              s(task, "tenant_id"),
              s(task, "id"))) {
        if (s(invocation, "tool").equals("write")) {
          written = true;
          continue;
        }
        if (written) continue;
        String output = s(invocation, "output_snapshot");
        EvaluationMaterials.require(Payloads.hash(output).equals(s(invocation, "output_digest")));
        var read = Payloads.read(output, com.fasterxml.jackson.databind.JsonNode.class);
        if (material.initialContent() != null) {
          var visible = new StringBuilder();
          String[] lines = material.initialContent().split("\n", -1);
          for (int index = 0; index < lines.length; index++)
            visible.append(index + 1).append(": ").append(lines[index]).append('\n');
          EvaluationMaterials.require(
              read.path("offset").asInt() == 1
                  && read.path("content").asText().equals(visible.toString()));
        }
        EvaluationMaterials.require(
            read.path("filePath").asText().equals(initial.path())
                && read.path("version").asLong() == initial.version()
                && read.path("size").asLong() == initial.size()
                && java.util.Objects.equals(
                    read.path("sha256").isNull() ? null : read.path("sha256").asText(),
                    initial.sha256()));
      }
    }
  }

  @Override
  public Optional<String> evaluationFile(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var material = material(task);
          if (material == null) return Optional.empty();
          boolean written =
              !rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND tool='write' AND state='SUCCEEDED' LIMIT 1",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty();
          if (written) {
            initialFile(task);
            return Optional.empty();
          }
          // A restart before first dispatch may inspect again, but may never replace its original
          // receipt.
          return Optional.of(material.target());
        });
  }

  private ReportFile initialFile(Map<String, Object> task) {
    String json = s(task, "material_file_snapshot");
    EvaluationMaterials.require(
        json != null && Payloads.hash(json).equals(s(task, "material_file_digest")));
    return Payloads.read(json, ReportFile.class);
  }

  @Override
  public void bindEvaluationFile(Lease lease, ReportFile file) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var material = material(task);
          EvaluationMaterials.require(material != null && material.target().equals(file.path()));
          String content = material.initialContent();
          EvaluationMaterials.require(
              content == null
                  ? file.version() == 0 && file.sha256() == null && file.size() == 0
                  : file.version() > 0
                      && Payloads.hash(content).equals(file.sha256())
                      && content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                          == file.size());
          if (task.get("material_file_snapshot") != null)
            EvaluationMaterials.require(initialFile(task).equals(file));
          else {
            EvaluationMaterials.require(
                rows(
                        "SELECT id FROM step WHERE tenant_id=? AND task_id=? LIMIT 1",
                        lease.tenant(),
                        lease.taskId())
                    .isEmpty());
            String json = Payloads.json(file);
            db.update(
                "UPDATE task SET material_file_snapshot=?,material_file_digest=? WHERE tenant_id=? AND id=?",
                json,
                Payloads.hash(json),
                lease.tenant(),
                lease.taskId());
          }
          return null;
        });
  }

  private void materialBody(Map<String, Object> task, String request, int ordinal) {
    var material = material(task);
    boolean business = businessTask(task);
    if (material == null && !business) return;
    var wire = Payloads.read(request, com.fasterxml.jackson.databind.JsonNode.class);
    if (material != null) {
      initialFile(task);
      EvaluationMaterials.body(
          material, wire.path("requestBody").asText(), time(task, "report_time").toString());
    }
    // Step N's request contains exactly the confirmed history through N-1. Its own
    // outputs may already be committed on recovery and must not enter this prefix.
    var preceding =
        rows(
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND ordinal<? ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"),
            ordinal);
    EvaluationMaterials.require(ordinal > 0 && preceding.size() == ordinal - 1);
    var body =
        Payloads.read(
            wire.path("requestBody").asText(), com.fasterxml.jackson.databind.JsonNode.class);
    var expected = Payloads.read("[]", com.fasterxml.jackson.databind.node.ArrayNode.class);
    if (business) {
      if (caps(task).contains("present")) {
        var reports = new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy();
        var context = new ReportContext(time(task, "report_time"), List.of());
        String instruction = body.path("messages").path(0).path("content").asText();
        // Recovery retains the original fixed template; no model-selected instruction is allowed.
        EvaluationMaterials.require(
            tools(task).pins().containsKey("compose_report")
                ? new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
                    .instruction(context)
                    .equals(instruction)
                : List.of(reports.instruction(context), reports.currentInstruction(context))
                    .contains(instruction));
        if (!preceding.isEmpty()) {
          String first = s(preceding.getFirst(), "request_snapshot");
          EvaluationMaterials.require(
              Payloads.hash(first).equals(s(preceding.getFirst(), "request_digest")));
          var original = Payloads.read(first, com.fasterxml.jackson.databind.JsonNode.class);
          var originalBody =
              Payloads.read(
                  original.path("requestBody").asText(),
                  com.fasterxml.jackson.databind.JsonNode.class);
          EvaluationMaterials.require(
              instruction.equals(originalBody.path("messages").path(0).path("content").asText()));
        }
        expected.addObject().put("role", "system").put("content", instruction);
      }
      expected.addObject().put("role", "user").put("content", s(task, "goal"));
    } else {
      expected.add(body.path("messages").get(0));
      expected.add(body.path("messages").get(1));
    }
    int position = 0;
    for (var prior : preceding) {
      String response = s(prior, "response_snapshot");
      EvaluationMaterials.require(
          n(prior, "ordinal") == ++position
              && response != null
              && Payloads.hash(response).equals(s(prior, "response_digest")));
      var original = Payloads.read(response, com.fasterxml.jackson.databind.JsonNode.class);
      EvaluationMaterials.require(
          original.path("version").asInt() == 2 && original.path("calls").isArray());
      var assistant = expected.addObject().put("role", "assistant");
      assistant.put("content", original.path("content").asText(""));
      var invocations =
          rows(
              "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? ORDER BY CAST(SUBSTRING_INDEX(slot,':',1) AS UNSIGNED)",
              s(task, "tenant_id"),
              s(task, "id"),
              s(prior, "id"));
      EvaluationMaterials.require(invocations.size() == original.path("calls").size());
      int slot = 0;
      for (var call : original.path("calls")) {
        var invocation = invocations.get(slot);
        String input = s(invocation, "input_snapshot");
        String output = s(invocation, "output_snapshot");
        EvaluationMaterials.require(
            (slot + ":" + call.path("id").asText()).equals(s(invocation, "slot"))
                && call.path("id").asText().equals(s(invocation, "provider_id"))
                && call.path("name").asText().equals(s(invocation, "tool"))
                && call.path("type").asText().equals("function")
                && call.path("arguments").asText().equals(input)
                && Payloads.hash(input).equals(s(invocation, "input_digest"))
                && s(invocation, "state").equals("SUCCEEDED")
                && output != null
                && Payloads.hash(output).equals(s(invocation, "output_digest")));
        var emitted = assistant.withArray("tool_calls").addObject();
        emitted.put("id", call.path("id").asText()).put("type", "function");
        emitted
            .putObject("function")
            .put("name", call.path("name").asText())
            .put("arguments", input);
        slot++;
      }
      for (var invocation : invocations)
        expected
            .addObject()
            .put("role", "tool")
            .put("tool_call_id", s(invocation, "provider_id"))
            .put("content", s(invocation, "output_snapshot"));
    }
    EvaluationMaterials.require(expected.equals(body.path("messages")));
  }

  private String runRejection(Map<String, Object> evaluation) {
    return evaluation != null && !"OPEN".equals(s(evaluation, "state"))
        ? "MODEL_BUDGET_EXCEEDED"
        : null;
  }

  // The dispatch fact is the execution decision, including rows written before this fix.
  // Fixture failures may retry; a paid failed dispatch must retain its original rejection.
  private String modelRejection(Map<String, Object> task) {
    var rejected =
        rows(
            "SELECT code FROM model_dispatch WHERE tenant_id=? AND task_id=? AND (state IN ('BOUND_EXCEEDED','USAGE_UNKNOWN') OR (state='FAILED' AND currency IS NOT NULL)) ORDER BY started_at,id LIMIT 1",
            s(task, "tenant_id"),
            s(task, "id"));
    return rejected.isEmpty() ? null : s(rejected.getFirst(), "code");
  }

  @Override
  public boolean renew(Lease lease) {
    return db.update(
            "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE tenant_id=? AND id=? AND current_attempt_id=? AND worker_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6) AND state IN ('RUNNING','CANCEL_REQUESTED')",
            lease.tenant(),
            lease.taskId(),
            lease.attemptId(),
            lease.worker(),
            lease.fence())
        == 1;
  }

  @Override
  public Optional<Step> checkpoint(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          return rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId())
              .stream()
              .map(
                  row -> {
                    materialBody(task, s(row, "request_snapshot"), (int) n(row, "ordinal"));
                    return step(row);
                  })
              .findFirst();
        });
  }

  private Step step(Map<String, Object> row) {
    if (n(row, "record_version") != 1) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    if (row.get("response_snapshot") != null
        && !Payloads.hash(s(row, "response_snapshot")).equals(s(row, "response_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!Payloads.hash(s(row, "request_snapshot")).equals(s(row, "request_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return new Step(
        s(row, "id"),
        (int) n(row, "ordinal"),
        s(row, "request_snapshot"),
        s(row, "response_snapshot"),
        s(row, "dispatch_id"));
  }

  private ModelSnapshot snapshot(Map<String, Object> task) {
    var snapshot = historicalSnapshot(task);
    models.available(snapshot);
    return snapshot;
  }

  private ModelSnapshot historicalSnapshot(Map<String, Object> task) {
    String value = s(task, "model_snapshot");
    if (value == null) throw new TaskFailure(409, "MODEL_SNAPSHOT_MISSING");
    if (!Payloads.hash(value).equals(s(task, "model_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ModelSnapshot.class);
    return snapshot;
  }

  @Override
  public ModelSnapshot modelSnapshot(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var evaluation = evaluation(lease);
          var task = fenced(lease, true);
          var snapshot = snapshot(task);
          checkEvaluation(task, evaluation, snapshot);
          return snapshot;
        });
  }

  private ToolSnapshot tools(Map<String, Object> task) {
    String value = s(task, "tools_snapshot");
    if (value == null) throw new TaskFailure(409, "PLUGIN_SNAPSHOT_UNAVAILABLE");
    if (!Payloads.hash(value).equals(s(task, "tools_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ToolSnapshot.class);
    if (snapshot.schemaVersion() != 1) throw new TaskFailure(409, "PLUGIN_SNAPSHOT_UNSUPPORTED");
    if (!snapshot.pins().keySet().equals(new java.util.HashSet<>(caps(task)))
        || snapshot.pins().values().stream().mapToLong(PluginPin::installationEpoch).sum()
            != n(task, "installation_epoch")) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return snapshot;
  }

  @Override
  public ToolSnapshot toolSnapshot(Lease lease) {
    return leaseTx(lease, () -> tools(fenced(lease, true)));
  }

  // This is always the first lock when a transaction can mutate evaluation accounting.
  private Map<String, Object> evaluation(Lease lease) {
    return evaluation(lease, true);
  }

  private Map<String, Object> evaluation(Lease lease, boolean admission) {
    String run =
        s(
            one(
                "SELECT evaluation_run_id FROM task WHERE tenant_id=? AND id=?",
                lease.tenant(),
                lease.taskId()),
            "evaluation_run_id");
    return evaluation(
        lease.tenant(), admission ? executionScopes.targetRun(lease.tenant(), run) : run);
  }

  private Map<String, Object> evaluation(String tenant, String run) {
    if (run == null) return null;
    var found =
        rows("SELECT * FROM evaluation_budget WHERE tenant_id=? AND id=? FOR UPDATE", tenant, run);
    if (found.isEmpty()) throw new TaskFailure(503, "MODEL_EVALUATION_UNAVAILABLE");
    return found.getFirst();
  }

  private EvaluationPlans.Plan plan(Map<String, Object> evaluation) {
    if (evaluation == null || evaluation.get("plan_snapshot") == null) return null;
    String json = s(evaluation, "plan_snapshot");
    if (!Payloads.hash(json).equals(s(evaluation, "plan_digest")))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    var stored = Payloads.read(json, EvaluationPlans.Plan.class);
    var canonical = EvaluationPlans.plan(stored.id());
    if (!stored.equals(canonical)
        || n(evaluation, "policy_version") != 2
        || n(evaluation, "max_requests") > canonical.maxRequests()
        || n(evaluation, "max_cost_micros") > canonical.maxCostMicros())
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    if (!rows(
            "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_snapshot IS NOT NULL AND (JSON_UNQUOTE(JSON_EXTRACT(evaluation_snapshot,'$.planDigest'))<>? OR (evaluation_phase='B' AND JSON_UNQUOTE(JSON_EXTRACT(evaluation_snapshot,'$.qualificationDigest'))<>COALESCE(?,''))) LIMIT 1",
            s(evaluation, "tenant_id"),
            s(evaluation, "id"),
            s(evaluation, "plan_digest"),
            s(evaluation, "phase_b_digest"))
        .isEmpty()) throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    return canonical;
  }

  private EvaluationPlans.Binding bindEvaluation(
      Map<String, Object> evaluation, ModelSnapshot model) {
    var selection = models.evaluationSelection();
    var plan = plan(evaluation);
    if (plan == null && selection == null) return null;
    if (plan == null || selection == null || !plan.equals(selection.plan()) || !model.paid())
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    // Same validation as profile admission, including the strictly local test seam.
    models.checkEvaluationModel(plan, model);
    boolean initial =
        rows(
                "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_snapshot IS NOT NULL LIMIT 1",
                s(evaluation, "tenant_id"),
                s(evaluation, "id"))
            .isEmpty();
    if (initial && "OPEN".equals(s(evaluation, "state"))) {
      var tenant = bucket(s(evaluation, "tenant_id"));
      if (n(evaluation, "requests_used") + plan.maxRequests() > n(evaluation, "max_requests")
          || plan.matrixWorstCostMicros()
              > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
          || plan.matrixWorstCostMicros() > n(tenant, "cost_limit") - n(tenant, "cost_held")
          || plan.tenantTokenLimit() > n(tenant, "token_limit") - n(tenant, "token_held")
          || plan.maxRequests() > n(tenant, "model_limit") - n(tenant, "model_used"))
        throw new TaskFailure(429, "EVALUATION_MATRIX_BUDGET_UNAVAILABLE");
    }
    if (model.stream() != List.of("W2", "W4").contains(selection.sample()))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    String qualification = null;
    if (selection.phase().equals("B")) {
      if (!"OPEN".equals(s(evaluation, "state"))
          || !rows(
                  "SELECT r.id FROM reservation r JOIN task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? AND r.state='HELD_UNKNOWN' LIMIT 1",
                  s(evaluation, "tenant_id"),
                  s(evaluation, "id"))
              .isEmpty()) throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
      var q =
          EvaluationPlans.qualify(
              plan, s(evaluation, "phase_b_snapshot"), s(evaluation, "phase_b_digest"));
      if (!q.originalRunId().equals(s(evaluation, "id")) || !q.finalists().contains(model.model()))
        throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
      var screening =
          rows(
              "SELECT evaluation_model,evaluation_sample,state FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_phase='A'",
              s(evaluation, "tenant_id"),
              s(evaluation, "id"));
      for (String candidate : plan.candidates().keySet())
        for (String sample : List.of("W1", "W2")) {
          var match =
              screening.stream()
                  .filter(
                      t ->
                          candidate.equals(s(t, "evaluation_model"))
                              && sample.equals(s(t, "evaluation_sample")))
                  .toList();
          if (match.size() != 1
              || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(match.getFirst(), "state"))
              || q.finalists().contains(candidate)
                  && !"SUCCEEDED".equals(s(match.getFirst(), "state")))
            throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
        }
      if (rows(
              "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_phase='B' LIMIT 1",
              s(evaluation, "tenant_id"),
              s(evaluation, "id"))
          .isEmpty()) {
        long rounds = 12L * q.finalists().size();
        long remainingCost =
            q.finalists().stream()
                .mapToLong(candidate -> 2 * EvaluationPlans.taskCost(plan, candidate, "W3"))
                .sum();
        var tenant = bucket(s(evaluation, "tenant_id"));
        if (n(evaluation, "requests_used") + rounds > n(evaluation, "max_requests")
            || remainingCost > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
            || remainingCost > n(tenant, "cost_limit") - n(tenant, "cost_held")
            || rounds * 1004096 > n(tenant, "token_limit") - n(tenant, "token_held")
            || rounds > n(tenant, "model_limit") - n(tenant, "model_used"))
          throw new TaskFailure(429, "EVALUATION_MATRIX_BUDGET_UNAVAILABLE");
      }
      qualification = s(evaluation, "phase_b_digest");
    }
    return new EvaluationPlans.Binding(
        plan.id(),
        s(evaluation, "plan_digest"),
        selection.phase(),
        selection.sample(),
        model.model(),
        qualification,
        n(evaluation, "max_requests"),
        n(evaluation, "max_cost_micros"));
  }

  private void checkEvaluation(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model) {
    if (businessTask(task)) {
      requireBusiness(s(task, "tenant_id"), evaluation, task, model);
      return;
    }
    var configured = bindEvaluation(evaluation, model);
    if (task.get("evaluation_snapshot") == null) {
      if (configured != null) throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
      return;
    }
    String json = s(task, "evaluation_snapshot");
    if (!Payloads.hash(json).equals(s(task, "evaluation_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var binding = Payloads.read(json, EvaluationPlans.Binding.class);
    if (!binding.equals(configured)
        || !binding.phase().equals(s(task, "evaluation_phase"))
        || !binding.sample().equals(s(task, "evaluation_sample"))
        || !binding.model().equals(s(task, "evaluation_model")))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
  }

  private void admitPlanDispatch(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model, long cost) {
    var plan = plan(evaluation);
    String phase = s(task, "evaluation_phase"), sample = s(task, "evaluation_sample");
    var usage =
        rows(
            "SELECT t.evaluation_phase,t.evaluation_sample,t.evaluation_model,COUNT(*) AS calls,COALESCE(SUM(COALESCE(d.actual_cost,d.reserved_cost)),0) AS cost FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? GROUP BY t.evaluation_phase,t.evaluation_sample,t.evaluation_model",
            s(task, "tenant_id"),
            s(evaluation, "id"));
    long phaseCalls = 0,
        phaseCost = 0,
        modelCalls = 0,
        modelCost = 0,
        phaseModelCalls = 0,
        phaseModelCost = 0,
        sampleCalls = 0;
    for (var row : usage) {
      boolean sameModel = model.model().equals(s(row, "evaluation_model"));
      if (sameModel) {
        modelCalls += n(row, "calls");
        modelCost += n(row, "cost");
      }
      if (phase.equals(s(row, "evaluation_phase"))) {
        phaseCalls += n(row, "calls");
        phaseCost += n(row, "cost");
        if (sameModel) {
          phaseModelCalls += n(row, "calls");
          phaseModelCost += n(row, "cost");
        }
      }
      if (sameModel && sample.equals(s(row, "evaluation_sample"))) sampleCalls += n(row, "calls");
    }
    int modelPhaseLimit = phase.equals("A") ? 13 : 12;
    long phaseLimit = phase.equals("A") ? plan.phaseACostMicros() : plan.phaseBCostMicros();
    if (phaseCalls >= (phase.equals("A") ? 13 * plan.candidates().size() : 24)
        || phaseCost + cost > phaseLimit
        || modelCalls >= 25
        || modelCost + cost > 25 * cost
        || phaseModelCalls >= modelPhaseLimit
        || phaseModelCost + cost > modelPhaseLimit * cost
        || sampleCalls >= (sample.equals("W1") ? 7 : 6)
        || !rows(
                "SELECT d.id FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? AND d.state='DISPATCHED' LIMIT 1",
                s(task, "tenant_id"),
                s(evaluation, "id"))
            .isEmpty()) throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
  }

  @Override
  public Step prepare(Lease lease, String request) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var previous =
              rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId());
          if (!previous.isEmpty() && request.equals(s(previous.getFirst(), "request_snapshot"))) {
            materialBody(task, request, (int) n(previous.getFirst(), "ordinal"));
            return step(previous.getFirst());
          }
          int ordinal = previous.isEmpty() ? 1 : (int) n(previous.getFirst(), "ordinal") + 1;
          materialBody(task, request, ordinal);
          if (!previous.isEmpty()
              && (previous.getFirst().get("response_snapshot") == null
                  || !rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND step_id=? AND state<>'SUCCEEDED'",
                          lease.tenant(),
                          s(previous.getFirst(), "id"))
                      .isEmpty())) throw new TaskFailure(409, "CHECKPOINT_INCOMPLETE");
          String stepId = id();
          db.update(
              "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,model_version) VALUES(?,?,?,?,?,'PREPARED',?,?,?)",
              lease.tenant(),
              stepId,
              lease.taskId(),
              lease.attemptId(),
              ordinal,
              request,
              Payloads.hash(request),
              snapshot(task).model());
          event(task, "prepared:" + stepId, "model.prepared", null);
          return new Step(stepId, ordinal, request, null, null);
        });
  }

  @Override
  public String dispatchModel(Lease lease, String stepId) {
    return leaseTx(
        lease,
        () -> {
          var evaluation = evaluation(lease);
          var task = fenced(lease, true);
          var model = snapshot(task);
          models.admit(model);
          var budget = bucket(lease.tenant());
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  lease.taskId());
          if (step.get("response_snapshot") != null)
            throw new TaskFailure(409, "ALREADY_RESPONDED");
          var wire =
              Payloads.read(
                  s(step, "request_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
          String bodyDigest = Payloads.hash(wire.path("requestBody").asText());
          if (!bodyDigest.equals(wire.path("bodyDigest").asText()))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          if (!ModelProfiles.digest(model).equals(wire.path("configDigest").asText()))
            throw new TaskFailure(409, "MODEL_CONFIG_UNAVAILABLE");
          checkEvaluation(task, evaluation, model);
          materialBody(task, s(step, "request_snapshot"), (int) n(step, "ordinal"));
          models.checkInput(model, wire.path("requestBody").asText());
          long upper =
              model.paid()
                  ? model.price().inputTokenUpper() + model.maxOutputTokens()
                  : s(step, "request_snapshot")
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                          .length
                      + 4096L;
          long cost =
              model.paid()
                  ? ModelCosts.amount(
                      model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                  : 0;
          if (model.paid()
              && (evaluation == null
                  || !"OPEN".equals(s(evaluation, "state"))
                  || n(evaluation, "requests_used") >= n(evaluation, "max_requests")
                  || cost > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
                  || cost > n(task, "max_cost_micros") - n(task, "cost_held")
                  || cost > n(budget, "cost_limit") - n(budget, "cost_held")))
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          if (n(step, "dispatches") >= (model.paid() ? 1 : 2)
              || n(task, "models_used") >= n(task, "max_models")
              || n(budget, "model_used") >= n(budget, "model_limit")
              || n(budget, "token_held") + upper > n(budget, "token_limit")
              || n(task, "token_held") + upper > n(task, "max_tokens"))
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          if (task.get("evaluation_snapshot") != null)
            admitPlanDispatch(task, evaluation, model, cost);
          String dispatch = id();
          if (model.paid()) {
            db.update(
                "UPDATE evaluation_budget SET requests_used=requests_used+1,cost_held=cost_held+? WHERE tenant_id=? AND id=?",
                cost,
                lease.tenant(),
                s(evaluation, "id"));
            db.update(
                "UPDATE tenant_budget SET cost_held=cost_held+? WHERE tenant_id=?",
                cost,
                lease.tenant());
            db.update(
                "UPDATE task SET cost_held=cost_held+? WHERE tenant_id=? AND id=?",
                cost,
                lease.tenant(),
                lease.taskId());
            reserve(
                lease.tenant(),
                lease.taskId(),
                dispatch + ":cost",
                dispatch,
                "MODEL_COST",
                cost,
                "HELD_UNKNOWN",
                "UNKNOWN");
          }
          db.update(
              "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state,price_version,currency,reserved_cost) VALUES(?,?,?,?,?,?,'DISPATCHED',?,?,?)",
              lease.tenant(),
              dispatch,
              lease.taskId(),
              stepId,
              s(task, "model_digest"),
              bodyDigest,
              model.price() == null ? null : model.price().version(),
              model.paid() ? "CNY" : null,
              model.paid() ? cost : null);
          db.update(
              "UPDATE tenant_budget SET model_used=model_used+1,token_held=token_held+? WHERE tenant_id=?",
              upper,
              lease.tenant());
          db.update(
              "UPDATE task SET models_used=models_used+1,token_held=token_held+? WHERE tenant_id=? AND id=?",
              upper,
              lease.tenant(),
              lease.taskId());
          if (step.get("dispatch_id") != null)
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND dispatch_id=?",
                lease.tenant(),
                s(step, "dispatch_id"));
          reserve(
              lease.tenant(),
              lease.taskId(),
              dispatch,
              dispatch,
              "MODEL",
              upper,
              "RESERVED",
              "UNKNOWN");
          db.update(
              "UPDATE step SET state='RUNNING',dispatch_id=?,dispatches=dispatches+1 WHERE tenant_id=? AND id=?",
              dispatch,
              lease.tenant(),
              stepId);
          event(task, "model:" + dispatch, "model.dispatched", null);
          return dispatch;
        });
  }

  @Override
  public void modelResponded(
      Lease lease,
      String stepId,
      String dispatch,
      String response,
      List<Call> calls,
      Integer tokens,
      ModelUsage usage) {
    tx(
        () -> {
          var evaluation = evaluation(lease, false);
          var task = fenced(lease, false);
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  lease.taskId());
          if (!dispatch.equals(s(step, "dispatch_id")) || step.get("response_snapshot") != null)
            throw new TaskFailure(409, "DISPATCH_REJECTED");
          if (settleCost(lease, task, evaluation, dispatch, usage)) {
            String rejection =
                s(
                    one(
                        "SELECT code FROM model_dispatch WHERE tenant_id=? AND id=?",
                        lease.tenant(),
                        dispatch),
                    "code");
            db.update(
                "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId);
            event(task, "response:" + stepId, "model.rejected", rejection);
            return null;
          }
          db.update(
              "UPDATE step SET state='RESPONDED',response_snapshot=?,response_digest=? WHERE tenant_id=? AND id=?",
              response,
              Payloads.hash(response),
              lease.tenant(),
              stepId);
          long upper =
              n(
                  one(
                      "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                      lease.tenant(),
                      dispatch),
                  "amount");
          if (tokens != null && tokens >= 0 && tokens <= upper) {
            db.update(
                "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
                upper - tokens,
                lease.tenant());
            db.update(
                "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
                upper - tokens,
                lease.tenant(),
                lease.taskId());
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
                tokens,
                lease.tenant(),
                dispatch);
          } else
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                dispatch);
          try {
            String rejection = modelRejection(task);
            if (rejection == null) rejection = runRejection(evaluation);
            if (rejection != null) throw new TaskFailure(409, rejection);
            requireScope(lease.tenant(), evaluation, task, null);
          } catch (TaskFailure rejected) {
            db.update(
                "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId);
            db.update(
                "UPDATE model_dispatch SET code=? WHERE tenant_id=? AND id=?",
                rejected.code(),
                lease.tenant(),
                dispatch);
            event(task, "response:" + stepId, "model.rejected", rejected.code());
            return null; // Audit/usage committed; no executable tool intents from this response.
          }
          int ordinal =
              db.queryForObject(
                  "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND attempt_id=?",
                  Integer.class,
                  lease.tenant(),
                  lease.attemptId());
          for (Call call : calls) {
            if (!lease.capabilities().contains(call.tool()))
              throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
            var plugin = tools(task).pins().get(call.tool());
            db.update(
                "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
                lease.tenant(),
                id(),
                lease.taskId(),
                stepId,
                lease.attemptId(),
                ++ordinal,
                call.slot(),
                call.providerId(),
                id(),
                call.tool(),
                plugin.version(),
                plugin.digest(),
                plugin.installationEpoch(),
                call.input(),
                Payloads.hash(call.input()));
          }
          event(task, "response:" + stepId, "model.responded", null);
          return null;
        });
    var outcome =
        one(
            "SELECT state,code FROM model_dispatch WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch);
    if (List.of("BOUND_EXCEEDED", "USAGE_UNKNOWN").contains(s(outcome, "state")))
      throw new TaskFailure(409, s(outcome, "code"));
    if (s(outcome, "code") != null) throw new TaskFailure(409, s(outcome, "code"));
  }

  private ModelSnapshot settlementModel(Lease lease, Map<String, Object> task, String dispatch) {
    var row =
        one(
            "SELECT * FROM model_dispatch WHERE tenant_id=? AND id=? AND task_id=?",
            lease.tenant(),
            dispatch,
            lease.taskId());
    if (!"DISPATCHED".equals(s(row, "state"))) throw new TaskFailure(409, "DISPATCH_REJECTED");
    try {
      var model = historicalSnapshot(task);
      if (!s(task, "model_digest").equals(s(row, "config_digest"))
          || !ModelProfiles.digest(model).equals(s(row, "config_digest"))
          || model.paid()
              && (model.price() == null
                  || !model.price().version().equals(s(row, "price_version"))
                  || !model.price().currency().equals(s(row, "currency"))
                  || ModelCosts.amount(
                          model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                      != n(row, "reserved_cost")))
        throw new IllegalArgumentException("Historical price binding mismatch");
      return model;
    } catch (TaskFailure | IllegalArgumentException corrupt) {
      db.update(
          "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN',cost_quality='UNKNOWN' WHERE tenant_id=? AND dispatch_id=? AND state='RESERVED'",
          lease.tenant(),
          dispatch);
      if (task.get("evaluation_run_id") != null)
        db.update(
            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            s(task, "evaluation_run_id"));
      throw new TaskFailure(409, "MODEL_SETTLEMENT_PROOF_INVALID");
    }
  }

  private boolean settleCost(
      Lease lease,
      Map<String, Object> task,
      Map<String, Object> evaluation,
      String dispatch,
      ModelUsage usage) {
    var model = settlementModel(lease, task, dispatch);
    Long actual = null;
    boolean exceeded = false;
    boolean unknown = model.paid() && (usage.inputTokens() == null || usage.outputTokens() == null);
    if (model.paid()) {
      exceeded =
          usage.inputTokens() != null && usage.inputTokens() > model.price().inputTokenUpper()
              || usage.outputTokens() != null && usage.outputTokens() > model.maxOutputTokens()
              || usage.totalTokens() != null
                  && java.math.BigInteger.valueOf(usage.totalTokens())
                          .compareTo(
                              java.math.BigInteger.valueOf(model.price().inputTokenUpper())
                                  .add(java.math.BigInteger.valueOf(model.maxOutputTokens())))
                      > 0;
      if (usage.inputTokens() != null && usage.outputTokens() != null) {
        try {
          actual = ModelCosts.amount(model.price(), usage.inputTokens(), usage.outputTokens());
        } catch (ArithmeticException invalidProviderCost) {
          // Retain raw usage; an unrepresentable cost is UNKNOWN, never clamped.
          exceeded = true;
        }
      }
      long reserved =
          n(
              one(
                  "SELECT reserved_cost FROM model_dispatch WHERE tenant_id=? AND id=?",
                  lease.tenant(),
                  dispatch),
              "reserved_cost");
      long delta = actual == null ? 0 : actual - reserved;
      exceeded = exceeded || actual != null && actual > reserved;
      db.update(
          "UPDATE evaluation_budget SET cost_held=cost_held+?,state=IF(?,'STOPPED',state) WHERE tenant_id=? AND id=?",
          delta,
          exceeded || unknown,
          lease.tenant(),
          s(evaluation, "id"));
      db.update(
          "UPDATE tenant_budget SET cost_held=cost_held+? WHERE tenant_id=?",
          delta,
          lease.tenant());
      db.update(
          "UPDATE task SET cost_held=cost_held+? WHERE tenant_id=? AND id=?",
          delta,
          lease.tenant(),
          lease.taskId());
      if (actual != null)
        db.update(
            "UPDATE reservation SET actual=?,state='SETTLED',usage_quality='KNOWN',cost_quality='KNOWN' WHERE tenant_id=? AND id=?",
            actual,
            lease.tenant(),
            dispatch + ":cost");
      else
        db.update(
            "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch + ":cost");
    }
    db.update(
        "UPDATE model_dispatch SET state=?,code=?,ended_at=CURRENT_TIMESTAMP(6),input_tokens=?,output_tokens=?,total_tokens=?,usage_snapshot=?,actual_cost=?,cost_quality=? WHERE tenant_id=? AND id=?",
        exceeded ? "BOUND_EXCEEDED" : unknown ? "USAGE_UNKNOWN" : "RESPONDED",
        exceeded ? "MODEL_COST_BOUND_EXCEEDED" : unknown ? "MODEL_USAGE_UNKNOWN" : null,
        usage.inputTokens(),
        usage.outputTokens(),
        usage.totalTokens(),
        Payloads.json(usage),
        actual,
        actual == null ? "UNKNOWN" : "KNOWN",
        lease.tenant(),
        dispatch);
    // Preserve the actual fact and stop subsequent dispatches even if the provider violates its
    // bound.
    if (exceeded)
      db.update(
          "UPDATE task SET max_models=models_used WHERE tenant_id=? AND id=?",
          lease.tenant(),
          lease.taskId());
    return exceeded || unknown;
  }

  @Override
  public void modelFailed(Lease lease, String dispatch, String code, ModelUsage usage) {
    tx(
        () -> {
          var evaluation = evaluation(lease, false);
          var task = fenced(lease, false);
          var model = settlementModel(lease, task, dispatch);
          if (usage != null) settleCost(lease, task, evaluation, dispatch, usage);
          else if (model.paid())
            settleCost(
                lease,
                task,
                evaluation,
                dispatch,
                new ModelUsage(null, null, null, null, null, null, null, null));
          db.update(
              "UPDATE model_dispatch SET state='FAILED',code=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state IN ('DISPATCHED','RESPONDED')",
              code,
              lease.tenant(),
              dispatch,
              lease.taskId());
          db.update(
              "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id=? AND dispatch_id=? AND category='MODEL'",
              lease.tenant(),
              dispatch);
          return null;
        });
  }

  @Override
  public Invocation admit(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var budget = bucket(lease.tenant());
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND step_id=? AND slot=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  call.slot(),
                  lease.taskId());
          if (!Payloads.hash(call.input()).equals(s(invocation, "input_digest"))
              || !call.tool().equals(s(invocation, "tool"))
              || !call.providerId().equals(s(invocation, "provider_id")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var material = material(task);
          if (material != null) {
            initialFile(task);
            var input = Payloads.read(call.input(), com.fasterxml.jackson.databind.JsonNode.class);
            if (call.tool().equals("web_fetch"))
              EvaluationMaterials.require(
                  material.sources().containsKey(input.path("url").asText()));
            if (call.tool().equals("write"))
              EvaluationMaterials.require(
                  !rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND tool='read' AND state='SUCCEEDED' LIMIT 1",
                          lease.tenant(),
                          lease.taskId())
                      .isEmpty());
            if (call.tool().equals("read") || call.tool().equals("write"))
              EvaluationMaterials.require(
                  material.target().equals(input.path("file_path").asText()));
            if (call.tool().equals("present"))
              EvaluationMaterials.require(
                  input.path("files").size() == 1
                      && material
                          .target()
                          .equals(input.path("files").get(0).path("path").asText()));
          }
          String invocationId = s(invocation, "id");
          var pinned = tools(task).pins().get(call.tool());
          var installation =
              one(
                  "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                  lease.tenant(),
                  call.tool());
          if (!pluginVersion.equals(s(invocation, "plugin_version"))
              || !pluginDigest.equals(s(invocation, "plugin_digest"))
              || !pluginVersion.equals(pinned.version())
              || !pluginDigest.equals(pinned.digest())
              || n(invocation, "installation_epoch") != pinned.installationEpoch()
              || !pluginVersion.equals(s(installation, "plugin_version"))
              || !pluginDigest.equals(s(installation, "plugin_digest")))
            throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          if ("SUCCEEDED".equals(s(invocation, "state"))) {
            if (!Payloads.hash(s(invocation, "output_snapshot"))
                .equals(s(invocation, "output_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            return new Invocation(
                invocationId,
                s(invocation, "effect_intent_id"),
                "SUCCEEDED",
                s(invocation, "output_snapshot"));
          }
          if (!"PENDING".equals(s(invocation, "state")))
            throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          db.update(
              "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              lease.taskId());
          db.update(
              "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "RESERVED",
              "KNOWN");
          db.update(
              "UPDATE invocation SET state='RUNNING' WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "intent:" + invocationId, "tool.dispatched", null);
          return new Invocation(invocationId, s(invocation, "effect_intent_id"), "RUNNING", null);
        });
  }

  @Override
  public void observeWebFetch(Lease lease, String invocationId, WebFetchObservation observation) {
    tx(
        () -> {
          if (observation.cancellation() != null) {
            observeWebCancellation(lease, invocationId, observation);
            return null;
          }
          var task = fenced(lease, false);
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (!"web_fetch".equals(invocation.get("tool"))
              || !"0.3.0".equals(invocation.get("plugin_version"))
              || !"RUNNING".equals(invocation.get("state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          boolean failed = observation.failure() != WebFetchObservation.Failure.NONE;
          event(
              task,
              (failed ? "web-failure:" : "web-phase:") + invocationId + ":" + observation.phase(),
              failed ? "web.fetch_failed" : "web.fetch_phase",
              observation.phase() + "_" + observation.failure());
          return null;
        });
  }

  /** Diagnostic append only: an expired original fence cannot authorize execution or settlement. */
  private void observeWebCancellation(
      Lease lease, String invocationId, WebFetchObservation observation) {
    var cancellation = observation.cancellation();
    var task =
        one(
            "SELECT * FROM task WHERE tenant_id=? AND id=? FOR UPDATE",
            lease.tenant(),
            lease.taskId());
    var bindings =
        rows(
            "SELECT i.id FROM invocation i JOIN task_attempt a ON a.tenant_id=i.tenant_id AND a.task_id=i.task_id AND a.id=i.attempt_id WHERE i.tenant_id=? AND i.task_id=? AND i.id=? AND i.attempt_id=? AND a.worker_id=? AND a.fence=? AND i.tool='web_fetch' AND i.plugin_version='0.3.0' AND i.state IN ('RUNNING','OUTCOME_UNKNOWN')",
            lease.tenant(),
            lease.taskId(),
            invocationId,
            lease.attemptId(),
            lease.worker(),
            lease.fence());
    if (bindings.size() != 1) throw new TaskFailure(409, "WEB_OBSERVATION_BINDING_REJECTED");
    String fact = "web-cancel:" + invocationId + ":" + lease.fence();
    if (!rows(
            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
            lease.tenant(),
            lease.taskId(),
            fact)
        .isEmpty()) return;
    event(task, fact, "web.fetch_cancelled", cancellation.phase() + "_" + cancellation.reason());
    event(
        task,
        fact + ":elapsed",
        "web.cancel_elapsed_ms",
        Long.toString(cancellation.elapsedMillis()));
    event(
        task,
        fact + ":checkpoint",
        "web.checkpoint_rejected",
        cancellation.checkpointCode() + ":" + cancellation.checkpointMillis());
    event(
        task,
        "web-failure:" + invocationId + ":" + observation.phase(),
        "web.fetch_failed",
        observation.phase() + "_" + observation.failure());
  }

  @Override
  public void preserveWebFetchResult(Lease lease, String invocationId, String output) {
    tx(
        () -> {
          var task = fenced(lease, false);
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (!"RUNNING".equals(invocation.get("state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocation.get("step_id"));
          String digest = WebFetchReceipts.digest(task, step, invocation, output);
          var existing =
              rows(
                  "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                  lease.tenant(),
                  lease.taskId(),
                  "web-result:" + invocationId);
          if (!existing.isEmpty()) {
            if (!digest.equals(existing.getFirst().get("code"))
                || !output.equals(invocation.get("output_snapshot"))
                || !Payloads.hash(output).equals(invocation.get("output_digest")))
              throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
            return null;
          }
          if (invocation.get("output_snapshot") != null || invocation.get("output_digest") != null)
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
          db.update(
              "UPDATE invocation SET output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND id=?",
              output,
              Payloads.hash(output),
              lease.tenant(),
              lease.taskId(),
              invocationId);
          event(task, "web-result:" + invocationId, "web.result_preserved", digest);
          return null;
        });
  }

  @Override
  public void toolSucceeded(Lease lease, String invocationId, String output) {
    boolean rejected =
        tx(
            () -> {
              var task = fenced(lease, false);
              var producer =
                  one(
                      "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                      lease.tenant(),
                      lease.taskId(),
                      invocationId);
              if ("present".equals(s(producer, "tool")))
                throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
              var receipts =
                  rows(
                      "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                      lease.tenant(),
                      lease.taskId(),
                      "web-result:" + invocationId);
              if (!receipts.isEmpty()) {
                var step =
                    one(
                        "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                        lease.tenant(),
                        lease.taskId(),
                        producer.get("step_id"));
                if (!output.equals(producer.get("output_snapshot"))
                    || !Payloads.hash(output).equals(producer.get("output_digest"))
                    || !WebFetchReceipts.digest(task, step, producer, output)
                        .equals(receipts.getFirst().get("code")))
                  throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
              }
              if (db.update(
                      "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                      output,
                      Payloads.hash(output),
                      lease.tenant(),
                      invocationId,
                      lease.taskId())
                  != 1) throw new TaskFailure(409, "INVOCATION_STATE");
              db.update(
                  "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
                  lease.tenant(),
                  invocationId);
              event(task, "result:" + invocationId, "tool.succeeded", null);
              try {
                checkMaterials(task);
                return false;
              } catch (TaskFailure failure) {
                if (!failure.code().equals("EVALUATION_MATERIAL_MISMATCH")) throw failure;
                // Preserve the confirmed tool result and settlement, and stop the shared run
                // atomically. A crash before WorkerLoop.stop cannot open another admission.
                db.update(
                    "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    s(task, "evaluation_run_id"));
                return true;
              }
            });
    if (rejected) throw new TaskFailure(409, "EVALUATION_MATERIAL_MISMATCH");
  }

  @Override
  public void uncertain(Lease lease, String invocationId) {
    tx(
        () -> {
          var task = fenced(lease, false);
          db.update(
              "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
              lease.tenant(),
              invocationId,
              lease.taskId());
          db.update(
              "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=? AND state='RESERVED'",
              lease.tenant(),
              invocationId);
          terminal(task, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
          return null;
        });
  }

  private boolean publicReport(Map<String, Object> task) {
    var pins = tools(task).pins();
    var pin = pins.get("web_fetch");
    return pin != null && "0.3.0".equals(pin.version())
        || pins.keySet().stream()
            .anyMatch(name -> List.of("read", "write", "present", "compose_report").contains(name));
  }

  @Override
  public ReportContext reportContext(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (!publicReport(task) || time(task, "report_time") == null)
            throw new TaskFailure(409, "REPORT_METADATA_MISSING");
          return reportContext(task);
        });
  }

  private ReportContext reportContext(Map<String, Object> task) {
    boolean composed = tools(task).pins().containsKey("compose_report");
    var fetchPin = tools(task).pins().get("web_fetch");
    var sources =
        rows(
                "SELECT id,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='web_fetch' AND state='SUCCEEDED'"
                    + (composed ? "" : " AND plugin_version='0.3.0'")
                    + " ORDER BY ordinal",
                s(task, "tenant_id"),
                s(task, "id"))
            .stream()
            .map(
                row -> {
                  if (composed
                      && (fetchPin == null
                          || !"0.3.0".equals(fetchPin.version())
                          || !fetchPin.version().equals(s(row, "plugin_version"))
                          || !fetchPin.digest().equals(s(row, "plugin_digest"))))
                    throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
                  return new Source(
                      s(row, "id"), s(row, "output_snapshot"), s(row, "output_digest"));
                })
            .toList();
    return new ReportContext(time(task, "report_time"), sources);
  }

  private void requirePresent(Lease lease, Map<String, Object> task, String invocationId) {
    if (!publicReport(task)) throw new TaskFailure(409, "REPORT_POLICY_REQUIRED");
    var invocation =
        one(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
            lease.tenant(),
            lease.taskId(),
            invocationId);
    if (!"present".equals(s(invocation, "tool")) || !"RUNNING".equals(s(invocation, "state")))
      throw new TaskFailure(409, "INVOCATION_STATE");
  }

  @Override
  public List<Upload> stageReports(Lease lease, String invocationId, List<ReportFile> files) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          requirePresent(lease, task, invocationId);
          if (files.isEmpty()
              || files.size() > 4
              || files.stream().map(ReportFile::path).distinct().count() != files.size())
            throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          var input =
              Payloads.read(
                  s(
                      one(
                          "SELECT input_snapshot FROM invocation WHERE tenant_id=? AND id=?",
                          lease.tenant(),
                          invocationId),
                      "input_snapshot"),
                  com.fasterxml.jackson.databind.JsonNode.class);
          var paths = new java.util.ArrayList<String>();
          input.path("files").forEach(file -> paths.add(file.path("path").asText()));
          if (!paths.equals(files.stream().map(ReportFile::path).toList()))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var result = new java.util.ArrayList<Upload>();
          for (var file : files) {
            requireComposition(task, file);
            if (file.version() < 1
                || file.size() < 1
                || file.size() > 1048576
                || !file.sha256().matches("[a-f0-9]{64}")
                || !"text/markdown; charset=utf-8".equals(file.mediaType()))
              throw new TaskFailure(400, "REPORT_INVALID");
            String receipt = Payloads.json(file), digest = Payloads.hash(receipt);
            var old =
                rows(
                    "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=? AND receipt_digest=?",
                    lease.tenant(),
                    lease.taskId(),
                    invocationId,
                    digest);
            if (!old.isEmpty()) {
              var a = old.getFirst();
              if (!receipt.equals(s(a, "file_receipt")))
                throw new TaskFailure(409, "ARTIFACT_MISMATCH");
              result.add(
                  new Upload(
                      s(a, "id"),
                      s(a, "upload_id"),
                      s(a, "state"),
                      file.sha256(),
                      file.size(),
                      s(a, "snapshot_key")));
              continue;
            }
            if (!rows(
                    "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation<>?",
                    lease.tenant(),
                    lease.taskId(),
                    invocationId)
                .isEmpty()) throw new TaskFailure(409, "REPORT_ALREADY_STAGED");
            var budget = bucket(lease.tenant());
            if (n(budget, "storage_held") + file.size() > n(budget, "storage_limit"))
              throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            String artifact = id(), upload = id();
            db.update(
                "INSERT INTO artifact(tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,sha256,size,media_type,file_receipt,receipt_digest) VALUES(?,?,?,?,?,?,'STAGING',?,?,?,?,?)",
                lease.tenant(),
                artifact,
                lease.taskId(),
                invocationId,
                upload,
                lease.fence(),
                file.sha256(),
                file.size(),
                file.mediaType(),
                receipt,
                digest);
            db.update(
                "UPDATE tenant_budget SET storage_held=storage_held+? WHERE tenant_id=?",
                file.size(),
                lease.tenant());
            reserve(
                lease.tenant(),
                lease.taskId(),
                artifact,
                null,
                "STORAGE",
                file.size(),
                "RESERVED",
                "KNOWN");
            event(task, "staging:" + artifact, "artifact.staging", null);
            result.add(new Upload(artifact, upload, "STAGING", file.sha256(), file.size(), null));
          }
          if (rows(
                      "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=?",
                      lease.tenant(),
                      lease.taskId(),
                      invocationId)
                  .size()
              != files.size()) throw new TaskFailure(409, "ARTIFACT_MISMATCH");
          return List.copyOf(result);
        });
  }

  private void requireComposition(Map<String, Object> task, ReportFile file) {
    var pin = tools(task).pins().get("compose_report");
    if (pin == null) return;
    var confirmed =
        rows(
            "SELECT input_snapshot,input_digest,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='compose_report' AND state='SUCCEEDED' ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"));
    for (var row : confirmed) {
      String input = s(row, "input_snapshot"), output = s(row, "output_snapshot");
      if (!Payloads.hash(input).equals(s(row, "input_digest"))
          || output == null
          || !Payloads.hash(output).equals(s(row, "output_digest"))
          || !pin.version().equals(s(row, "plugin_version"))
          || !pin.digest().equals(s(row, "plugin_digest")))
        throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
      if (new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
          .matches(input, output, file, reportContext(task))) return;
    }
    throw new TaskFailure(409, "REPORT_COMPOSITION_REQUIRED");
  }

  @Override
  public void presentSucceeded(
      Lease lease, String invocationId, List<Publication> publications, String output) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          requirePresent(lease, task, invocationId);
          var artifacts =
              rows(
                  "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (artifacts.size() != publications.size()
              || artifacts.isEmpty()
              || publications.stream().map(Publication::artifactId).distinct().count()
                  != publications.size()) throw new TaskFailure(409, "ARTIFACT_MISMATCH");
          var result =
              Payloads.read(output, com.fasterxml.jackson.databind.JsonNode.class).path("files");
          if (!result.isArray() || result.size() != publications.size())
            throw new TaskFailure(409, "TOOL_OUTPUT_INVALID");
          for (int index = 0; index < publications.size(); index++) {
            var pub = publications.get(index);
            var a =
                artifacts.stream()
                    .filter(row -> pub.artifactId().equals(s(row, "id")))
                    .findFirst()
                    .orElseThrow(() -> new TaskFailure(409, "ARTIFACT_MISMATCH"));
            var receipt = Payloads.read(s(a, "file_receipt"), ReportFile.class);
            var item = result.get(index);
            if (!"STAGING".equals(s(a, "state"))
                || !pub.key().equals(s(a, "upload_id") + "-" + s(a, "sha256"))
                || !Payloads.hash(s(a, "file_receipt")).equals(s(a, "receipt_digest"))
                || !pub.artifactId().equals(item.path("artifactId").asText())
                || !receipt.path().equals(item.path("path").asText())
                || receipt.version() != item.path("version").asLong()
                || !receipt.sha256().equals(item.path("sha256").asText())
                || receipt.size() != item.path("size").asLong()
                || !receipt.mediaType().equals(item.path("mediaType").asText()))
              throw new TaskFailure(409, "ARTIFACT_MISMATCH");
            db.update(
                "UPDATE artifact SET state='PUBLISHED',snapshot_key=?,production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                pub.key(),
                lease.fence(),
                lease.tenant(),
                pub.artifactId());
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=amount WHERE tenant_id=? AND id=?",
                lease.tenant(),
                pub.artifactId());
            event(task, "published:" + pub.artifactId(), "artifact.created", null);
          }
          db.update(
              "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              output,
              Payloads.hash(output),
              lease.tenant(),
              invocationId);
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "result:" + invocationId, "tool.succeeded", null);
          return null;
        });
  }

  @Override
  public void toolFailed(Lease lease, String invocationId, String code) {
    tx(
        () -> {
          var task = fenced(lease, false);
          if (db.update(
                  "UPDATE invocation SET state='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                  lease.tenant(),
                  invocationId,
                  lease.taskId())
              != 1) throw new TaskFailure(409, "INVOCATION_STATE");
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "failure:" + invocationId, "tool.failed", code);
          return null;
        });
  }

  @Override
  public Upload stage(Lease lease, String hash, long size) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (publicReport(task)) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var budget = bucket(lease.tenant());
          var existing =
              rows(
                  "SELECT * FROM artifact WHERE tenant_id=? AND task_id=?",
                  lease.tenant(),
                  lease.taskId());
          if (!existing.isEmpty()) {
            var a = existing.getFirst();
            if (!hash.equals(s(a, "sha256")) || size != n(a, "size"))
              throw new TaskFailure(409, "ARTIFACT_MISMATCH");
            return new Upload(
                s(a, "id"), s(a, "upload_id"), s(a, "state"), hash, size, s(a, "snapshot_key"));
          }
          String producer =
              s(
                  one(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state='SUCCEEDED' ORDER BY ordinal DESC LIMIT 1",
                      lease.tenant(),
                      lease.taskId()),
                  "id");
          if (size > 1048576 || n(budget, "storage_held") + size > n(budget, "storage_limit"))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String artifact = id(), upload = id();
          db.update(
              "INSERT INTO artifact(tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,sha256,size) VALUES(?,?,?,?,?,?,'STAGING',?,?)",
              lease.tenant(),
              artifact,
              lease.taskId(),
              producer,
              upload,
              lease.fence(),
              hash,
              size);
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held+? WHERE tenant_id=?",
              size,
              lease.tenant());
          reserve(
              lease.tenant(), lease.taskId(), artifact, null, "STORAGE", size, "RESERVED", "KNOWN");
          event(task, "staging:" + artifact, "artifact.staging", null);
          return new Upload(artifact, upload, "STAGING", hash, size, null);
        });
  }

  @Override
  public void verified(Lease lease, String artifactId, String key) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          db.update(
              "UPDATE artifact SET state='VERIFIED',snapshot_key=?,production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='STAGING'",
              key,
              lease.fence(),
              lease.tenant(),
              artifactId,
              lease.taskId());
          event(task, "verified:" + artifactId, "artifact.verified", null);
          return null;
        });
  }

  @Override
  public void publish(Lease lease, String artifactId) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (publicReport(task)) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var artifact =
              one(
                  "SELECT * FROM artifact WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  artifactId,
                  lease.taskId());
          if ("PUBLISHED".equals(s(artifact, "state"))) return null;
          if (!"VERIFIED".equals(s(artifact, "state")) || artifact.get("snapshot_key") == null)
            throw new TaskFailure(409, "ARTIFACT_UNVERIFIED");
          db.update(
              "UPDATE artifact SET state='PUBLISHED',production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              lease.fence(),
              lease.tenant(),
              artifactId);
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=amount WHERE tenant_id=? AND id=?",
              lease.tenant(),
              artifactId);
          event(task, "published:" + artifactId, "artifact.created", null);
          return null;
        });
  }

  @Override
  public void finish(Lease lease, String summary) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (publicReport(task)
              && rows(
                      "SELECT a.id FROM artifact a JOIN invocation i ON i.tenant_id=a.tenant_id AND i.id=a.producer_invocation AND i.task_id=a.task_id WHERE a.tenant_id=? AND a.task_id=? AND a.state='PUBLISHED' AND a.file_receipt IS NOT NULL AND i.tool='present' AND i.state='SUCCEEDED'",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty()) throw new TaskFailure(409, "REPORT_NOT_PUBLISHED");
          if (!rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state<>'SUCCEEDED'",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty()
              || !rows(
                      "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND state<>'PUBLISHED'",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty()) throw new TaskFailure(409, "UNRESOLVED_FACTS");
          db.update(
              "UPDATE task SET summary=? WHERE tenant_id=? AND id=?",
              summary,
              lease.tenant(),
              lease.taskId());
          terminal(task, "SUCCEEDED", null);
          return null;
        });
  }

  @Override
  public void stop(Lease lease, String code) {
    tx(
        () -> {
          var task = fenced(lease, false);
          if (code.equals("EVALUATION_MATERIAL_MISMATCH") && task.get("evaluation_run_id") != null)
            db.update(
                "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                s(task, "evaluation_run_id"));
          boolean pendingEffect =
              !rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty();
          if (pendingEffect
              || List.of("SNAPSHOT_CORRUPT", "MODEL_SETTLEMENT_PROOF_INVALID").contains(code))
            terminal(task, "NEEDS_RECONCILIATION", code);
          else
            terminal(task, task.get("cancel_requested_at") != null ? "CANCELLED" : "FAILED", code);
          return null;
        });
  }

  private void terminal(Map<String, Object> task, String state, String code) {
    if ("CANCELLED".equals(state) && !writersQuiescent(task)) return;
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    db.update(
        "UPDATE task SET state=?,code=? WHERE tenant_id=? AND id=?", state, code, tenant, taskId);
    if ("NEEDS_RECONCILIATION".equals(state)) {
      db.update(
          "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND task_id=? AND state='RUNNING'",
          tenant,
          taskId);
      db.update(
          "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND task_id=? AND category='TOOL' AND state='RESERVED'",
          tenant,
          taskId);
    }
    if (!"NEEDS_RECONCILIATION".equals(state)) {
      int queued =
          db.update(
              "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
              tenant,
              taskId + ":queue");
      db.update("UPDATE tenant_budget SET queued=queued-? WHERE tenant_id=?", queued, tenant);
    }
    if (writersQuiescent(task)) releaseRun(task);
    db.update(
        "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND task_id=? AND category='MODEL' AND state='RESERVED'",
        tenant,
        taskId);
    db.update(
        "UPDATE task_attempt SET state=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='ACTIVE'",
        "SUCCEEDED".equals(state) ? "SUCCEEDED" : "STOPPED",
        tenant,
        s(task, "current_attempt_id"));
    event(
        task,
        "terminal",
        "SUCCEEDED".equals(state)
            ? "task.completed"
            : "task." + state.toLowerCase(java.util.Locale.ROOT),
        code);
  }

  @Override
  public void registerWorker(String worker, String host, long pid, long startTicks) {
    db.update(
        "INSERT INTO worker_process(id,host_id,pid,start_ticks) VALUES(?,?,?,?)",
        worker,
        host,
        pid,
        startTicks);
  }

  @Override
  public List<WorkerProcess> processes(String host) {
    return rows(
            "SELECT w.*,EXISTS(SELECT 1 FROM task_attempt a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id WHERE a.worker_id=w.id AND a.quiescent=FALSE AND t.cancel_requested_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND) AS overdue FROM worker_process w WHERE host_id=? AND stopped=FALSE ORDER BY id LIMIT 64",
            host)
        .stream()
        .map(
            w ->
                new WorkerProcess(
                    s(w, "id"), n(w, "pid"), n(w, "start_ticks"), n(w, "overdue") == 1))
        .toList();
  }

  private void releaseRun(Map<String, Object> task) {
    int released =
        db.update(
            "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
            s(task, "tenant_id"),
            s(task, "id") + ":run");
    if (released == 1)
      db.update(
          "UPDATE tenant_budget SET running=running-1 WHERE tenant_id=?", s(task, "tenant_id"));
  }

  private boolean writersQuiescent(Map<String, Object> task) {
    // Every attempt matters, including expired attempts fenced out by a later worker.
    // Missing receipts and process-observation failures remain unknown.
    return rows(
            "SELECT a.id FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id WHERE a.tenant_id=? AND a.task_id=? AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE)",
            s(task, "tenant_id"),
            s(task, "id"))
        .isEmpty();
  }

  @Override
  public void quiescent(Lease lease) {
    tx(
        () -> {
          bucket(lease.tenant());
          var task = locked(lease.tenant(), lease.taskId());
          db.update(
              "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND id=? AND worker_id=? AND fence=?",
              lease.tenant(),
              lease.attemptId(),
              lease.worker(),
              lease.fence());
          settleStoppedWriters(task);
          return null;
        });
  }

  @Override
  public void processStopped(String worker) {
    for (var candidate :
        rows(
            "SELECT DISTINCT tenant_id,task_id AS id FROM task_attempt WHERE worker_id=?",
            worker)) {
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id");
            bucket(tenant);
            var task = locked(tenant, s(candidate, "id"));
            db.update(
                "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND task_id=? AND worker_id=?",
                tenant,
                s(task, "id"),
                worker);
            if (worker.equals(s(task, "worker_id")))
              db.update(
                  "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE tenant_id=? AND id=?",
                  tenant,
                  s(task, "id"));
            settleStoppedWriters(task);
            return null;
          });
    }
    // Mark the receipt last: a crash during reconciliation must leave it eligible for retry.
    db.update("UPDATE worker_process SET stopped=TRUE WHERE id=?", worker);
  }

  private void settleStoppedWriters(Map<String, Object> task) {
    if (!writersQuiescent(task)) return;
    if ("CANCEL_REQUESTED".equals(s(task, "state"))) {
      boolean uncertain =
          !rows(
                  "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                  s(task, "tenant_id"),
                  s(task, "id"))
              .isEmpty();
      terminal(
          task,
          uncertain ? "NEEDS_RECONCILIATION" : "CANCELLED",
          uncertain ? "EFFECT_OUTCOME_UNKNOWN" : "CANCELLED");
    }
    if (List.of("CANCEL_REQUESTED", "NEEDS_RECONCILIATION", "SUCCEEDED", "FAILED", "CANCELLED")
        .contains(s(task, "state"))) releaseRun(task);
  }

  @Override
  public void check() {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10') AND success=TRUE",
            Integer.class)
        != 10) throw new IllegalStateException("Required migration unavailable");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE",
            Integer.class)
        != 10) throw new IllegalStateException("Unsupported schema version");
  }

  @Override
  public void cleanupArtifacts(ArtifactStore store) {
    var candidates =
        rows(
            "SELECT a.tenant_id,a.id,a.task_id,a.upload_id FROM artifact a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id JOIN reservation r ON r.tenant_id=a.tenant_id AND r.id=a.id WHERE r.state<>'RELEASED' AND ((a.state IN ('STAGING','VERIFIED','ABANDONED') AND a.updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 HOUR AND t.state IN ('FAILED','CANCELLED')) OR (a.state IN ('PUBLISHED','EXPIRED') AND a.expires_at<CURRENT_TIMESTAMP(6))) LIMIT 64");
    for (var candidate : candidates) {
      String tenant = s(candidate, "tenant_id"),
          artifact = s(candidate, "id"),
          taskId = s(candidate, "task_id");
      boolean ready =
          tx(
              () -> {
                bucket(tenant);
                var task = locked(tenant, taskId);
                var a =
                    one(
                        "SELECT * FROM artifact WHERE tenant_id=? AND id=? FOR UPDATE",
                        tenant,
                        artifact);
                boolean expired = time(a, "expires_at").isBefore(Instant.now());
                // A RUNNING task may still claim a new attempt after this transaction.
                // Quiescence of existing writers alone cannot make that cleanup safe.
                if (expired
                    && !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state")))
                  return false;
                if (!expired && !List.of("FAILED", "CANCELLED").contains(s(task, "state")))
                  return false;
                if (!writersQuiescent(task)) return false;
                db.update(
                    "UPDATE artifact SET state=? WHERE tenant_id=? AND id=?",
                    expired ? "EXPIRED" : "ABANDONED",
                    tenant,
                    artifact);
                return true;
              });
      if (!ready) continue;
      // Filesystem operations are outside every database transaction. Failed deletion is retried by
      // state.
      store.removeUpload(tenant, s(candidate, "upload_id"));
      tx(
          () -> {
            bucket(tenant);
            locked(tenant, taskId);
            var reservation =
                one("SELECT * FROM reservation WHERE tenant_id=? AND id=?", tenant, artifact);
            if (!"RELEASED".equals(s(reservation, "state"))) {
              db.update(
                  "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                  n(reservation, "amount"),
                  tenant);
              db.update(
                  "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                  tenant,
                  artifact);
            }
            return null;
          });
    }
  }
}
