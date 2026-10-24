package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries.Commit;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries.Result;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries.Task;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeQueryService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 回放先证明、PENDING才结算；特殊拒绝通过返回值跨过原事务提交。 */
public final class SessionScopeQueryServiceImpl implements SessionScopeQueryService {
  private final SessionScopeQueries records;
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeNormalizationService
      normalization = new SessionScopeNormalizationServiceImpl();

  public SessionScopeQueryServiceImpl(SessionScopeQueries records) {
    this.records = records;
  }

  private record Outcome(String value, TaskFailure rejection) {}

  public String scopedOrResult(Lease lease, String step, Call call, String version, String digest) {
    var result =
        records.required(
            Math.min(
                System.nanoTime() + 2_000_000_000L,
                System.nanoTime() + Duration.between(Instant.now(), lease.deadline()).toNanos()),
            () -> execute(lease, step, call, version, digest));
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private Outcome execute(Lease lease, String step, Call call, String version, String digest) {
    var task = records.lock(lease);
    if (!task.scopeTask() || !List.of("session_event_read", "session_search").contains(call.tool()))
      throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var invocation = task.originalSlot(step, call, version, digest);
    var st = task.validatedStep(step);
    String authority = task.requireScope();
    var cut = task.cut(step);
    var binding = task.lockBinding(invocation.id());
    String normalized = task.normalized(call);
    normalization.verify(
        normalized,
        new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Stored(
            binding.normalized(), binding::normalizedDigest, binding::normalizedBytes),
        records);
    boolean read = call.tool().equals("session_event_read");
    if (binding.version() != 1
        || !lease.taskId().equals(binding.caller())
        || !step.equals(binding.step())
        || !(read ? "READ" : "SEARCH").equals(binding.operation())
        || !st.binding().equals(binding.binding())
        || !records.hash(call.input()).equals(binding.input())
        || !authority.equals(binding.authority())
        || !cut.authority().equals(binding.authority())
        || !st.context().equals(binding.context())
        || !cut.context().equals(binding.context())
        || !records.hash(cut.snapshot()).equals(cut.context())
        || !"PRESENT".equals(binding.content())) throw corrupt();
    if (!"PENDING".equals(invocation.state())) {
      boolean success = "SUCCEEDED".equals(binding.outcome());
      if (!invocation.state().equals(binding.outcome())
          || !List.of("SUCCEEDED", "FAILED").contains(binding.outcome())) throw corrupt();
      var receipt = task.receipt(binding.receipt());
      var reservation = task.reservation(invocation.id());
      if (!receipt.fact().equals("result:" + invocation.id())
          || !receipt.type().equals(success ? "tool.completed" : "tool.failed")
          || !Objects.equals(receipt.code(), binding.failure())
          || !"TOOL".equals(reservation.category())
          || !"SETTLED".equals(reservation.state())
          || reservation.actual() != 1) throw corrupt();
      task.requireAttempt(binding.attempt(), binding.fence());
      if (success) {
        String output = invocation.output();
        if (!records.hash(output).equals(invocation.outputDigest())
            || !invocation.outputDigest().equals(binding.outputDigest())
            || records.bytes(output) != binding.outputBytes()) throw corrupt();
        if (read) task.validateReadOutput(output);
        else task.validateSearchOutput(output);
      } else if (invocation.output() != null
          || invocation.outputDigest() != null
          || binding.outputBytes() != 0) throw corrupt();
      if (read) task.verifyNative(binding.nativeSeq());
      finish(task);
      return new Outcome(
          success ? invocation.output() : null,
          success ? null : new TaskFailure(failureStatus(binding.failure()), binding.failure()));
    }
    if (!"PENDING".equals(binding.outcome())) throw corrupt();
    var budget = task.budget();
    if (task.toolsUsed() >= task.maxTools()
        || task.toolsUsed() >= 20
        || budget.used() >= budget.limit()) throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
    Result found = null;
    TaskFailure failure = null;
    try {
      found = read ? task.read(normalized) : task.search(call.input());
    } catch (TaskFailure rejected) {
      if (!rejected.code().equals("SESSION_EVENT_NOT_FOUND")) throw rejected;
      failure = rejected;
    }
    String output = found == null ? null : found.output();
    String refs = read ? (found == null ? "[]" : found.refs()) : null;
    String outcome = failure == null ? "SUCCEEDED" : "FAILED",
        code = failure == null ? null : failure.code();
    if (output != null) task.body(records.bytes(output));
    if (refs != null) {
      task.validateRefs(refs);
      task.body(records.bytes(refs));
    }
    task.consumeTask();
    task.consumeTenant();
    task.reserve(invocation.id());
    task.settle(invocation.id());
    task.finishInvocation(
        invocation.id(), outcome, output, output == null ? null : records.hash(output));
    task.event("intent:" + invocation.id(), "tool.dispatched", null);
    task.event(
        "result:" + invocation.id(), failure == null ? "tool.completed" : "tool.failed", code);
    long receipt = task.receiptSequence(invocation.id());
    Long nativeSeq = read ? task.nextNativeSequence() : null;
    Integer rows = found == null ? null : read ? task.refCount(refs) : found.rows();
    String scan = found == null ? null : read ? records.hash(refs) : found.scanDigest();
    Integer eventBytes = found == null ? null : found.eventBytes();
    task.commitQuery(
        invocation.id(),
        new Commit(
            outcome,
            code,
            scan,
            rows,
            eventBytes,
            refs,
            refs == null ? null : records.hash(refs),
            refs == null ? null : records.bytes(refs),
            output == null ? null : records.hash(output),
            output == null ? 0 : records.bytes(output),
            receipt,
            nativeSeq));
    if (read) task.appendRead(step, invocation);
    finish(task);
    return new Outcome(output, failure);
  }

  private static void finish(Task task) {
    task.commitFence();
    task.requireScope();
    task.remaining();
    task.account();
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
