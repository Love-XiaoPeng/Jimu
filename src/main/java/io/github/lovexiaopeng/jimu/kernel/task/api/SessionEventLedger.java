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

  /** A bounded HTTP body reader invoked only after the path's current authority is checked. */
  interface SearchBody {
    byte[] read(long deadlineNanos);
  }

  record SearchRequest(
      long deadlineNanos,
      java.util.function.Consumer<byte[]> completed,
      java.util.function.Consumer<RuntimeException> failed,
      boolean deadlineThroughResponse,
      SessionTaskResponse inputOwner) {
    public SearchRequest(
        long deadlineNanos,
        java.util.function.Consumer<byte[]> completed,
        java.util.function.Consumer<RuntimeException> failed,
        boolean deadlineThroughResponse) {
      this(deadlineNanos, completed, failed, deadlineThroughResponse, null);
    }

    public SearchRequest(
        long deadlineNanos,
        java.util.function.Consumer<byte[]> completed,
        java.util.function.Consumer<RuntimeException> failed) {
      this(deadlineNanos, completed, failed, false, null);
    }
  }

  void authorizeSessionSearch(Tasks.Identity identity, String taskId, long deadlineNanos);

  String sessionSearch(Tasks.Identity identity, String taskId, SearchBody body, long deadlineNanos);

  void authorizeSessionTrace(Tasks.Identity identity, String taskId, long deadlineNanos);

  String sessionTrace(Tasks.Identity identity, String taskId, SearchBody body, long deadlineNanos);

  String traceOrResult(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String version,
      String digest);

  String searchOrResult(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String version,
      String digest);
}
