package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionSearches;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionSearches.Commit;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionSearches.Search;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionSearchService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Objects;

/** 原生搜索按原顺序证明回放；已知拒绝在结算提交后抛出。 */
public final class SessionSearchServiceImpl implements SessionSearchService {
  private final SessionSearches records;

  public SessionSearchServiceImpl(SessionSearches records) {
    this.records = records;
  }

  private record Outcome(String value, TaskFailure rejection) {}

  public String searchOrResult(Lease lease, String step, Call call, String version, String digest) {
    var result = records.required(() -> execute(lease, step, call, version, digest));
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private Outcome execute(Lease lease, String step, Call call, String version, String digest) {
    var task = records.lock(lease);
    if (!task.enabled()) throw new TaskFailure(403, "SESSION_SEARCH_DISABLED");
    var invocation = task.originalSlot(step, call, version, digest);
    if (!call.tool().equals("session_event_search"))
      throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var input = task.input(call.input());
    String history = task.historyBinding();
    var st = task.validatedStep(step);
    var b = task.lockBinding(invocation.id());
    String normalized = task.normalized(input, st.upper());
    if (b.version() != 1
        || !lease.taskId().equals(b.task())
        || !step.equals(b.step())
        || !history.equals(b.binding())
        || !history.equals(st.binding())
        || b.upper() != st.upper()
        || !records.hash(call.input()).equals(b.input())
        || !"PRESENT".equals(b.content())
        || !normalized.equals(b.normalized())
        || !records.hash(normalized).equals(b.normalizedDigest())
        || records.bytes(normalized) != b.normalizedBytes()) throw corrupt();
    if (!"PENDING".equals(invocation.state())) {
      if (!invocation.state().equals(b.outcome())) throw corrupt();
      var receipt = task.receipt(b.receipt());
      boolean success = "SUCCEEDED".equals(b.outcome());
      if (!success && !known(b.failure())) throw corrupt();
      if ((!success && !"FAILED".equals(b.outcome()))
          || !receipt.fact().equals("result:" + invocation.id())
          || !receipt.type().equals(success ? "tool.completed" : "tool.failed")
          || !Objects.equals(receipt.code(), b.failure())) throw corrupt();
      var reservation = task.reservation(invocation.id());
      if (!"TOOL".equals(reservation.category())
          || !"SETTLED".equals(reservation.state())
          || reservation.actual() != 1) throw corrupt();
      task.requireAttempt(b.attempt(), b.fence());
      if (success) {
        if (!records.hash(invocation.output()).equals(invocation.outputDigest())
            || !invocation.outputDigest().equals(b.outputDigest())
            || records.bytes(invocation.output()) != b.outputBytes()) throw corrupt();
        task.validateOutput(invocation.output());
      } else if (invocation.output() != null
          || invocation.outputDigest() != null
          || b.outputBytes() != 0) throw corrupt();
      task.commitFence();
      task.account();
      return new Outcome(
          success ? invocation.output() : null,
          success ? null : new TaskFailure(failureStatus(b.failure()), b.failure()));
    }
    if (!"PENDING".equals(b.outcome())) throw corrupt();
    var budget = task.budget();
    if (task.toolsUsed() >= task.maxTools()
        || task.toolsUsed() >= 20
        || budget.used() >= budget.limit()) throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
    Search found = null;
    TaskFailure failure = null;
    try {
      found = task.search(input, b.upper());
    } catch (TaskFailure rejected) {
      if (!known(rejected.code())) throw rejected;
      failure = rejected;
    }
    String output = found == null ? null : found.output();
    String code = failure == null ? null : failure.code();
    String outcome = failure == null ? "SUCCEEDED" : "FAILED";
    if (output != null) task.body(records.bytes(output));
    task.consumeTask();
    task.consumeTenant();
    task.reserve(invocation.id());
    task.settle(invocation.id());
    task.finishInvocation(
        invocation.id(), outcome, output, output == null ? null : records.hash(output));
    task.event("intent:" + invocation.id(), "tool.dispatched", null);
    task.event(
        "result:" + invocation.id(), failure == null ? "tool.completed" : "tool.failed", code);
    long receipt = task.receiptSequence("result:" + invocation.id());
    task.commitSearch(
        invocation.id(),
        new Commit(
            outcome,
            code,
            found == null ? null : found.scanDigest(),
            found == null ? null : Integer.valueOf(found.rows()),
            found == null ? null : Integer.valueOf(found.eventBytes()),
            output == null ? null : records.hash(output),
            output == null ? 0 : records.bytes(output),
            receipt));
    task.commitFence();
    task.deadline();
    task.account();
    return new Outcome(output, failure);
  }

  private static boolean known(String code) {
    return List.of(
            "SESSION_HISTORY_CORRUPT",
            "SESSION_HISTORY_VERSION_UNSUPPORTED",
            "SESSION_HISTORY_LIMIT")
        .contains(code);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }

  private static int failureStatus(String code) {
    return switch (code) {
      case "SESSION_EVENT_NOT_FOUND" -> 404;
      case "SESSION_HISTORY_EXPIRED" -> 410;
      case "SESSION_HISTORY_LIMIT" -> 429;
      default -> 409;
    };
  }
}
