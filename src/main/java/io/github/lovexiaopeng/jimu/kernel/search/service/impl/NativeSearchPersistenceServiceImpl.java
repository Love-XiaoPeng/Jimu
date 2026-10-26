package io.github.lovexiaopeng.jimu.kernel.search.service.impl;

import io.github.lovexiaopeng.jimu.kernel.search.port.NativeSearchRecords;
import io.github.lovexiaopeng.jimu.kernel.search.service.NativeSearchPersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Result;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Map;

/** 保留原资格、预算首错、停止事实与事务边界的原生检索用例。 */
public final class NativeSearchPersistenceServiceImpl implements NativeSearchPersistenceService {
  private final NativeSearchRecords records;

  public NativeSearchPersistenceServiceImpl(NativeSearchRecords records) {
    this.records = records;
  }

  @Override
  public boolean searchQuiescent(Lease lease, String invocation) {
    return records.selectNonStoppedSearch(lease.tenant(), lease.taskId(), invocation).isEmpty();
  }

  @Override
  public void prepareSearch(
      Lease lease,
      Invocation invocation,
      String directory,
      String snapshot,
      String query,
      String pin) {
    records.leaseTransaction(
        lease,
        () -> {
          records.fenced(lease, true);
          var budget = records.budget(lease.tenant());
          var call =
              records.selectSearchInvocation(lease.tenant(), invocation.id(), lease.taskId());
          // A PROPOSED invocation can be admitted by a newer fenced attempt. Its original
          // proposal attempt is historical; the native run below records the current writer.
          if (!"RUNNING".equals(call.state())
              || !List.of("glob", "grep").contains(call.tool())
              || !invocation.effectId().equals(call.effectIntentId()))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var aggregate = records.selectSearchAggregate(lease.tenant(), lease.taskId());
          if (aggregate.roots() >= 20 || aggregate.bytes() + 4194304 > 8388608)
            throw new TaskFailure(429, "SEARCH_RESULT_LIMIT");
          if ((budget.storageHeld() + budget.mediaCheckpointHeld()) + 4194304
              > budget.storageLimit()) throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String host = records.selectSearchWorkerHost(lease.worker()).hostId();
          records.insertSearchRun(
              lease.tenant(),
              invocation.id(),
              lease.taskId(),
              invocation.effectId(),
              lease.attemptId(),
              lease.worker(),
              host,
              directory,
              snapshot,
              query,
              pin);
          records.holdSearchStorage(lease.tenant());
          records.reserve(
              lease.tenant(),
              lease.taskId(),
              "search:" + invocation.id(),
              null,
              "SEARCH_STORAGE",
              4194304,
              "RESERVED",
              "KNOWN");
          return null;
        });
  }

  @Override
  public void searchStarted(Lease lease, String invocation, NativeIdentity identity) {
    records.leaseTransaction(
        lease,
        () -> {
          records.fenced(lease, true);
          if (records.startSearchRun(
                  records.json(identity),
                  lease.tenant(),
                  lease.taskId(),
                  invocation,
                  lease.attemptId())
              != 1) throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          return null;
        });
  }

  @Override
  public void searchStopped(Run run, NativeIdentity identity) {
    // Append-only physical observation: an old fence may report death, never business success.
    records.transaction(
        () -> {
          records.budget(run.tenant());
          var task = records.locked(run.tenant(), run.task());
          var row =
              records.selectSearchStopRun(
                  run.tenant(), run.invocation(), run.task(), run.effect(), run.worker());
          String fact = records.json(identity);
          if (row.processIdentityObject() != null && !fact.equals(row.processIdentityObject()))
            throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          records.insertSearchStop(run.tenant(), run.invocation(), records.hash(fact), fact);
          records.stopSearchRun(fact, run.tenant(), run.invocation());
          records.settleStoppedWriters(task);
          return null;
        });
  }

  @Override
  public void searchCompleted(
      Lease lease, String invocation, String hash, long size, Map<String, Integer> cursors) {
    if (size < 0 || size > 4194304 || !hash.matches("[a-f0-9]{64}") || cursors.size() > 100000)
      throw new TaskFailure(409, "SEARCH_RESULT_LIMIT");
    records.leaseTransaction(
        lease,
        () -> {
          records.fenced(lease, true);
          var run =
              records.selectSearchCompleteRun(
                  lease.tenant(), lease.taskId(), invocation, lease.attemptId());
          if (!"STOPPED".equals(run.phase()) || run.resultHashObject() != null)
            throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          records.completeSearchRun(hash, size, records.json(cursors), lease.tenant(), invocation);
          records.settleSearchStorage(size, lease.tenant(), "search:" + invocation);
          records.releaseCompletedSearchStorage(4194304 - size, lease.tenant());
          return null;
        });
  }

  @Override
  public Result searchPage(Lease lease, String query, String pin, String cursor) {
    return records.leaseTransaction(
        lease,
        () -> {
          records.fenced(lease, true);
          for (var row : records.selectSearchPage(lease.tenant(), lease.taskId())) {
            var index = records.readIndex(row.cursorIndex());
            if (!index.has(cursor)) continue;
            if (!query.equals(row.queryDigest()) || !pin.equals(row.toolPin()))
              throw new TaskFailure(409, "SEARCH_CURSOR_INVALID");
            return new Result(
                row.rootInvocation(),
                row.runtimeDirectory(),
                row.resultHash(),
                row.resultSize(),
                index.path(cursor).intValue());
          }
          throw new TaskFailure(409, "SEARCH_CURSOR_INVALID");
        });
  }

  @Override
  public List<Run> nativeSearches(String host) {
    return records.selectNativeSearches(host).stream()
        .map(
            row ->
                new Run(
                    row.tenantId(),
                    row.taskId(),
                    row.rootInvocation(),
                    row.effectId(),
                    row.attemptId(),
                    row.workerId(),
                    row.runtimeDirectory(),
                    row.snapshotDigest(),
                    row.phase(),
                    row.processIdentityObject() == null
                        ? null
                        : records.readIdentity(row.processIdentity()),
                    row.stopRequested() != 0))
        .toList();
  }

  @Override
  public List<Run> expiredSearches(String host) {
    return records.selectExpiredSearches(host).stream()
        .map(
            row ->
                new Run(
                    row.tenantId(),
                    row.taskId(),
                    row.rootInvocation(),
                    row.effectId(),
                    row.attemptId(),
                    row.workerId(),
                    row.runtimeDirectory(),
                    row.snapshotDigest(),
                    "STOPPED",
                    records.readIdentity(row.processIdentity()),
                    true))
        .toList();
  }

  @Override
  public void searchStorageRemoved(Run run) {
    records.transaction(
        () -> {
          records.budget(run.tenant());
          records.locked(run.tenant(), run.task());
          var row =
              records.selectSearchRemovalRun(
                  run.tenant(), run.invocation(), run.task(), run.effect());
          if (!"STOPPED".equals(row.phase())) throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          var reservation =
              records.selectSearchRemovalReservation(run.tenant(), "search:" + run.invocation());
          if (!"RELEASED".equals(reservation.state())) {
            long held =
                reservation.actualObject() == null ? reservation.amount() : reservation.actual();
            records.releaseRemovedSearchStorage(held, run.tenant());
            records.releaseSearchReservation(run.tenant(), "search:" + run.invocation());
          }
          records.removeSearchStorage(run.tenant(), run.invocation());
          return null;
        });
  }
}
