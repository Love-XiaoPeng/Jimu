package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;

/** owner协议边界；正文读取在同一绝对截止内，预核权计数必须传入后续查询。 */
public interface SessionLineageLedger {
  String lineageOrResult(
      ExecutionLedger.Lease lease,
      String step,
      ExecutionLedger.Call call,
      String version,
      String digest);

  Tasks.View submitSessionLineage(
      Tasks.Identity identity, String key, SessionEventLedger.SearchBody body, long deadline);

  default void submitSessionLineage(
      Tasks.Identity identity,
      String key,
      SessionEventLedger.SearchBody body,
      long deadline,
      io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse response) {
    throw new UnsupportedOperationException("Owned session response required");
  }

  QueryResources authorizeSessionLineage(Tasks.Identity identity, String caller, long deadline);

  String sessionLineage(
      Tasks.Identity identity,
      String caller,
      SessionEventLedger.SearchBody body,
      long deadline,
      QueryResources resources);

  void revokeSessionLineage(
      Tasks.Identity identity, String caller, boolean validRequest, long deadline);
}
