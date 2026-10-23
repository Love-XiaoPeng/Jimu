package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.List;
import java.util.UUID;

/** Host-created immutable authority for independently retained prior v4 sessions. */
public interface SessionSearchScopeLedger {
  Tasks.View submitSessionSearch(
      Tasks.Identity identity, String key, String goal, List<UUID> sources, long deadlineNanos);

  Tasks.View submitSessionSearch(
      Tasks.Identity identity, String key, SessionEventLedger.SearchBody body, long deadlineNanos);

  record QueryResources(long reads, long bytes, long operations) {}

  QueryResources authorizePriorSessionSearch(
      Tasks.Identity identity, String caller, long deadlineNanos);

  String priorSessionSearch(
      Tasks.Identity identity,
      String caller,
      SessionEventLedger.SearchBody body,
      long deadlineNanos,
      QueryResources resources);

  void revokeSessionSearchScope(
      Tasks.Identity identity, String caller, boolean validRequest, long deadlineNanos);

  String scopedOrResult(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String version,
      String digest);
}
