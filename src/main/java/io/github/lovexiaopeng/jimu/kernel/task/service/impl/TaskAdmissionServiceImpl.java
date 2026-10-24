package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.ModelCost;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskAdmissionRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskAdmissionService;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;
import java.util.Map;

/** 同一短事务中的受理决策：重试先于新任务资格，所有预留和 accepted 事件原子提交。 */
public final class TaskAdmissionServiceImpl implements TaskAdmissionService {
  private final TaskAdmissionRecords records;
  private final LedgerUnitOfWork work;

  public TaskAdmissionServiceImpl(TaskAdmissionRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public View submit(
      Identity identity,
      String key,
      Request original,
      List<TaskImages.Input> input,
      List<String> historySources,
      String callerId) {
    var descriptors = input.isEmpty() ? null : records.describe(input);
    boolean prior = historySources != null;
    var request =
        new Request(
            original.goal(),
            original.capabilityIds(),
            original.resourceIds(),
            original.budget(),
            descriptors);
    if (!prior && request.capabilityIds().contains("session_search"))
      throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean sessionTrace = request.capabilityIds().contains("session_event_trace");
    boolean sessionSearch = request.capabilityIds().contains("session_event_search");
    boolean sessionRead =
        request.capabilityIds().contains("session_event_read") || sessionSearch || sessionTrace;
    if (sessionRead
        && (!(prior
                ? request.capabilityIds().equals(List.of("session_event_read", "session_search"))
                : sessionTrace
                    ? request
                        .capabilityIds()
                        .equals(
                            List.of(
                                "session_event_read",
                                "session_event_search",
                                "session_event_trace"))
                    : sessionSearch
                        ? request
                            .capabilityIds()
                            .equals(List.of("session_event_read", "session_event_search"))
                        : request.capabilityIds().equals(List.of("session_event_read")))
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean directory = request.capabilityIds().contains("working_directory");
    if (directory
        && (!new java.util.HashSet<>(request.capabilityIds())
                .equals(java.util.Set.of("working_directory", "read", "write", "edit"))
            || request.capabilityIds().size() != 4
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean todo = request.capabilityIds().contains("todo_write");
    if (todo
        && (!request.capabilityIds().equals(List.of("todo_write"))
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean ask = request.capabilityIds().contains("ask_user_question");
    if (ask
        && (!request.capabilityIds().equals(List.of("ask_user_question"))
            || !request.resourceIds().isEmpty()
            || descriptors != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    if (ask && !records.questionsEnabled()) throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
    boolean media = request.capabilityIds().contains("read_image");
    boolean mcpTask =
        request.capabilityIds().stream()
            .anyMatch(io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES::contains);
    if (mcpTask
        && (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.containsAll(
                request.capabilityIds())
            || !request.resourceIds().isEmpty()))
      throw new TaskFailure(400, "INVALID_CAPABILITIES");
    if (mcpTask && !records.mcpEnabled()) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    if (descriptors != null && !media) throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    return work.required(
        () -> {
          var admission = records.lock(identity, key, historySources, callerId);
          var budget = admission.room();
          String
              snapshot =
                  prior
                      ? records.json(
                          Map.of("request", request, "history_session_ids", historySources))
                      : records.json(request),
              digest = records.hash(snapshot);
          var old = admission.existing(ask);
          if (old != null) {
            if (!records.hash(old.snapshot()).equals(old.digest()))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            if (prior
                ? !snapshot.equals(old.snapshot())
                : !records.readRequest(old.snapshot()).equals(request))
              throw new TaskFailure(409, "IDEMPOTENCY_CONFLICT");
            return admission.replay(mcpTask || ask);
          }
          if (media) {
            admission.requireImageModel();
            if (!admission.imageStoreAvailable())
              throw new TaskFailure(503, "IMAGE_STORE_UNAVAILABLE");
            if (request.capabilityIds().contains("write")
                || request.capabilityIds().contains("present")
                    && !request.capabilityIds().contains("compose_report"))
              throw new TaskFailure(400, "INVALID_CAPABILITIES");
          }
          long installationEpoch = admission.installationEpoch(request.capabilityIds());
          var mcpBinding = mcpTask ? admission.bindMcp() : null;
          if (request.capabilityIds().stream()
                  .anyMatch(name -> List.of("glob", "grep").contains(name))
              && !admission.searchAvailable()) throw new TaskFailure(503, "SEARCH_NOT_CONFIGURED");
          var pins = new java.util.LinkedHashMap<String, PluginPin>();
          for (String capability : request.capabilityIds()) {
            pins.put(capability, admission.pin(capability));
          }
          String toolSnapshot =
              prior
                  ? records.json(
                      new java.util.TreeMap<>(
                          Map.of("schemaVersion", 1, "pins", new java.util.TreeMap<>(pins))))
                  : records.json(new ToolSnapshot(1, pins));
          if (pins.containsKey("compose_report")
              && media != pins.get("compose_report").version().equals("0.2.0"))
            throw new TaskFailure(503, "PLUGIN_PIN_MISMATCH");
          var model = admission.model();
          String scopeDigest = admission.requireScope(model);
          var businessPolicy = admission.policy();
          if (ask
              && (model.paid()
                  || businessPolicy != null
                  || admission.hasEvaluation()
                  || !(admission.localModel(model))))
            throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
          if (sessionRead) {
            if (!admission.sessionReadEnabled())
              throw new TaskFailure(403, "SESSION_READ_DISABLED");
            if (prior) {
              if (!admission.priorSearchEnabled())
                throw new TaskFailure(403, "SESSION_SEARCH_DISABLED");
              admission.requireSessionModel(model, "search");
            } else if (sessionTrace) {
              if (!admission.sessionTraceEnabled() || !admission.sessionSearchEnabled())
                throw new TaskFailure(403, "SESSION_TRACE_DISABLED");
              admission.requireSessionModel(model, "trace");
            } else if (sessionSearch) {
              if (!admission.sessionSearchEnabled())
                throw new TaskFailure(403, "SESSION_SEARCH_DISABLED");
              admission.requireSessionModel(model, "search");
            } else admission.requireSessionModel(model, "read");
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, "SESSION_READ_DISABLED");
            for (String name : request.capabilityIds()) {
              var descriptor = admission.contract(name, prior, sessionTrace, sessionSearch);
              var pin = pins.get(name);
              if (!pin.version().equals(descriptor.version())
                  || !pin.digest().equals(descriptor.digest()))
                throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            }
            if (budget.historyRoom() < 8388608) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > 180)
              throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          }
          if (directory) {
            if (!admission.directoryEnabled())
              throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
            admission.requireDirectoryModel(model);
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
            for (String name : request.capabilityIds()) {
              var descriptor = admission.contract(name, prior, sessionTrace, sessionSearch);
              if (!pins.get(name).version().equals(descriptor.version())
                  || !pins.get(name).digest().equals(descriptor.digest()))
                throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            }
            if (budget.directoryRoom() < 8388608)
              throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > 180)
              throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
          }
          if (todo) {
            if (!admission.todoEnabled()) throw new TaskFailure(403, "TODO_NOT_AUTHORIZED");
            admission.requireTodoModel(model);
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, "TODO_NOT_AUTHORIZED");
            if (!pins.get("todo_write")
                    .version()
                    .equals(admission.contract("todo_write", false, false, false).version())
                || !pins.get("todo_write")
                    .digest()
                    .equals(admission.contract("todo_write", false, false, false).digest()))
              throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            if (budget.todoRoom() < 2097152) throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20)
              throw new TaskFailure(429, "TODO_EXECUTION_LIMIT");
          }
          String questionBinding =
              ask ? admission.questionBinding(pins.get("ask_user_question").version()) : null;
          if (ask
              && !pins.get("ask_user_question")
                  .digest()
                  .equals(admission.questionDigest(pins.get("ask_user_question").version())))
            throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          if (ask && budget.questionHeld() >= budget.questionLimit())
            throw new TaskFailure(429, "QUESTION_TASK_LIMIT");
          if (ask && 2097152 > budget.storageRoom())
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          if (mcpTask && (model.paid() || businessPolicy != null || !(admission.localModel(model))))
            throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
          if (request.capabilityIds().contains("compose_report")
              && request.capabilityIds().contains("write"))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (businessPolicy != null
              && businessPolicy.version() == 1
              && request.capabilityIds().contains("present")
              && !request.capabilityIds().stream()
                  .filter(name -> !List.of("edit", "glob", "grep").contains(name))
                  .toList()
                  .equals(List.of("web_fetch", "read", "compose_report", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (businessPolicy != null
              && businessPolicy.version() == 2
              && (!media
                  || !request
                      .capabilityIds()
                      .containsAll(List.of("read", "read_image", "compose_report", "present"))
                  || request.capabilityIds().stream()
                      .anyMatch(c -> List.of("write", "web_search").contains(c))))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          Long explicitCost = request.budget().maxCostMicros();
          if (explicitCost != null
              && (explicitCost < 1 || businessPolicy == null && explicitCost > 1000000))
            throw new TaskFailure(400, "INVALID_BUDGET");
          if (request.budget().maxCostMicros() != null && !model.paid())
            throw new TaskFailure(400, "CURRENCY_BUDGET_NOT_CONFIGURED");
          admission.admitModel(model);
          long taskCost = explicitCost == null ? 1000000 : explicitCost;
          long taskTokens = 65536;
          if (businessPolicy != null) {
            long singleCost;
            long hostCost;
            try {
              long singleTokens =
                  Math.addExact(model.price().inputTokenUpper(), model.maxOutputTokens());
              singleCost =
                  ModelCost.amount(
                      model.price(), model.price().inputTokenUpper(), model.maxOutputTokens());
              taskTokens = Math.multiplyExact(singleTokens, request.budget().maxModelRequests());
              hostCost =
                  Math.min(
                      Math.multiplyExact(singleCost, request.budget().maxModelRequests()),
                      admission.evaluationCost());
            } catch (ArithmeticException unrepresentable) {
              throw new TaskFailure(503, "MODEL_COST_BOUND_UNAVAILABLE");
            }
            if (explicitCost != null && explicitCost > hostCost)
              throw new TaskFailure(400, "INVALID_BUDGET");
            taskCost = explicitCost == null ? hostCost : explicitCost;
            if (taskCost < singleCost) throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          }
          var evaluationBinding = businessPolicy == null ? admission.bindEvaluation(model) : null;
          var material = evaluationBinding == null ? null : admission.material(evaluationBinding);
          if (material != null && !request.goal().equals(material.goal()))
            throw new TaskFailure(409, "EVALUATION_MATERIAL_MISMATCH");
          if (evaluationBinding != null
              && !request.capabilityIds().equals(List.of("web_fetch", "read", "write", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (evaluationBinding != null && admission.sampleAlreadyBound(model, evaluationBinding))
            throw new TaskFailure(409, "EVALUATION_SAMPLE_ALREADY_BOUND");
          if (budget.queued() >= budget.queueLimit()) throw new TaskFailure(429, "QUEUE_LIMIT");
          String task =
              admission.insert(
                  prior ? callerId : null, key, request, snapshot, digest, installationEpoch);
          admission.bindSnapshots(task, model, taskCost, toolSnapshot, scopeDigest);
          if (sessionRead) {
            admission.createSession(task, historySources);
            admission.reserve(task, ":session-history", "SESSION_HISTORY_STORAGE", 8388608);
          }
          if (directory) {
            admission.createDirectory(task);
            admission.reserve(task, ":directory-storage", "DIRECTORY_STORAGE", 8388608);
          }
          if (todo) {
            admission.createTodo(task);
            admission.reserve(task, ":todo-storage", "TODO_STORAGE", 2097152);
          }
          if (ask) {
            admission.bindQuestion(task, questionBinding);
            admission.reserve(task, ":question-task", "QUESTION_TASK", 1);
            admission.reserve(task, ":question-storage", "QUESTION_STORAGE", 2097152);
          }
          if (mcpBinding != null) admission.bindMcp(task, mcpBinding);
          if (businessPolicy != null) admission.bindPolicy(task, businessPolicy, taskTokens);
          if (media) {
            long held = input.stream().mapToLong(i -> i.dataBase64().length()).sum();
            if (held > budget.storageRoom()) throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            admission.insertImages(
                task, input, descriptors == null ? List.of() : descriptors, held);
          }
          if (evaluationBinding != null)
            admission.bindEvaluation(task, request, model, evaluationBinding, material);
          admission.reserve(task, ":queue", "QUEUE", 1);
          return admission.accepted(task);
        });
  }
}
