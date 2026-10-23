package io.github.lovexiaopeng.jimu.kernel.task.api;

/** Authoritative native session history; model callers are restricted to their current task. */
public interface SessionEventLedger {
  String sessionContext(ExecutionLedger.Lease lease);

  String readOrResult(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String version,
      String digest);

  String sessionHistory(Tasks.Identity identity, String taskId);

  String sessionEvent(Tasks.Identity identity, String taskId, long seq, int before, int after);
}
