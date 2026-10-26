package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionOutcome;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads.Commit;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads.Read;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads.Task;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionReadService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.util.List;

/** 原生协议保持证明顺序；已结算的拒绝在事务提交后抛出。 */
public final class SessionReadServiceImpl implements SessionReadService {
  private final SessionReads records;

  public SessionReadServiceImpl(SessionReads records) {
    this.records = records;
  }

  private record Outcome(String value, TaskFailure rejection) {}

  public String readOrResult(Lease lease, String step, Call call, String version, String digest) {
    var result = records.required(() -> execute(lease, step, call, version, digest));
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private Outcome execute(Lease lease, String step, Call call, String version, String digest) {
    var task = records.lock(lease);
    if (!task.enabled()) throw new TaskFailure(403, SessionErrorCode.SESSION_READ_DISABLED);
    var invocation = task.originalSlot(step, call, version, digest);
    var input = task.input(call.input());
    if (!call.tool().equals(ToolCapability.SESSION_EVENT_READ))
      throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
    String history = task.historyBinding();
    var st = task.validatedStep(step);
    var binding = task.lockBinding(invocation.id());
    if (binding.version() != 1
        || !lease.taskId().equals(binding.task())
        || !step.equals(binding.step())
        || !history.equals(binding.binding())
        || !history.equals(st.binding())
        || binding.upper() != st.upper()
        || !records.hash(call.input()).equals(binding.input())
        || binding.target() != input.seq()
        || binding.before() != input.before()
        || binding.after() != input.after()) throw corrupt();
    if (task.trace() && binding.relation() != 1) throw corrupt();
    if (!InvocationState.PENDING.equals(invocation.state())) {
      if (!invocation.state().equals(binding.outcome())) throw corrupt();
      task.verifyReceipt(binding.receipt());
      finish(task);
      if (SessionOutcome.FAILED.equals(binding.outcome()))
        return new Outcome(
            null, new TaskFailure(failureStatus(binding.failure()), binding.failure()));
      if (!SessionOutcome.SUCCEEDED.equals(binding.outcome())) throw corrupt();
      task.validateOutput(invocation.output());
      return new Outcome(invocation.output(), null);
    }
    if (!SessionOutcome.PENDING.equals(binding.outcome())) throw corrupt();
    var budget = task.budget();
    if (task.toolsUsed() >= task.maxTools()
        || task.toolsUsed() >= 20
        || budget.used() >= budget.limit())
      throw new TaskFailure(429, ToolErrorCode.TOOL_BUDGET_EXCEEDED);
    Read read = null;
    TaskFailure failure = null;
    try {
      read = task.read(input, binding.upper());
    } catch (TaskFailure rejected) {
      if (!List.of(
              SessionErrorCode.SESSION_EVENT_NOT_FOUND,
              SessionErrorCode.SESSION_HISTORY_CORRUPT,
              SessionErrorCode.SESSION_HISTORY_VERSION_UNSUPPORTED,
              SessionErrorCode.SESSION_HISTORY_LIMIT)
          .contains(rejected.code())) throw rejected;
      failure = rejected;
    }
    String output = read == null ? null : read.output(),
        code = failure == null ? null : failure.code();
    if (output != null) task.body(records.bytes(output));
    task.consumeTask();
    task.consumeTenant();
    task.reserve(invocation.id());
    task.settle(invocation.id());
    String outcome = failure == null ? SessionOutcome.SUCCEEDED : SessionOutcome.FAILED;
    task.finishInvocation(
        invocation.id(), outcome, output, output == null ? null : records.hash(output));
    long receipt = task.nextSequence();
    String sources = task.trace() ? (read == null ? "[]" : read.sources()) : null;
    task.commitRead(
        invocation.id(),
        new Commit(
            outcome,
            code,
            read == null ? null : Long.valueOf(read.from()),
            read == null ? null : Long.valueOf(read.to()),
            read == null ? null : read.targetDigest(),
            output == null ? null : records.hash(output),
            output == null ? 0 : records.bytes(output),
            receipt,
            sources,
            sources == null ? null : records.hash(sources),
            sources == null ? null : Integer.valueOf(records.bytes(sources))));
    if (task.trace()) task.body(records.bytes(sources));
    task.event("intent:" + invocation.id(), "tool.dispatched", null);
    task.event(
        "result:" + invocation.id(), failure == null ? "tool.completed" : "tool.failed", code);
    task.appendRead(step, invocation);
    finish(task);
    return new Outcome(output, failure);
  }

  private static void finish(Task task) {
    task.commitFence();
    task.account();
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static int failureStatus(String code) {
    return switch (code) {
      case SessionErrorCode.SESSION_EVENT_NOT_FOUND -> 404;
      case SessionErrorCode.SESSION_HISTORY_EXPIRED -> 410;
      case SessionErrorCode.SESSION_HISTORY_LIMIT -> 429;
      default -> 409;
    };
  }
}
