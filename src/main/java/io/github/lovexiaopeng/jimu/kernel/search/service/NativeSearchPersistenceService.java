package io.github.lovexiaopeng.jimu.kernel.search.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Result;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run;
import java.util.List;
import java.util.Map;

/** 原生检索账本完整生命周期，不启动或观察真实进程。 */
public interface NativeSearchPersistenceService {
  boolean searchQuiescent(Lease lease, String invocation);

  void prepareSearch(
      Lease lease,
      Invocation invocation,
      String directory,
      String snapshot,
      String query,
      String pin);

  void searchStarted(Lease lease, String invocation, NativeIdentity identity);

  void searchStopped(Run run, NativeIdentity identity);

  void searchCompleted(
      Lease lease, String invocation, String hash, long size, Map<String, Integer> cursors);

  Result searchPage(Lease lease, String query, String pin, String cursor);

  List<Run> nativeSearches(String host);

  List<Run> expiredSearches(String host);

  void searchStorageRemoved(Run run);
}
