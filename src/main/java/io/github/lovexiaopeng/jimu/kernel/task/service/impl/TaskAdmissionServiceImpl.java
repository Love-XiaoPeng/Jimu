package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ModelCost;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.kernel.task.domain.TaskExecutionLimitsV1;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskAdmissionRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskAdmissionService;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.McpErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SearchErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
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
    if (!prior && request.capabilityIds().contains(ToolCapability.SESSION_SEARCH))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    boolean sessionTrace = request.capabilityIds().contains(ToolCapability.SESSION_EVENT_TRACE);
    boolean sessionSearch = request.capabilityIds().contains(ToolCapability.SESSION_EVENT_SEARCH);
    boolean sessionRead =
        request.capabilityIds().contains(ToolCapability.SESSION_EVENT_READ)
            || sessionSearch
            || sessionTrace;
    if (sessionRead
        && (!(prior
                ? request
                    .capabilityIds()
                    .equals(
                        List.of(ToolCapability.SESSION_EVENT_READ, ToolCapability.SESSION_SEARCH))
                : sessionTrace
                    ? request
                        .capabilityIds()
                        .equals(
                            List.of(
                                ToolCapability.SESSION_EVENT_READ,
                                ToolCapability.SESSION_EVENT_SEARCH,
                                ToolCapability.SESSION_EVENT_TRACE))
                    : sessionSearch
                        ? request
                            .capabilityIds()
                            .equals(
                                List.of(
                                    ToolCapability.SESSION_EVENT_READ,
                                    ToolCapability.SESSION_EVENT_SEARCH))
                        : request
                            .capabilityIds()
                            .equals(List.of(ToolCapability.SESSION_EVENT_READ)))
            || !request.resourceIds().isEmpty()
            || original.images() != null))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    boolean directory = request.capabilityIds().contains(ToolCapability.WORKING_DIRECTORY);
    if (directory
        && (!new java.util.HashSet<>(request.capabilityIds())
                .equals(
                    java.util.Set.of(
                        ToolCapability.WORKING_DIRECTORY,
                        ToolCapability.READ,
                        ToolCapability.WRITE,
                        ToolCapability.EDIT))
            || request.capabilityIds().size() != 4
            || !request.resourceIds().isEmpty()
            || original.images() != null))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    boolean todo = request.capabilityIds().contains(ToolCapability.TODO_WRITE);
    if (todo
        && (!request.capabilityIds().equals(List.of(ToolCapability.TODO_WRITE))
            || !request.resourceIds().isEmpty()
            || original.images() != null))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    boolean ask = request.capabilityIds().contains(ToolCapability.ASK_USER_QUESTION);
    if (ask
        && (!request.capabilityIds().equals(List.of(ToolCapability.ASK_USER_QUESTION))
            || !request.resourceIds().isEmpty()
            || descriptors != null)) throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    if (ask && !records.questionsEnabled())
      throw new TaskFailure(403, QuestionErrorCode.QUESTION_NOT_AUTHORIZED);
    boolean media = request.capabilityIds().contains(ToolCapability.READ_IMAGE);
    boolean mcpTask =
        request.capabilityIds().stream()
            .anyMatch(io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES::contains);
    if (mcpTask
        && (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.containsAll(
                request.capabilityIds())
            || !request.resourceIds().isEmpty()))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    if (mcpTask && !records.mcpEnabled())
      throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
    if (descriptors != null && !media)
      throw new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
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
              throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
            if (prior
                ? !snapshot.equals(old.snapshot())
                : !records.readRequest(old.snapshot()).equals(request))
              throw new TaskFailure(409, CommonErrorCode.IDEMPOTENCY_CONFLICT);
            return admission.replay(mcpTask || ask);
          }
          if (media) {
            admission.requireImageModel();
            if (!admission.imageStoreAvailable())
              throw new TaskFailure(503, ImageErrorCode.IMAGE_STORE_UNAVAILABLE);
            if (request.capabilityIds().contains(ToolCapability.WRITE)
                || request.capabilityIds().contains(ToolCapability.PRESENT)
                    && !request.capabilityIds().contains(ToolCapability.COMPOSE_REPORT))
              throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
          }
          long installationEpoch = admission.installationEpoch(request.capabilityIds());
          var mcpBinding = mcpTask ? admission.bindMcp() : null;
          if (request.capabilityIds().stream()
                  .anyMatch(
                      name -> List.of(ToolCapability.GLOB, ToolCapability.GREP).contains(name))
              && !admission.searchAvailable())
            throw new TaskFailure(503, SearchErrorCode.SEARCH_NOT_CONFIGURED);
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
          if (pins.containsKey(ToolCapability.COMPOSE_REPORT)
              && media != pins.get(ToolCapability.COMPOSE_REPORT).version().equals("0.2.0"))
            throw new TaskFailure(503, TaskErrorCode.PLUGIN_PIN_MISMATCH);
          var model = admission.model();
          String scopeDigest = admission.requireScope(model);
          var businessPolicy = admission.policy();
          if (ask
              && (model.paid()
                  || businessPolicy != null
                  || admission.hasEvaluation()
                  || !(admission.localModel(model))))
            throw new TaskFailure(403, QuestionErrorCode.QUESTION_NOT_AUTHORIZED);
          if (sessionRead) {
            if (!admission.sessionReadEnabled())
              throw new TaskFailure(403, SessionErrorCode.SESSION_READ_DISABLED);
            if (prior) {
              if (!admission.priorSearchEnabled())
                throw new TaskFailure(403, SessionErrorCode.SESSION_SEARCH_DISABLED);
              admission.requireSessionModel(model, "search");
            } else if (sessionTrace) {
              if (!admission.sessionTraceEnabled() || !admission.sessionSearchEnabled())
                throw new TaskFailure(403, SessionErrorCode.SESSION_TRACE_DISABLED);
              admission.requireSessionModel(model, "trace");
            } else if (sessionSearch) {
              if (!admission.sessionSearchEnabled())
                throw new TaskFailure(403, SessionErrorCode.SESSION_SEARCH_DISABLED);
              admission.requireSessionModel(model, "search");
            } else admission.requireSessionModel(model, ToolCapability.READ);
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, SessionErrorCode.SESSION_READ_DISABLED);
            for (String name : request.capabilityIds()) {
              var descriptor = admission.contract(name, prior, sessionTrace, sessionSearch);
              var pin = pins.get(name);
              if (!pin.version().equals(descriptor.version())
                  || !pin.digest().equals(descriptor.digest()))
                throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
            }
            if (budget.historyRoom() < SessionHistoryPolicyV1.TASK_RESERVE_BYTES)
              throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > TaskExecutionLimitsV1.DEADLINE_SECONDS)
              throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
          }
          if (directory) {
            if (!admission.directoryEnabled())
              throw new TaskFailure(403, DirectoryErrorCode.DIRECTORY_NOT_AUTHORIZED);
            admission.requireDirectoryModel(model);
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, DirectoryErrorCode.DIRECTORY_NOT_AUTHORIZED);
            for (String name : request.capabilityIds()) {
              var descriptor = admission.contract(name, prior, sessionTrace, sessionSearch);
              if (!pins.get(name).version().equals(descriptor.version())
                  || !pins.get(name).digest().equals(descriptor.digest()))
                throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
            }
            if (budget.directoryRoom() < DirectoryPolicyV1.STORAGE_BYTES)
              throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_STORAGE_LIMIT);
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > TaskExecutionLimitsV1.DEADLINE_SECONDS)
              throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_EXECUTION_LIMIT);
          }
          if (todo) {
            if (!admission.todoEnabled())
              throw new TaskFailure(403, TodoErrorCode.TODO_NOT_AUTHORIZED);
            admission.requireTodoModel(model);
            if (admission.hasEvaluation()
                || businessPolicy != null
                || admission.hasScopeRequirement())
              throw new TaskFailure(403, TodoErrorCode.TODO_NOT_AUTHORIZED);
            if (!pins.get(ToolCapability.TODO_WRITE)
                    .version()
                    .equals(
                        admission
                            .contract(ToolCapability.TODO_WRITE, false, false, false)
                            .version())
                || !pins.get(ToolCapability.TODO_WRITE)
                    .digest()
                    .equals(
                        admission
                            .contract(ToolCapability.TODO_WRITE, false, false, false)
                            .digest()))
              throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
            if (budget.todoRoom() < TodoPolicyV1.STORAGE_BYTES)
              throw new TaskFailure(429, TodoErrorCode.TODO_STORAGE_LIMIT);
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20)
              throw new TaskFailure(429, TodoErrorCode.TODO_EXECUTION_LIMIT);
          }
          String questionBinding =
              ask
                  ? admission.questionBinding(pins.get(ToolCapability.ASK_USER_QUESTION).version())
                  : null;
          if (ask
              && !pins.get(ToolCapability.ASK_USER_QUESTION)
                  .digest()
                  .equals(
                      admission.questionDigest(
                          pins.get(ToolCapability.ASK_USER_QUESTION).version())))
            throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
          if (ask && budget.questionHeld() >= budget.questionLimit())
            throw new TaskFailure(429, QuestionErrorCode.QUESTION_TASK_LIMIT);
          if (ask && QuestionPolicyV1.STORAGE_BYTES > budget.storageRoom())
            throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
          if (mcpTask && (model.paid() || businessPolicy != null || !(admission.localModel(model))))
            throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
          if (request.capabilityIds().contains(ToolCapability.COMPOSE_REPORT)
              && request.capabilityIds().contains(ToolCapability.WRITE))
            throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
          if (businessPolicy != null
              && businessPolicy.version() == 1
              && request.capabilityIds().contains(ToolCapability.PRESENT)
              && !request.capabilityIds().stream()
                  .filter(
                      name ->
                          !List.of(ToolCapability.EDIT, ToolCapability.GLOB, ToolCapability.GREP)
                              .contains(name))
                  .toList()
                  .equals(
                      List.of(
                          ToolCapability.WEB_FETCH,
                          ToolCapability.READ,
                          ToolCapability.COMPOSE_REPORT,
                          ToolCapability.PRESENT)))
            throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
          if (businessPolicy != null
              && businessPolicy.version() == 2
              && (!media
                  || !request
                      .capabilityIds()
                      .containsAll(
                          List.of(
                              ToolCapability.READ,
                              ToolCapability.READ_IMAGE,
                              ToolCapability.COMPOSE_REPORT,
                              ToolCapability.PRESENT))
                  || request.capabilityIds().stream()
                      .anyMatch(
                          c ->
                              List.of(ToolCapability.WRITE, ToolCapability.WEB_SEARCH)
                                  .contains(c))))
            throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
          Long explicitCost = request.budget().maxCostMicros();
          if (explicitCost != null
              && (explicitCost < 1
                  || businessPolicy == null
                      && explicitCost > TaskExecutionLimitsV1.MAX_UNSCOPED_COST_MICROS))
            throw new TaskFailure(400, TaskErrorCode.INVALID_BUDGET);
          if (request.budget().maxCostMicros() != null && !model.paid())
            throw new TaskFailure(400, ModelErrorCode.CURRENCY_BUDGET_NOT_CONFIGURED);
          admission.admitModel(model);
          long taskCost =
              explicitCost == null ? TaskExecutionLimitsV1.DEFAULT_COST_MICROS : explicitCost;
          long taskTokens = TaskExecutionLimitsV1.DEFAULT_TOKENS;
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
              throw new TaskFailure(503, ModelErrorCode.MODEL_COST_BOUND_UNAVAILABLE);
            }
            if (explicitCost != null && explicitCost > hostCost)
              throw new TaskFailure(400, TaskErrorCode.INVALID_BUDGET);
            taskCost = explicitCost == null ? hostCost : explicitCost;
            if (taskCost < singleCost)
              throw new TaskFailure(429, ModelErrorCode.MODEL_BUDGET_EXCEEDED);
          }
          var evaluationBinding = businessPolicy == null ? admission.bindEvaluation(model) : null;
          var material = evaluationBinding == null ? null : admission.material(evaluationBinding);
          if (material != null && !request.goal().equals(material.goal()))
            throw new TaskFailure(409, ModelErrorCode.EVALUATION_MATERIAL_MISMATCH);
          if (evaluationBinding != null
              && !request
                  .capabilityIds()
                  .equals(
                      List.of(
                          ToolCapability.WEB_FETCH,
                          ToolCapability.READ,
                          ToolCapability.WRITE,
                          ToolCapability.PRESENT)))
            throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
          if (evaluationBinding != null && admission.sampleAlreadyBound(model, evaluationBinding))
            throw new TaskFailure(409, ModelErrorCode.EVALUATION_SAMPLE_ALREADY_BOUND);
          if (budget.queued() >= budget.queueLimit())
            throw new TaskFailure(429, TaskErrorCode.QUEUE_LIMIT);
          String task =
              admission.insert(
                  prior ? callerId : null, key, request, snapshot, digest, installationEpoch);
          admission.bindSnapshots(task, model, taskCost, toolSnapshot, scopeDigest);
          if (sessionRead) {
            admission.createSession(task, historySources);
            admission.reserve(
                task,
                ":session-history",
                ReservationCategory.SESSION_HISTORY_STORAGE,
                SessionHistoryPolicyV1.TASK_RESERVE_BYTES);
          }
          if (directory) {
            admission.createDirectory(task);
            admission.reserve(
                task,
                ":directory-storage",
                ReservationCategory.DIRECTORY_STORAGE,
                DirectoryPolicyV1.STORAGE_BYTES);
          }
          if (todo) {
            admission.createTodo(task);
            admission.reserve(
                task,
                ":todo-storage",
                ReservationCategory.TODO_STORAGE,
                TodoPolicyV1.STORAGE_BYTES);
          }
          if (ask) {
            admission.bindQuestion(task, questionBinding);
            admission.reserve(task, ":question-task", ReservationCategory.QUESTION_TASK, 1);
            admission.reserve(
                task,
                ":question-storage",
                ReservationCategory.QUESTION_STORAGE,
                QuestionPolicyV1.STORAGE_BYTES);
          }
          if (mcpBinding != null) admission.bindMcp(task, mcpBinding);
          if (businessPolicy != null) admission.bindPolicy(task, businessPolicy, taskTokens);
          if (media) {
            long held = input.stream().mapToLong(i -> i.dataBase64().length()).sum();
            if (held > budget.storageRoom())
              throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
            admission.insertImages(
                task, input, descriptors == null ? List.of() : descriptors, held);
          }
          if (evaluationBinding != null)
            admission.bindEvaluation(task, request, model, evaluationBinding, material);
          admission.reserve(task, ":queue", ReservationCategory.QUEUE, 1);
          return admission.accepted(task);
        });
  }
}
