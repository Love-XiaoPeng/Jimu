package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoContentState;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRecords;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoPersistenceService;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.util.Map;
import java.util.UUID;

/** 保留替换/重放资格、预算事件/CAS与owner读取的原顺序。 */
public final class TodoPersistenceServiceImpl implements TodoPersistenceService {
  private final TodoRecords records;

  public TodoPersistenceServiceImpl(TodoRecords records) {
    this.records = records;
  }

  public TodoPort.Result replaceOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    return records.leaseTransaction(
        lease,
        () ->
            records.session(
                lease,
                () -> {
                  var task = records.fenced(lease, true);
                  if (!records.todoTask(task))
                    throw new TaskFailure(403, TodoErrorCode.TODO_NOT_AUTHORIZED);
                  var p = records.todoProjection(task);
                  var candidates =
                      records.todoCandidates(lease.tenant(), lease.taskId(), stepId, call.slot());
                  if (candidates.size() != 1)
                    throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
                  var i = candidates.getFirst();
                  if (!ToolCapability.TODO_WRITE.equals(call.tool())
                      || !call.tool().equals(i.tool())
                      || !call.providerId().equals(i.providerId())
                      || !call.input().equals(i.inputSnapshot())
                      || !records.hash(i.inputSnapshot()).equals(i.inputDigest())
                      || !lease.taskId().equals(i.stepTask())
                      || !"RESPONDED".equals(i.stepState())
                      || !records.hash(i.responseSnapshot()).equals(i.responseDigest()))
                    throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
                  if (i.recordVersion() != 1 || i.stepVersion() != 1)
                    throw new TaskFailure(409, TaskErrorCode.PAYLOAD_VERSION_UNSUPPORTED);
                  var response = records.response(i.responseSnapshot());
                  if (response.version() != 2)
                    throw new TaskFailure(409, TaskErrorCode.PAYLOAD_VERSION_UNSUPPORTED);
                  boolean originalSlot = false;
                  for (int slot = 0; slot < response.calls().size(); slot++) {
                    var wire = response.calls().get(slot);
                    if (new Call(slot + ":" + wire.id(), wire.id(), wire.name(), wire.arguments())
                        .equals(call)) originalSlot = true;
                  }
                  if (!originalSlot)
                    throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
                  var pin = records.tools(task).pins().get(ToolCapability.TODO_WRITE);
                  if (!version.equals(pin.version())
                      || !digest.equals(pin.digest())
                      || !version.equals(i.pluginVersion())
                      || !digest.equals(i.pluginDigest())
                      || pin.installationEpoch() != i.installationEpoch())
                    throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
                  if (i.todoExpectedRevisionObject() == null || i.todoExpectedRevision() < 0)
                    throw new TaskFailure(409, TodoErrorCode.TODO_BINDING_UNSUPPORTED);
                  var result = records.input(i.inputSnapshot());
                  String output = records.json(result), invocation = i.id();
                  if (InvocationState.SUCCEEDED.equals(i.state())) {
                    records.todoResultSource(task, i);
                    if (!output.equals(i.outputSnapshot()))
                      throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
                    records.todoCommitFence(lease);
                    return records.output(i.outputSnapshot());
                  }
                  if (!InvocationState.PENDING.equals(i.state())
                      || i.todoAppliedRevisionObject() != null)
                    throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
                  long expected = i.todoExpectedRevision();
                  if (p.revision() != expected)
                    throw new TaskFailure(409, TodoErrorCode.TODO_REVISION_CONFLICT);
                  var budget = records.bucket(lease.tenant());
                  if (task.toolsUsed() >= task.maxTools()
                      || budget.toolUsed() >= budget.toolLimit())
                    throw new TaskFailure(429, ToolErrorCode.TOOL_BUDGET_EXCEEDED);
                  records.output(output);
                  records.todoStorage(task, records.bytes(output));
                  records.countTodoTool(lease.tenant(), lease.taskId());
                  records.countTodoBudget(lease.tenant());
                  records.reserve(
                      lease.tenant(),
                      lease.taskId(),
                      invocation,
                      invocation,
                      ReservationCategory.TOOL,
                      1,
                      ReservationState.SETTLED,
                      UsageQuality.KNOWN);
                  records.settleTodoReservation(lease.tenant(), invocation);
                  records.event(task, "intent:" + invocation, "tool.dispatched", null);
                  records.event(task, "todo-write:" + invocation, "todo.updated", null);
                  long seq =
                      records.factSequence(
                          lease.tenant(), lease.taskId(), "todo-write:" + invocation);
                  if (records.completeTodoInvocation(
                          output,
                          records.hash(output),
                          expected + 1,
                          lease.attemptId(),
                          lease.fence(),
                          seq,
                          lease.tenant(),
                          invocation)
                      != 1) throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
                  if (records.advanceTodoRevision(
                          invocation,
                          stepId,
                          lease.attemptId(),
                          lease.fence(),
                          seq,
                          result.counts().pending(),
                          result.counts().inProgress(),
                          result.counts().completed(),
                          records.hash(output),
                          lease.tenant(),
                          lease.taskId(),
                          expected)
                      != 1) throw new TaskFailure(409, TodoErrorCode.TODO_REVISION_CONFLICT);
                  records.event(task, "result:" + invocation, "tool.completed", null);
                  records.todoCommitFence(lease);
                  return result;
                }));
  }

  @Override
  public Map<String, Object> getTodos(Identity identity, String taskId) {
    try {
      UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    }
    return records.transaction(
        () -> {
          var task = records.visible(identity, taskId);
          records.access(task);
          var r = records.todoOwnerView(identity.tenant(), taskId, identity.actor());
          var view = new java.util.LinkedHashMap<String, Object>();
          view.put("version", 1);
          view.put("taskId", taskId);
          view.put("asOfSeq", r.nextEventSeq());
          boolean absent = r.taskIdObject() == null;
          view.put("revision", absent ? 0 : r.revision());
          view.put("state", absent ? "ABSENT" : r.contentState());
          view.put("todos", null);
          view.put("counts", null);
          view.put("source", null);
          view.put("retainUntil", absent ? null : r.retainUntil().toString());
          if (!absent && r.revision() > 0) {
            if (!taskId.equals(r.invocationTask())
                || !r.sourceStepId().equals(r.invocationStep())
                || r.revision() != r.todoAppliedRevision()
                || !r.committedAttemptId().equals(r.todoCommittedAttemptId())
                || r.committedFence() != r.todoCommittedFence()
                || r.updatedSeq() != r.todoEventSeq()
                || !r.outputDigest().equals(r.invocationDigest()))
              throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
            records.todoResultSource(task, r);
            var counts =
                new io.github.lovexiaopeng.jimu.plugin.api.TodoPort.Counts(
                    (int) r.pendingCount(), (int) r.inProgressCount(), (int) r.completedCount());
            if (TodoContentState.PRESENT.equals(r.contentState())) {
              var output = records.output(r.outputSnapshot());
              if (!counts.equals(output.counts()))
                throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
              view.put("todos", output.todos());
            }
            view.put("counts", counts);
            view.put(
                "source",
                Map.of(
                    "invocationId",
                    r.sourceInvocationId(),
                    "checkpointId",
                    r.sourceStepId(),
                    "providerCallId",
                    r.providerId(),
                    "createdAttemptId",
                    r.attemptId(),
                    "committedAttemptId",
                    r.committedAttemptId(),
                    "fence",
                    r.committedFence(),
                    "eventId",
                    taskId + ":" + r.updatedSeq(),
                    "outputSha256",
                    r.outputDigest(),
                    "updatedAt",
                    r.todoCommittedAt().toString()));
          }
          records.view(view);
          return view;
        });
  }
}
