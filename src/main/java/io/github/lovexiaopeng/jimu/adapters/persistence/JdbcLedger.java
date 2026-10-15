package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
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

  public JdbcLedger(DataSource source, ModelProfiles models) {
    this.models = models;
    db = new JdbcTemplate(source);
    db.setQueryTimeout(5);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
  }

  private <T> T tx(Supplier<T> work) {
    return transactions.execute(status -> work.get());
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
          var model = models.selected(identity.tenant());
          if (request.budget().maxCostMicros() != null && !model.paid())
            throw new TaskFailure(400, "CURRENCY_BUDGET_NOT_CONFIGURED");
          models.admit(model);
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
              "UPDATE task SET model_snapshot=?,model_digest=?,evaluation_run_id=?,max_cost_micros=? WHERE tenant_id=? AND id=?",
              modelSnapshot,
              Payloads.hash(modelSnapshot),
              model.paid() ? models.evaluationRunId() : null,
              request.budget().maxCostMicros() == null ? 1000000 : request.budget().maxCostMicros(),
              identity.tenant(),
              task);
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
          if (view(row).terminal() || row.get("cancel_requested_at") != null) return view(row);
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
      Optional<Lease> lease =
          tx(
              () -> {
                var budget = bucket(tenant);
                var candidates =
                    rows(
                        "SELECT *,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND record_version=1 AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))) ORDER BY ready_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                        tenant);
                if (candidates.isEmpty()) return Optional.empty();
                var task = candidates.getFirst();
                String taskId = s(task, "id");
                boolean queued = "QUEUED".equals(s(task, "state"));
                if (queued && n(budget, "running") >= n(budget, "running_limit"))
                  return Optional.empty();
                if (!queued)
                  db.update(
                      "UPDATE task_attempt SET state='EXPIRED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='ACTIVE'",
                      tenant,
                      s(task, "current_attempt_id"));
                // An expired writer might have caused an effect. Never infer safety from an absent
                // result.
                if (!rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                        tenant,
                        taskId)
                    .isEmpty()) {
                  db.update(
                      "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND task_id=? AND state='RUNNING'",
                      tenant,
                      taskId);
                  terminal(task, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
                  return Optional.empty();
                }
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
    bucket(lease.tenant());
    var task = locked(lease.tenant(), lease.taskId());
    if (n(task, "fence") != lease.fence()
        || !lease.attemptId().equals(s(task, "current_attempt_id"))
        || !lease.worker().equals(s(task, "worker_id"))
        || n(task, "live") != 1
        || !List.of("RUNNING", "CANCEL_REQUESTED").contains(s(task, "state")))
      throw new TaskFailure(409, "FENCE_REJECTED");
    if (admission) {
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
    return tx(
        () -> {
          fenced(lease, true);
          return rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId())
              .stream()
              .map(this::step)
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
    String value = s(task, "model_snapshot");
    if (value == null) throw new TaskFailure(409, "MODEL_SNAPSHOT_MISSING");
    if (!Payloads.hash(value).equals(s(task, "model_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ModelSnapshot.class);
    models.available(snapshot);
    return snapshot;
  }

  @Override
  public ModelSnapshot modelSnapshot(Lease lease) {
    return tx(() -> snapshot(fenced(lease, true)));
  }

  // This is always the first lock when a transaction can mutate evaluation accounting.
  private Map<String, Object> evaluation(Lease lease) {
    String run =
        s(
            one(
                "SELECT evaluation_run_id FROM task WHERE tenant_id=? AND id=?",
                lease.tenant(),
                lease.taskId()),
            "evaluation_run_id");
    return run == null
        ? null
        : one(
            "SELECT * FROM evaluation_budget WHERE tenant_id=? AND id=? FOR UPDATE",
            lease.tenant(),
            run);
  }

  @Override
  public Step prepare(Lease lease, String request) {
    return tx(
        () -> {
          var task = fenced(lease, true);
          var previous =
              rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId());
          if (!previous.isEmpty() && request.equals(s(previous.getFirst(), "request_snapshot")))
            return step(previous.getFirst());
          if (!previous.isEmpty()
              && (previous.getFirst().get("response_snapshot") == null
                  || !rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND step_id=? AND state<>'SUCCEEDED'",
                          lease.tenant(),
                          s(previous.getFirst(), "id"))
                      .isEmpty())) throw new TaskFailure(409, "CHECKPOINT_INCOMPLETE");
          int ordinal = previous.isEmpty() ? 1 : (int) n(previous.getFirst(), "ordinal") + 1;
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
    return tx(
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
          var evaluation = evaluation(lease);
          var task = fenced(lease, false);
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  lease.taskId());
          if (!dispatch.equals(s(step, "dispatch_id")) || step.get("response_snapshot") != null)
            throw new TaskFailure(409, "DISPATCH_REJECTED");
          settleCost(lease, task, evaluation, dispatch, usage);
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
          int ordinal =
              db.queryForObject(
                  "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND attempt_id=?",
                  Integer.class,
                  lease.tenant(),
                  lease.attemptId());
          for (Call call : calls) {
            if (!lease.capabilities().contains(call.tool()))
              throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
            var plugin =
                one(
                    "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                    lease.tenant(),
                    call.tool());
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
                s(plugin, "plugin_version"),
                s(plugin, "plugin_digest"),
                n(plugin, "epoch"),
                call.input(),
                Payloads.hash(call.input()));
          }
          event(task, "response:" + stepId, "model.responded", null);
          return null;
        });
    if ("BOUND_EXCEEDED"
        .equals(
            s(
                one(
                    "SELECT state FROM model_dispatch WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    dispatch),
                "state"))) throw new TaskFailure(409, "MODEL_COST_BOUND_EXCEEDED");
  }

  private void settleCost(
      Lease lease,
      Map<String, Object> task,
      Map<String, Object> evaluation,
      String dispatch,
      ModelUsage usage) {
    var model = snapshot(task);
    Long actual = null;
    boolean exceeded = false;
    if (model.paid() && usage.inputTokens() != null && usage.outputTokens() != null) {
      try {
        actual = ModelCosts.amount(model.price(), usage.inputTokens(), usage.outputTokens());
      } catch (ArithmeticException invalidProviderCost) {
        // Retain raw usage; an unrepresentable cost is UNKNOWN, never clamped.
        exceeded = true;
      }
      long reserved =
          n(
              one(
                  "SELECT reserved_cost FROM model_dispatch WHERE tenant_id=? AND id=?",
                  lease.tenant(),
                  dispatch),
              "reserved_cost");
      long delta = actual == null ? 0 : actual - reserved;
      exceeded =
          exceeded
              || usage.inputTokens() > model.price().inputTokenUpper()
              || usage.outputTokens() > model.maxOutputTokens()
              || actual != null && actual > reserved;
      db.update(
          "UPDATE evaluation_budget SET cost_held=cost_held+?,state=IF(?,'STOPPED',state) WHERE tenant_id=? AND id=?",
          delta,
          exceeded,
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
    }
    db.update(
        "UPDATE model_dispatch SET state=?,code=?,ended_at=CURRENT_TIMESTAMP(6),input_tokens=?,output_tokens=?,total_tokens=?,usage_snapshot=?,actual_cost=?,cost_quality=? WHERE tenant_id=? AND id=?",
        exceeded ? "BOUND_EXCEEDED" : "RESPONDED",
        exceeded ? "MODEL_COST_BOUND_EXCEEDED" : null,
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
  }

  @Override
  public void modelFailed(Lease lease, String dispatch, String code) {
    tx(
        () -> {
          fenced(lease, false);
          db.update(
              "UPDATE model_dispatch SET state='FAILED',code=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='DISPATCHED'",
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
    return tx(
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
          String invocationId = s(invocation, "id");
          var installation =
              one(
                  "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                  lease.tenant(),
                  call.tool());
          if (!pluginVersion.equals(s(invocation, "plugin_version"))
              || !pluginDigest.equals(s(invocation, "plugin_digest"))
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
  public void toolSucceeded(Lease lease, String invocationId, String output) {
    tx(
        () -> {
          var task = fenced(lease, false);
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
          return null;
        });
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

  @Override
  public Upload stage(Lease lease, String hash, long size) {
    return tx(
        () -> {
          var task = fenced(lease, true);
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
    tx(
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
    tx(
        () -> {
          var task = fenced(lease, true);
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
    tx(
        () -> {
          var task = fenced(lease, true);
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
          boolean pendingEffect =
              !rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty();
          if (pendingEffect || "SNAPSHOT_CORRUPT".equals(code))
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
            "SELECT w.*,EXISTS(SELECT 1 FROM task_attempt a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id WHERE a.worker_id=w.id AND a.quiescent=FALSE AND t.state='CANCEL_REQUESTED' AND t.cancel_requested_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND) AS overdue FROM worker_process w WHERE host_id=? AND stopped=FALSE ORDER BY id LIMIT 64",
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
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4') AND success=TRUE",
            Integer.class)
        != 4) throw new IllegalStateException("Required migration unavailable");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE",
            Integer.class)
        != 4) throw new IllegalStateException("Unsupported schema version");
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
