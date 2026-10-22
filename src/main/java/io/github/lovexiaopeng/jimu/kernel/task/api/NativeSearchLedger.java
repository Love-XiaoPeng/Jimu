package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.List;
import java.util.Map;

/** Search facts share the task ledger's lease, lock order and tenant storage reservations. */
public interface NativeSearchLedger {
  boolean searchQuiescent(ExecutionLedger.Lease lease, String invocation);

  record ProcessIdentity(
      long pid, long startTicks, String bootId, String pidNamespace, String observerPidNamespace) {}

  record NativeIdentity(
      ProcessIdentity launcher, ProcessIdentity bubblewrap, ProcessIdentity init) {}

  record Run(
      String tenant,
      String task,
      String invocation,
      String effect,
      String attempt,
      String worker,
      String directory,
      String snapshot,
      String phase,
      NativeIdentity identity,
      boolean stopRequested) {}

  record Result(String invocation, String directory, String hash, long size, int page) {}

  void prepareSearch(
      ExecutionLedger.Lease lease,
      ExecutionLedger.Invocation invocation,
      String directory,
      String snapshot,
      String query,
      String pin);

  void searchStarted(ExecutionLedger.Lease lease, String invocation, NativeIdentity identity);

  void searchStopped(Run run, NativeIdentity identity);

  void searchCompleted(
      ExecutionLedger.Lease lease,
      String invocation,
      String hash,
      long size,
      Map<String, Integer> cursors);

  Result searchPage(ExecutionLedger.Lease lease, String query, String pin, String cursor);

  List<Run> nativeSearches(String host);

  List<Run> expiredSearches(String host);

  void searchStorageRemoved(Run run);
}
