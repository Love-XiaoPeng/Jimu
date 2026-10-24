package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskLookupMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskAdmissionRecords;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.Map;

/** 同一事务中的受理事实、模块证明与具名Mapper；不另开连接、不保留跨请求状态。 */
final class MapperTaskAdmissions implements TaskAdmissionRecords {
  private final JdbcLedger ledger;
  private final TaskAdmissionMapper sql;

  MapperTaskAdmissions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(TaskAdmissionMapper.class);
  }

  public List<TaskImages.Descriptor> describe(List<TaskImages.Input> input) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageInputs.describe(input);
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public Request readRequest(String value) {
    return Payloads.read(value, Request.class);
  }

  public boolean questionsEnabled() {
    return ledger.questionsEnabled;
  }

  public boolean mcpEnabled() {
    return ledger.mcp.enabled();
  }

  private List<Map<String, Object>> lookup(LambdaQueryWrapper<TaskLookupEntity> wrapper) {
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .selectList(TaskLookupMapper.class.getName() + ".selectMaps", parameters);
  }

  private List<Map<String, Object>> binding(Identity identity, String key) {
    var wrapper = new LambdaQueryWrapper<TaskLookupEntity>();
    wrapper
        .select(TaskLookupEntity::getTaskId, TaskLookupEntity::getEvaluationRunId)
        .eq(TaskLookupEntity::getTenantId, identity.tenant())
        .eq(TaskLookupEntity::getOwnerActorId, identity.actor())
        .eq(TaskLookupEntity::getIdempotencyKey, key);
    return lookup(wrapper);
  }

  public Admission lock(
      Identity identity, String key, List<String> historySources, String callerId) {
    // FK parent first, including retry; budget serializes per-tenant admission and queue
    // accounting.
    var found = binding(identity, key);
    String run;
    if (found.isEmpty()) {
      run = ledger.executionScopes.targetRun(identity.tenant(), null);
      if (run == null) {
        var candidate = ledger.models.selected(identity.tenant());
        run = candidate.paid() ? ledger.models.evaluationRunId() : null;
      }
    } else run = ledger.s(found.getFirst(), "evaluation_run_id");
    var evaluation = ledger.evaluation(identity.tenant(), run);
    var budget = ledger.bucket(identity.tenant());
    if (historySources != null) {
      var ids = new java.util.ArrayList<>(historySources);
      ids.add(callerId);
      var existing = binding(identity, key);
      if (!existing.isEmpty()) {
        String previous = ledger.s(existing.getFirst(), "id");
        ids.add(previous);
        var targets = sql.selectScopeTargets(ledger.queryContext(), identity.tenant(), previous);
        if (targets.size() > 8) throw JdbcSessionRecords.corrupt();
        for (var target : targets)
          if (target.get("source_task_id") != null) ids.add(ledger.s(target, "source_task_id"));
      }
      ledger.sessionScopes.lock(identity.tenant(), ids);
    }
    ledger.identity(identity);
    return new Locked(identity, key, run, evaluation, budget);
  }

  private final class Locked implements Admission {
    private final Identity identity;
    private final String key, run;
    private final Map<String, Object> evaluation, budget;
    private Map<String, Object> retry;

    Locked(
        Identity identity,
        String key,
        String run,
        Map<String, Object> evaluation,
        Map<String, Object> budget) {
      this.identity = identity;
      this.key = key;
      this.run = run;
      this.evaluation = evaluation;
      this.budget = budget;
    }

    public Existing existing(boolean ask) {
      var rows =
          sql.lockIdempotency(ledger.queryContext(), identity.tenant(), identity.actor(), key);
      if (rows.isEmpty()) return null;
      retry = rows.getFirst();
      if (JdbcSessionRecords.task(retry)) {
        ledger.access(retry);
        ledger.sessionAdmission(retry);
      }
      if (ledger.directoryTask(retry)) {
        ledger.access(retry);
        if (!"PRESENT".equals(ledger.s(ledger.directoryProjection(retry), "content_state")))
          throw new TaskFailure(410, "DIRECTORY_CONTENT_EXPIRED");
      }
      if (ledger.todoTask(retry)) {
        ledger.access(retry);
        if ("PURGED".equals(ledger.s(ledger.todoProjection(retry), "content_state")))
          throw new TaskFailure(410, "TODO_CONTENT_EXPIRED");
      }
      if (ask) ledger.access(retry);
      return new Existing(ledger.s(retry, "request_snapshot"), ledger.s(retry, "request_digest"));
    }

    public View replay(boolean recheckAccess) {
      if (recheckAccess) ledger.access(retry);
      return ledger.view(retry);
    }

    public Room room() {
      return new Room(
          ledger.n(budget, "queued"),
          ledger.n(budget, "queue_limit"),
          ledger.n(budget, "history_storage_limit") - ledger.n(budget, "history_storage_held"),
          ledger.n(budget, "directory_storage_limit") - ledger.n(budget, "directory_storage_held"),
          ledger.n(budget, "todo_storage_limit") - ledger.n(budget, "todo_storage_held"),
          ledger.n(budget, "question_tasks_held"),
          ledger.n(budget, "question_tasks_limit"),
          ledger.n(budget, "storage_limit") - ledger.storageHeld(budget));
    }

    public void requireImageModel() {
      io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages.require(
          ledger.models.selected(identity.tenant()));
    }

    public boolean imageStoreAvailable() {
      return ledger.imagesAvailable() && sql.maxPacket(ledger.queryContext()) >= 16777216;
    }

    public long installationEpoch(List<String> capabilities) {
      return ledger.epoch(identity, capabilities);
    }

    public McpLedger.Binding bindMcp() {
      return ledger.mcp.bind(identity.tenant(), identity.actor());
    }

    public boolean searchAvailable() {
      return ledger.searchAvailable.getAsBoolean();
    }

    public PluginPin pin(String capability) {
      var row =
          JdbcLedger.first(
              ledger.mappers.selectInstallation(
                  ledger.queryContext(), identity.tenant(), capability));
      return new PluginPin(
          ledger.s(row, "plugin_version"), ledger.s(row, "plugin_digest"), ledger.n(row, "epoch"));
    }

    public ModelSnapshot model() {
      return ledger.models.selected(identity.tenant());
    }

    public String requireScope(ModelSnapshot model) {
      return ledger.requireScope(identity.tenant(), evaluation, null, model);
    }

    public Policy policy() {
      var p = ledger.models.businessPolicy();
      return p == null ? null : new Policy(p.version(), BusinessPolicies.canonical(p));
    }

    public boolean hasEvaluation() {
      return evaluation != null;
    }

    public long evaluationCost() {
      return ledger.n(evaluation, "max_cost_micros");
    }

    public boolean localModel(ModelSnapshot model) {
      return ledger.models.fixture() || ledger.models.localEvaluation(model);
    }

    public boolean hasScopeRequirement() {
      return ledger.models.scopeRequirement() != null;
    }

    public boolean sessionReadEnabled() {
      return ledger.sessionReadEnabled;
    }

    public boolean priorSearchEnabled() {
      return ledger.priorSessionSearchEnabled;
    }

    public boolean sessionTraceEnabled() {
      return ledger.sessionTraceEnabled;
    }

    public boolean sessionSearchEnabled() {
      return ledger.sessionSearchEnabled;
    }

    public boolean directoryEnabled() {
      return ledger.directoryEnabled;
    }

    public boolean todoEnabled() {
      return ledger.todosEnabled;
    }

    public void requireSessionModel(ModelSnapshot model, String kind) {
      switch (kind) {
        case "read" -> ledger.models.requireSessionRead(model);
        case "search" -> ledger.models.requireSessionSearch(model);
        case "trace" -> ledger.models.requireSessionTrace(model);
        default -> throw new IllegalArgumentException(kind);
      }
    }

    public void requireDirectoryModel(ModelSnapshot model) {
      ledger.models.requireDirectory(model);
    }

    public void requireTodoModel(ModelSnapshot model) {
      ledger.models.requireTodo(model);
    }

    public PluginPin contract(String name, boolean prior, boolean trace, boolean search) {
      var descriptor =
          switch (name) {
            case "session_event_read" ->
                (prior
                        ? ledger.sessions.v4
                        : trace
                            ? ledger.sessions.v3
                            : search ? ledger.sessions.v2 : ledger.sessions.contracts)
                    .descriptor();
            case "session_search" -> ledger.sessionScopes.contracts.descriptor();
            case "session_event_search" ->
                (trace ? ledger.sessions.searchV3 : ledger.sessions.search).descriptor();
            case "session_event_trace" -> ledger.sessions.trace.descriptor();
            case "working_directory" -> ledger.directories.descriptor();
            case "todo_write" -> ledger.todos.descriptor();
            default -> ledger.directoryFileContract(name).descriptor();
          };
      return new PluginPin(descriptor.version(), descriptor.outputSha256(), 0);
    }

    public String questionBinding(String version) {
      return ledger.questions.binding(version);
    }

    public String questionDigest(String version) {
      return ledger.questions.contract(version).descriptor().outputSha256();
    }

    public void admitModel(ModelSnapshot model) {
      ledger.models.admit(model);
    }

    public EvaluationBinding bindEvaluation(ModelSnapshot model) {
      var binding = ledger.bindEvaluation(evaluation, model);
      return binding == null
          ? null
          : new EvaluationBinding(Payloads.json(binding), binding.sample());
    }

    public Material material(EvaluationBinding binding) {
      var m =
          EvaluationMaterials.load(ledger.models.evaluationSelection().plan(), binding.sample());
      return new Material(m.goal(), Payloads.json(m), m.systemTemplate());
    }

    public boolean sampleAlreadyBound(ModelSnapshot model, EvaluationBinding binding) {
      return !sql.sampleBound(
              ledger.queryContext(), identity.tenant(), run, model.model(), binding.sample())
          .isEmpty();
    }

    public String insert(
        String caller, String key, Request request, String snapshot, String digest, long epoch) {
      String task = caller == null ? JdbcLedger.id() : caller;
      sql.insertAccepted(
          ledger.queryContext(),
          identity.tenant(),
          task,
          identity.actor(),
          key,
          digest,
          snapshot,
          request.goal(),
          String.join(",", request.capabilityIds()),
          epoch,
          request.budget().deadlineSeconds(),
          request.budget().maxModelRequests(),
          request.budget().maxToolInvocations());
      return task;
    }

    public void bindSnapshots(
        String task, ModelSnapshot model, long cost, String tools, String scope) {
      String snapshot = Payloads.json(model);
      sql.bindSnapshots(
          ledger.queryContext(),
          snapshot,
          Payloads.hash(snapshot),
          model.paid() ? run : null,
          cost,
          tools,
          Payloads.hash(tools),
          scope,
          identity.tenant(),
          task);
    }

    public void createSession(String task, List<String> historySources) {
      var accepted = ledger.locked(identity.tenant(), task);
      ledger.sessions.create(accepted);
      if (historySources != null) ledger.sessionScopes.create(accepted, historySources);
      sql.holdHistory(ledger.queryContext(), identity.tenant());
    }

    public void createDirectory(String task) {
      String namespace = Payloads.hash(identity.tenant() + ":" + task),
          binding = ledger.directories.binding(namespace);
      sql.insertDirectory(
          ledger.queryContext(),
          binding,
          Payloads.hash(binding),
          namespace,
          identity.tenant(),
          task);
      sql.holdDirectory(ledger.queryContext(), identity.tenant());
    }

    public void createTodo(String task) {
      String binding = ledger.todos.binding();
      sql.insertTodo(
          ledger.queryContext(), binding, Payloads.hash(binding), identity.tenant(), task);
      sql.holdTodo(ledger.queryContext(), identity.tenant());
    }

    public void bindQuestion(String task, String binding) {
      sql.bindQuestion(
          ledger.queryContext(), binding, Payloads.hash(binding), identity.tenant(), task);
      sql.holdQuestion(ledger.queryContext(), identity.tenant());
    }

    public void bindMcp(String task, McpLedger.Binding binding) {
      String snapshot = Payloads.json(binding);
      sql.bindMcp(
          ledger.queryContext(), snapshot, Payloads.hash(snapshot), identity.tenant(), task);
    }

    public void bindPolicy(String task, Policy policy, long tokens) {
      sql.bindPolicy(
          ledger.queryContext(),
          policy.snapshot(),
          Payloads.hash(policy.snapshot()),
          tokens,
          identity.tenant(),
          task);
    }

    public void insertImages(
        String task,
        List<TaskImages.Input> input,
        List<TaskImages.Descriptor> descriptors,
        long held) {
      ledger.images.insert(identity, task, input, descriptors);
      if (held > 0) {
        sql.holdImages(ledger.queryContext(), held, identity.tenant());
        ledger.reserve(
            identity.tenant(),
            task,
            task + ":media-input",
            null,
            "MEDIA_INPUT",
            held,
            "SETTLED",
            "KNOWN");
        sql.settleImages(ledger.queryContext(), identity.tenant(), task + ":media-input");
      }
    }

    public void bindEvaluation(
        String task,
        Request request,
        ModelSnapshot model,
        EvaluationBinding binding,
        Material material) {
      var selection = ledger.models.evaluationSelection();
      var plan = selection.plan();
      String snapshot = binding.snapshot();
      sql.bindEvaluation(
          ledger.queryContext(),
          snapshot,
          Payloads.hash(snapshot),
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
      var accepted = ledger.locked(identity.tenant(), task);
      String system =
          material
              .systemTemplate()
              .replace("{{HOST_REPORT_TIME}}", ledger.time(accepted, "report_time").toString());
      sql.bindMaterial(
          ledger.queryContext(),
          material.snapshot(),
          Payloads.hash(material.snapshot()),
          Payloads.hash(system),
          identity.tenant(),
          task);
      if (request.budget().maxCostMicros() == null)
        sql.setTaskCost(
            ledger.queryContext(),
            EvaluationPlans.taskCost(plan, model.model(), selection.sample()),
            identity.tenant(),
            task);
    }

    public void reserve(String task, String suffix, String kind, long amount) {
      if (kind.equals("QUEUE")) sql.holdQueue(ledger.queryContext(), identity.tenant());
      ledger.reserve(
          identity.tenant(), task, task + suffix, null, kind, amount, "RESERVED", "KNOWN");
      if (kind.equals("DIRECTORY_STORAGE"))
        ledger.directoryStorage(ledger.locked(identity.tenant(), task), 0);
      if (kind.equals("TODO_STORAGE"))
        ledger.todoStorage(ledger.locked(identity.tenant(), task), 0);
    }

    public View accepted(String task) {
      var row = ledger.locked(identity.tenant(), task);
      ledger.event(row, "accepted", "task.accepted", null);
      return ledger.view(ledger.locked(identity.tenant(), task));
    }
  }
}
