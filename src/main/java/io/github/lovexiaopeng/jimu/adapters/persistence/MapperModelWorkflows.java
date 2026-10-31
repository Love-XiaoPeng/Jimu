package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.model.api.DispatchState;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 模型流程的实际 Mapper/模块端口；沿原调用者 DS/UoW，不直接执行 SQL。 */
final class MapperModelWorkflows implements ModelWorkflowRecords {
  private final JdbcLedger ledger;

  MapperModelWorkflows(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public SnapshotAccess lockModelSnapshot(Lease lease) {
    var evaluation = ledger.evaluation(lease);
    var task = ledger.fenced(lease, true);
    return new SnapshotAccess() {
      public ModelSnapshot model() {
        return ledger.snapshot(task);
      }

      public void checkEvaluation(ModelSnapshot snapshot) {
        ledger.checkEvaluation(task, evaluation, snapshot);
      }
    };
  }

  @Override
  public ToolSnapshot lockToolSnapshot(Lease lease) {
    return ledger.tools(ledger.fenced(lease, true));
  }

  @Override
  public Admission lockAdmission(Lease lease, String stepId) {
    ledger.bindSessionQuery(lease, stepId, null);
    var evaluation = ledger.evaluation(lease);
    var task = ledger.fenced(lease, true);
    var model = ledger.snapshot(task);
    ledger.models.admit(model);
    var budget = ledger.bucket(lease.tenant());
    var step =
        JdbcLedger.first(
            ledger
                .mappers
                .steps()
                .selectModelStep(ledger.queryContext(), lease.tenant(), stepId, lease.taskId()));
    return new Admission() {
      public void validateStep() {
        if (JdbcSessionRecords.task(task)) {
          ledger.sessions.validateStep(task, step);
          ledger.sessions.metadata(task, false, 4096);
        }
      }

      public boolean responded() {
        return step.get("response_snapshot") != null;
      }

      public void validateDirectory() {
        if (ledger.directoryTask(task)) {
          var p = ledger.directoryProjection(task);
          if (step.get("directory_revision") == null
              || ledger.n(step, "directory_revision") != ledger.n(p, "revision")
              || !ledger.s(p, "cwd").equals(ledger.s(step, "directory_cwd"))
              || !ledger.s(p, "binding_digest").equals(ledger.s(step, "directory_binding_digest")))
            throw new TaskFailure(409, DirectoryErrorCode.DIRECTORY_REVISION_CONFLICT);
          if (ledger.n(task, "models_used") >= 8)
            throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_EXECUTION_LIMIT);
          ledger.directoryStorage(task, 131072);
        }
      }

      public Request request() {
        var wire =
            Payloads.read(
                ledger.s(step, "request_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
        return new Request(
            ledger.s(step, "request_snapshot"),
            wire.path("requestBody").asText(),
            wire.path("bodyDigest").asText(),
            wire.path("configDigest").asText(),
            ModelProfiles.digest(model));
      }

      public void validateEvaluationAndMaterial() {
        ledger.checkEvaluation(task, evaluation, model);
        ledger.materialBody(
            task, ledger.s(step, "request_snapshot"), (int) ledger.n(step, "ordinal"));
      }

      public void validateInput(String body) {
        ledger.models.checkInput(model, body);
      }

      public Limits limits() {
        return new Limits(
            model.paid(),
            model.price() == null ? 0 : model.price().inputTokenUpper(),
            model.maxOutputTokens(),
            model.paid()
                ? ModelCosts.amount(
                    model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                : 0,
            evaluation != null,
            evaluation == null ? null : ledger.s(evaluation, "state"),
            evaluation == null ? 0 : ledger.n(evaluation, "requests_used"),
            evaluation == null ? 0 : ledger.n(evaluation, "max_requests"),
            evaluation == null
                ? 0
                : ledger.n(evaluation, "max_cost_micros") - ledger.n(evaluation, "cost_held"),
            model.paid() ? ledger.n(task, "max_cost_micros") - ledger.n(task, "cost_held") : 0,
            model.paid() ? ledger.n(budget, "cost_limit") - ledger.n(budget, "cost_held") : 0,
            ledger.n(step, "dispatches"),
            ledger.n(task, "models_used"),
            ledger.n(task, "max_models"),
            ledger.n(budget, "model_used"),
            ledger.n(budget, "model_limit"),
            ledger.n(budget, "token_held"),
            ledger.n(budget, "token_limit"),
            ledger.n(task, "token_held"),
            ledger.n(task, "max_tokens"));
      }

      public PlanAllowance planAllowance() {
        if (task.get("evaluation_snapshot") == null) return null;
        var plan = ledger.plan(evaluation);
        var usage =
            ledger
                .mappers
                .dispatches()
                .selectEvaluationUsage(
                    ledger.queryContext(), ledger.s(task, "tenant_id"), ledger.s(evaluation, "id"));
        return new PlanAllowance(
            ledger.s(task, "evaluation_phase"),
            ledger.s(task, "evaluation_sample"),
            model.model(),
            plan.candidates().size(),
            "A".equals(ledger.s(task, "evaluation_phase"))
                ? plan.phaseACostMicros()
                : plan.phaseBCostMicros(),
            usage.stream()
                .map(
                    row ->
                        new PlanUsage(
                            ledger.s(row, "evaluation_phase"),
                            ledger.s(row, "evaluation_sample"),
                            ledger.s(row, "evaluation_model"),
                            ledger.n(row, "calls"),
                            ledger.n(row, "cost")))
                .toList());
      }

      public boolean activePlanDispatch() {
        return !ledger
            .mappers
            .dispatches()
            .selectActiveEvaluationDispatch(
                ledger.queryContext(), ledger.s(task, "tenant_id"), ledger.s(evaluation, "id"))
            .isEmpty();
      }

      public void reserveModuleStorage() {
        ledger.questionStorage(task, 131072);
        ledger.todoStorage(task, 131072);
      }

      public void reserveCost(String dispatch, long cost) {
        ledger
            .mappers
            .evaluations()
            .reserveModelCost(
                ledger.queryContext(), cost, lease.tenant(), ledger.s(evaluation, "id"));
        ledger.mappers.budgets().adjustModelCost(ledger.queryContext(), cost, lease.tenant());
        ledger
            .mappers
            .tasks()
            .adjustModelCost(ledger.queryContext(), cost, lease.tenant(), lease.taskId());
        ledger.reserve(
            lease.tenant(),
            lease.taskId(),
            dispatch + ":cost",
            dispatch,
            ReservationCategory.MODEL_COST,
            cost,
            ReservationState.HELD_UNKNOWN,
            UsageQuality.UNKNOWN);
      }

      public void insertDispatch(String dispatch, String bodyDigest, long cost) {
        var row =
            new io.github.lovexiaopeng.jimu.adapters.persistence.model.entity.ModelDispatchEntity();
        row.setTenantId(lease.tenant());
        row.setDispatchId(dispatch);
        row.setTaskId(lease.taskId());
        row.setStepId(stepId);
        row.setConfigDigest(ledger.s(task, "model_digest"));
        row.setRequestDigest(bodyDigest);
        row.setState(DispatchState.DISPATCHED);
        row.setPriceVersion(model.price() == null ? null : model.price().version());
        row.setCurrency(model.paid() ? "CNY" : null);
        row.setReservedCost(model.paid() ? cost : null);
        ledger.mappers.insertDispatch(ledger.queryContext(), row);
      }

      public void reserveTokens(String dispatch, long upper) {
        ledger.mappers.budgets().reserveModelTokens(ledger.queryContext(), upper, lease.tenant());
        ledger
            .mappers
            .tasks()
            .reserveModelTokens(ledger.queryContext(), upper, lease.tenant(), lease.taskId());
        if (step.get("dispatch_id") != null)
          ledger
              .mappers
              .reservations()
              .holdPreviousDispatchUnknown(
                  ledger.queryContext(), lease.tenant(), ledger.s(step, "dispatch_id"));
        ledger.reserve(
            lease.tenant(),
            lease.taskId(),
            dispatch,
            dispatch,
            ReservationCategory.MODEL,
            upper,
            ReservationState.RESERVED,
            UsageQuality.UNKNOWN);
      }

      public void markRunning(String dispatch) {
        ledger
            .mappers
            .steps()
            .markModelRunning(ledger.queryContext(), dispatch, lease.tenant(), stepId);
      }

      public void dispatched(String dispatch) {
        ledger.event(task, "model:" + dispatch, "model.dispatched", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task) || JdbcSessionScopes.lineage(task))
          ledger.todoCommitFence(lease);
      }
    };
  }

  @Override
  public Response lockResponse(Lease lease, String stepId, String dispatch) {
    ledger.bindSessionQuery(lease, stepId, dispatch);
    var evaluation = ledger.evaluation(lease, false);
    var task = ledger.measured("M2.lock", () -> ledger.fenced(lease, false));
    var step =
        JdbcLedger.first(
            ledger
                .mappers
                .steps()
                .selectModelStep(ledger.queryContext(), lease.tenant(), stepId, lease.taskId()));
    return new Response() {
      public boolean originalDispatch() {
        return dispatch.equals(ledger.s(step, "dispatch_id"))
            && step.get("response_snapshot") == null;
      }

      public boolean ordinaryRejection(TaskFailure failure) {
        if (!JdbcSessionScopes.lineage(task)) return true;
        var query = ledger.queryContext();
        query.remainingMillis();
        var resources = query.resources();
        if (resources.reads() > 256
            || resources.bytes() > 1048576
            || resources.operations() > 65536
            || query.scratchPeak() > 1048576) return false;
        return java.util.Set.of(
                "TOOL_INPUT_INVALID",
                "TOOL_NOT_AUTHORIZED",
                "MODEL_RESPONSE_TOO_LARGE",
                "TOOL_BUDGET_EXCEEDED",
                "SESSION_HISTORY_LIMIT",
                "CAPABILITY_REVOKED",
                "ACTOR_REVOKED",
                "SESSION_SCOPE_UNAVAILABLE",
                "SESSION_HISTORY_EXPIRED")
            .contains(failure.code());
      }

      public void settlementOnly() {
        if (JdbcSessionScopes.lineage(task))
          new MapperCommitFence(ledger, lease).settlement(dispatch);
      }

      public io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords costs() {
        return new MapperModelCosts(ledger, lease, task, evaluation, dispatch);
      }

      public String costRejection() {
        return ledger.s(
            JdbcLedger.first(
                ledger
                    .mappers
                    .dispatches()
                    .selectRejection(ledger.queryContext(), lease.tenant(), dispatch)),
            "code");
      }

      public boolean constrained() {
        return ledger.todoTask(task) || ledger.directoryTask(task) || JdbcSessionRecords.task(task);
      }

      public void validatePayload(String response, List<Call> calls) {
        ledger.access(task);
        if (JdbcSessionRecords.task(task)) {
          ledger.sessionAdmission(task);
          ledger.sessions.response(task, response, calls);
        } else if (ledger.directoryTask(task)) ledger.directoryAdmission(task);
        else {
          if (!ledger.todosEnabled) throw new TaskFailure(403, TodoErrorCode.TODO_FEATURE_DISABLED);
          ledger.models.requireTodo(ledger.snapshot(task));
        }
        if (task.get("cancel_requested_at") != null)
          throw new TaskFailure(409, TaskErrorCode.CANCEL_REQUESTED);
        if (ledger.n(task, "within_deadline") != 1)
          throw new TaskFailure(409, TaskErrorCode.DEADLINE_EXCEEDED);
        io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.unicode(response);
        long invocations =
            ledger
                .mappers
                .invocations()
                .countTaskInvocationsScalar(ledger.queryContext(), lease.tenant(), lease.taskId());
        if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(response) > 65536
            || calls.size() > 4)
          throw new TaskFailure(409, ModelErrorCode.MODEL_RESPONSE_TOO_LARGE);
        if (invocations + calls.size() > 20)
          throw new TaskFailure(
              429,
              ledger.directoryTask(task)
                  ? DirectoryErrorCode.DIRECTORY_EXECUTION_LIMIT
                  : TodoErrorCode.TODO_EXECUTION_LIMIT);
        if (invocations + calls.size() > ledger.n(task, "max_tools"))
          throw new TaskFailure(429, ToolErrorCode.TOOL_BUDGET_EXCEEDED);
        for (var call : calls) {
          if (JdbcSessionRecords.task(task)) {
            ledger.sessions.validateInput(task, call);
          } else if (ledger.directoryTask(task)) {
            io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.unicode(
                call.providerId());
            if (call.providerId().isEmpty()
                || call.providerId().length() > 256
                || !ledger.caps(task).contains(call.tool()))
              throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
            if (call.tool().equals(ToolCapability.WORKING_DIRECTORY))
              ledger.directories.input(call.input());
            else {
              try {
                ledger
                    .directoryFileContract(call.tool())
                    .in()
                    .validate(ledger.directories.json(call.input(), 65535));
              } catch (IllegalArgumentException invalid) {
                throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
              }
            }
          } else {
            if (!ToolCapability.TODO_WRITE.equals(call.tool()))
              throw new TaskFailure(403, ToolErrorCode.TOOL_NOT_AUTHORIZED);
            ledger.todos.input(call.input());
          }
        }
        ledger.directoryStorage(
            task,
            response.getBytes(StandardCharsets.UTF_8).length
                + calls.stream()
                    .mapToLong(c -> c.input().getBytes(StandardCharsets.UTF_8).length)
                    .sum());
        if (JdbcSessionRecords.task(task))
          ledger.sessions.body(
              task,
              response.getBytes(StandardCharsets.UTF_8).length
                  + calls.stream()
                      .mapToLong(c -> c.input().getBytes(StandardCharsets.UTF_8).length)
                      .sum());
        ledger.todoStorage(
            task,
            io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(response)
                + calls.stream()
                    .mapToLong(
                        c ->
                            io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                                c.input()))
                    .sum());
      }

      public long rejectedPayloadUpper() {
        long upper =
            ledger
                .mappers
                .reservations()
                .selectTokenAmountScalar(ledger.queryContext(), lease.tenant(), dispatch);
        return upper;
      }

      public long reservedTokens() {
        return ledger.n(
            JdbcLedger.first(
                ledger
                    .mappers
                    .reservations()
                    .selectTokenAmount(ledger.queryContext(), lease.tenant(), dispatch)),
            "amount");
      }

      public void settleTokens(long tokens, long released) {
        ledger
            .mappers
            .budgets()
            .releaseModelTokens(ledger.queryContext(), released, lease.tenant());
        ledger
            .mappers
            .tasks()
            .releaseModelTokens(ledger.queryContext(), released, lease.tenant(), lease.taskId());
        ledger
            .mappers
            .reservations()
            .settleModelTokens(ledger.queryContext(), tokens, lease.tenant(), dispatch);
      }

      public void holdUnknown() {
        ledger
            .mappers
            .reservations()
            .holdModelUnknown(ledger.queryContext(), lease.tenant(), dispatch);
      }

      public void rejectStep() {
        ledger.mappers.steps().rejectModelStep(ledger.queryContext(), lease.tenant(), stepId);
      }

      public void recordRejection(String code) {
        ledger
            .mappers
            .dispatches()
            .recordRejection(ledger.queryContext(), code, lease.tenant(), dispatch);
      }

      public void rejected(String code) {
        ledger.event(task, "response:" + stepId, "model.rejected", code);
      }

      public boolean question() {
        return ledger.questionTask(task);
      }

      public void rejectOversizedQuestion() {
        ledger.mappers.steps().rejectModelStep(ledger.queryContext(), lease.tenant(), stepId);
        ledger
            .mappers
            .dispatches()
            .rejectOversizedResponse(ledger.queryContext(), lease.tenant(), dispatch);
        ledger
            .mappers
            .reservations()
            .holdReservedModelUnknown(ledger.queryContext(), lease.tenant(), dispatch);
        ledger.event(
            task, "response:" + stepId, "model.rejected", ModelErrorCode.MODEL_RESPONSE_TOO_LARGE);
      }

      public void reserveQuestionStorage(long bytes) {
        ledger.questionStorage(task, bytes);
      }

      public void storeResponse(String response) {
        var owner =
            io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
                ledger.queryContext());
        if (owner != null) owner.protectedWrite();
        ledger
            .mappers
            .steps()
            .storeModelResponse(
                ledger.queryContext(), response, Payloads.hash(response), lease.tenant(), stepId);
      }

      public String modelRejection() {
        return ledger.modelRejection(task);
      }

      public String runRejection() {
        return ledger.runRejection(evaluation);
      }

      public void requireScope() {
        ledger.requireScope(lease.tenant(), evaluation, task, null);
      }

      public int invocationCount() {
        return ledger
            .mappers
            .invocations()
            .countAttemptInvocationsScalar(
                ledger.queryContext(), lease.tenant(), lease.attemptId());
      }

      public long todoRevision() {
        return ledger.todoTask(task) ? ledger.n(ledger.todoProjection(task), "revision") : 0;
      }

      public void insertCall(Call call, int ordinal, long todoExpected) {
        var plugin = ledger.tools(task).pins().get(call.tool());
        var row =
            new io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity
                .InvocationEntity();
        row.setTenantId(lease.tenant());
        row.setInvocationId(ledger.id());
        row.setTaskId(lease.taskId());
        row.setStepId(stepId);
        row.setAttemptId(lease.attemptId());
        row.setOrdinal(ordinal);
        row.setSlot(call.slot());
        row.setProviderId(call.providerId());
        row.setEffectIntentId(ledger.id());
        row.setTool(call.tool());
        row.setPluginVersion(plugin.version());
        row.setPluginDigest(plugin.digest());
        row.setInstallationEpoch(plugin.installationEpoch());
        row.setInputSnapshot(call.input());
        row.setInputDigest(Payloads.hash(call.input()));
        row.setState(InvocationState.PENDING);
        ledger.mappers.insertInvocation(ledger.queryContext(), row);
        if (ledger.todoTask(task))
          ledger
              .mappers
              .invocations()
              .bindTodoRevision(
                  ledger.queryContext(),
                  todoExpected,
                  lease.tenant(),
                  lease.taskId(),
                  stepId,
                  call.slot());
      }

      public void bindCalls() {
        if (JdbcSessionRecords.task(task)) ledger.sessions.bindCalls(task, stepId);
      }

      public void responded() {
        if (JdbcSessionScopes.lineage(task)) ledger.todoCommitFence(lease);
        ledger.measured(
            "M2.event",
            () -> {
              ledger.event(task, "response:" + stepId, "model.responded", null);
              return null;
            });
      }
    };
  }

  @Override
  public Outcome outcome(Lease lease, String dispatch) {
    java.util.function.Supplier<Outcome> read =
        () -> {
          var row =
              JdbcLedger.first(
                  ledger
                      .mappers
                      .dispatches()
                      .selectOutcome(ledger.queryContext(), lease.tenant(), dispatch));
          return new Outcome(ledger.s(row, "state"), ledger.s(row, "code"));
        };
    // 必须在结算提交之后读取拒绝；仍沿原query截止，不包住整个responded。
    return ledger.measured(
        "M2.outcome",
        () ->
            ledger.queryContext() == null
                ? read.get()
                : ledger.sessionTransaction(read, ledger.queryContext().deadlineNanos()));
  }
}
