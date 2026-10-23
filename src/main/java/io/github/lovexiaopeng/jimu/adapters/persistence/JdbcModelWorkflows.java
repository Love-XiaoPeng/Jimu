package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transitional named SQL/module ports; all work joins the original shared DS/UoW. */
final class JdbcModelWorkflows implements ModelWorkflowRecords {
  private final JdbcLedger ledger;
  private final JdbcTemplate db;

  JdbcModelWorkflows(JdbcLedger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
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
    var evaluation = ledger.evaluation(lease);
    var task = ledger.fenced(lease, true);
    var model = ledger.snapshot(task);
    ledger.models.admit(model);
    var budget = ledger.bucket(lease.tenant());
    var step =
        ledger.one(
            "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
            lease.tenant(),
            stepId,
            lease.taskId());
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
            throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
          if (ledger.n(task, "models_used") >= 8)
            throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
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

      public void admitPlan(long cost) {
        if (task.get("evaluation_snapshot") != null)
          ledger.admitPlanDispatch(task, evaluation, model, cost);
      }

      public void reserveModuleStorage() {
        ledger.questionStorage(task, 131072);
        ledger.todoStorage(task, 131072);
      }

      public void reserveCost(String dispatch, long cost) {
        db.update(
            "UPDATE evaluation_budget SET requests_used=requests_used+1,cost_held=cost_held+? WHERE tenant_id=? AND id=?",
            cost,
            lease.tenant(),
            ledger.s(evaluation, "id"));
        db.update(
            "UPDATE tenant_budget SET cost_held=cost_held+? WHERE tenant_id=?",
            cost,
            lease.tenant());
        db.update(
            "UPDATE task SET cost_held=cost_held+? WHERE tenant_id=? AND id=?",
            cost,
            lease.tenant(),
            lease.taskId());
        ledger.reserve(
            lease.tenant(),
            lease.taskId(),
            dispatch + ":cost",
            dispatch,
            "MODEL_COST",
            cost,
            "HELD_UNKNOWN",
            "UNKNOWN");
      }

      public void insertDispatch(String dispatch, String bodyDigest, long cost) {
        db.update(
            "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state,price_version,currency,reserved_cost) VALUES(?,?,?,?,?,?,'DISPATCHED',?,?,?)",
            lease.tenant(),
            dispatch,
            lease.taskId(),
            stepId,
            ledger.s(task, "model_digest"),
            bodyDigest,
            model.price() == null ? null : model.price().version(),
            model.paid() ? "CNY" : null,
            model.paid() ? cost : null);
      }

      public void reserveTokens(String dispatch, long upper) {
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
              ledger.s(step, "dispatch_id"));
        ledger.reserve(
            lease.tenant(),
            lease.taskId(),
            dispatch,
            dispatch,
            "MODEL",
            upper,
            "RESERVED",
            "UNKNOWN");
      }

      public void markRunning(String dispatch) {
        db.update(
            "UPDATE step SET state='RUNNING',dispatch_id=?,dispatches=dispatches+1 WHERE tenant_id=? AND id=?",
            dispatch,
            lease.tenant(),
            stepId);
      }

      public void dispatched(String dispatch) {
        ledger.event(task, "model:" + dispatch, "model.dispatched", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task)) ledger.todoCommitFence(lease);
      }
    };
  }

  @Override
  public Response lockResponse(Lease lease, String stepId, String dispatch) {
    var evaluation = ledger.evaluation(lease, false);
    var task = ledger.fenced(lease, false);
    var step =
        ledger.one(
            "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
            lease.tenant(),
            stepId,
            lease.taskId());
    return new Response() {
      public boolean originalDispatch() {
        return dispatch.equals(ledger.s(step, "dispatch_id"))
            && step.get("response_snapshot") == null;
      }

      public boolean settleCost(ModelUsage usage) {
        return ledger.settleCost(lease, task, evaluation, dispatch, usage);
      }

      public String costRejection() {
        return ledger.s(
            ledger.one(
                "SELECT code FROM model_dispatch WHERE tenant_id=? AND id=?",
                lease.tenant(),
                dispatch),
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
          if (!ledger.todosEnabled) throw new TaskFailure(403, "TODO_FEATURE_DISABLED");
          ledger.models.requireTodo(ledger.snapshot(task));
        }
        if (task.get("cancel_requested_at") != null) throw new TaskFailure(409, "CANCEL_REQUESTED");
        if (ledger.n(task, "within_deadline") != 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
        io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.unicode(response);
        long invocations =
            db.queryForObject(
                "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND task_id=?",
                Long.class,
                lease.tenant(),
                lease.taskId());
        if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(response) > 65536
            || calls.size() > 4) throw new TaskFailure(409, "MODEL_RESPONSE_TOO_LARGE");
        if (invocations + calls.size() > 20)
          throw new TaskFailure(
              429,
              ledger.directoryTask(task) ? "DIRECTORY_EXECUTION_LIMIT" : "TODO_EXECUTION_LIMIT");
        if (invocations + calls.size() > ledger.n(task, "max_tools"))
          throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
        for (var call : calls) {
          if (JdbcSessionRecords.task(task)) {
            ledger.sessions.validateInput(task, call);
          } else if (ledger.directoryTask(task)) {
            io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.unicode(
                call.providerId());
            if (call.providerId().isEmpty()
                || call.providerId().length() > 256
                || !ledger.caps(task).contains(call.tool()))
              throw new TaskFailure(400, "TOOL_INPUT_INVALID");
            if (call.tool().equals("working_directory")) ledger.directories.input(call.input());
            else {
              try {
                ledger
                    .directoryFileContract(call.tool())
                    .in()
                    .validate(ledger.directories.json(call.input(), 65535));
              } catch (IllegalArgumentException invalid) {
                throw new TaskFailure(400, "TOOL_INPUT_INVALID");
              }
            }
          } else {
            if (!"todo_write".equals(call.tool()))
              throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
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
            db.queryForObject(
                "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                Long.class,
                lease.tenant(),
                dispatch);
        return upper;
      }

      public long reservedTokens() {
        return ledger.n(
            ledger.one(
                "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                lease.tenant(),
                dispatch),
            "amount");
      }

      public void settleTokens(long tokens, long released) {
        db.update(
            "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
            released,
            lease.tenant());
        db.update(
            "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
            released,
            lease.tenant(),
            lease.taskId());
        db.update(
            "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
            tokens,
            lease.tenant(),
            dispatch);
      }

      public void holdUnknown() {
        db.update(
            "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch);
      }

      public void rejectStep() {
        db.update(
            "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?", lease.tenant(), stepId);
      }

      public void recordRejection(String code) {
        db.update(
            "UPDATE model_dispatch SET code=? WHERE tenant_id=? AND id=?",
            code,
            lease.tenant(),
            dispatch);
      }

      public void rejected(String code) {
        ledger.event(task, "response:" + stepId, "model.rejected", code);
      }

      public boolean question() {
        return ledger.questionTask(task);
      }

      public void rejectOversizedQuestion() {
        db.update(
            "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?", lease.tenant(), stepId);
        db.update(
            "UPDATE model_dispatch SET state='FAILED',code='MODEL_RESPONSE_TOO_LARGE' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch);
        db.update(
            "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=? AND state='RESERVED'",
            lease.tenant(),
            dispatch);
        ledger.event(task, "response:" + stepId, "model.rejected", "MODEL_RESPONSE_TOO_LARGE");
      }

      public void reserveQuestionStorage(long bytes) {
        ledger.questionStorage(task, bytes);
      }

      public void storeResponse(String response) {
        db.update(
            "UPDATE step SET state='RESPONDED',response_snapshot=?,response_digest=? WHERE tenant_id=? AND id=?",
            response,
            Payloads.hash(response),
            lease.tenant(),
            stepId);
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
        return db.queryForObject(
            "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND attempt_id=?",
            Integer.class,
            lease.tenant(),
            lease.attemptId());
      }

      public long todoRevision() {
        return ledger.todoTask(task) ? ledger.n(ledger.todoProjection(task), "revision") : 0;
      }

      public void insertCall(Call call, int ordinal, long todoExpected) {
        var plugin = ledger.tools(task).pins().get(call.tool());
        db.update(
            "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
            lease.tenant(),
            ledger.id(),
            lease.taskId(),
            stepId,
            lease.attemptId(),
            ordinal,
            call.slot(),
            call.providerId(),
            ledger.id(),
            call.tool(),
            plugin.version(),
            plugin.digest(),
            plugin.installationEpoch(),
            call.input(),
            Payloads.hash(call.input()));
        if (ledger.todoTask(task))
          db.update(
              "UPDATE invocation SET todo_expected_revision=? WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=?",
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
        ledger.event(task, "response:" + stepId, "model.responded", null);
      }
    };
  }

  @Override
  public Outcome outcome(Lease lease, String dispatch) {
    var row =
        ledger.one(
            "SELECT state,code FROM model_dispatch WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch);
    return new Outcome(ledger.s(row, "state"), ledger.s(row, "code"));
  }
}
