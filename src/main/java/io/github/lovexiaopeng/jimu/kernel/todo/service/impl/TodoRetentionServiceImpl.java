package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoContentState;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRetentions;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRetentions.Body;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRetentions.RetainedBody;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoRetentionService;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 同原短事务、短路顺序、事件先于reservation释放；不改变原截止提交边界。 */
public final class TodoRetentionServiceImpl implements TodoRetentionService {
  private final TodoRetentions records;
  private final LedgerUnitOfWork work;
  private final SnapshotDigest digest;

  public TodoRetentionServiceImpl(
      TodoRetentions records, LedgerUnitOfWork work, SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.digest = digest;
  }

  private void retain(List<RetainedBody> retained, String prefix, List<Body> bodies) {
    for (var body : bodies)
      if (body.value() != null)
        retained.add(
            new RetainedBody(
                prefix + body.field(),
                digest.hash(body.value()),
                body.value().getBytes(StandardCharsets.UTF_8).length));
  }

  private void binding(TodoRetentions.Projection p) {
    if (p.recordVersion() != 1 || p.todoVersion() != 1)
      throw new TaskFailure(409, TodoErrorCode.TODO_BINDING_UNSUPPORTED);
    if (!digest.hash(p.binding()).equals(p.bindingDigest()))
      throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
    var versions = records.bindingVersions(p.binding());
    if (versions.version() != 1 || versions.todoVersion() != 1)
      throw new TaskFailure(409, TodoErrorCode.TODO_BINDING_UNSUPPORTED);
    if (!records.expectedBinding().equals(p.binding()))
      throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
  }

  public void cleanup() {
    for (var candidate : records.candidates())
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            var task = records.lockTask(candidate);
            var p = task.projection();
            binding(p);
            if (TodoContentState.PURGED.equals(p.state())
                || p.retainUntil().isAfter(records.now())
                || !List.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)
                    .contains(task.state())
                || !task.writersQuiescent()) return null;
            if (task.unsafeReservations() || task.activeInvocations() || task.dispatchedModels())
              return null;
            var retained = new ArrayList<RetainedBody>();
            retain(retained, "task:", task.bodies());
            String tombstone = "{\"version\":1,\"state\":\"TODO_PURGED\"}";
            for (var step : task.steps()) {
              retain(retained, "step:" + step.id() + ":", step.bodies());
              task.purgeStep(step.id(), tombstone);
            }
            for (var invocation : task.invocations()) {
              retain(retained, "invocation:" + invocation.id() + ":", invocation.bodies());
              task.purgeInvocation(invocation.id(), tombstone);
            }
            for (var dispatch : task.dispatches()) {
              retain(retained, "dispatch:" + dispatch.id() + ":", dispatch.bodies());
              task.purgeDispatch(dispatch.id(), tombstone);
            }
            String metadata = records.metadata(retained);
            if (metadata.getBytes(StandardCharsets.UTF_8).length > TodoPolicyV1.METADATA_BYTES)
              throw new TaskFailure(429, TodoErrorCode.TODO_STORAGE_LIMIT);
            task.purgeTask(tombstone);
            task.markPurged(metadata);
            task.event();
            if (task.releaseHold() != 1)
              throw new TaskFailure(409, BudgetErrorCode.RESERVATION_STATE);
            task.releaseBudget();
            return null;
          });
  }
}
