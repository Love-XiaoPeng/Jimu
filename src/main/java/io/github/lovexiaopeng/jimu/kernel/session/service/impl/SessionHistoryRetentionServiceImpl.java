package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionContentState;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.History;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.LockedHistory;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.RetainedBody;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.RetainedRow;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.RetainedTable;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryRetentionService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 保留原短路顺序、两次history锁检、五轮计账固定点及原提交截止边界。 */
public final class SessionHistoryRetentionServiceImpl implements SessionHistoryRetentionService {
  private final SessionHistoryRetentions records;
  private final LedgerUnitOfWork work;
  private final SnapshotDigest digest;

  public SessionHistoryRetentionServiceImpl(
      SessionHistoryRetentions records, LedgerUnitOfWork work, SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.digest = digest;
  }

  private History history(LockedHistory task) {
    var h = task.history();
    if (h.recordVersion() != 1
        || h.version() != task.expectedVersion()
        || !task.matchesSnapshot(h.binding())
        || !digest.hash(task.expectedBinding()).equals(h.digest()))
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_VERSION_UNSUPPORTED);
    return h;
  }

  public void cleanup() {
    try (var candidates = records.candidateBatch()) {
      for (var candidate : candidates)
        work.required(
            () -> {
              records.lockBudget(candidate.tenant());
              var task = records.lockTask(candidate);
              var h = history(task);
              if (!SessionContentState.PRESENT.equals(h.state())
                  || h.retained() && (!task.scoped() || !task.invalidated())
                  || !List.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)
                      .contains(task.state())
                  || !task.writersQuiescent()
                  || task.activeInvocations()
                  || task.unsafeReservations()
                  || task.dispatchedModels()) return null;
              purge(task);
              return null;
            });
    }
  }

  private static long bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private long metadata(LockedHistory task) {
    long total = task.lineageMetadataBytes();
    for (var row : task.metadata()) {
      total += 32;
      for (var value : row.scalars()) total += 8 + (value == null ? 1 : bytes(value));
    }
    if (total > SessionHistoryPolicyV1.CLOSED_METADATA_BYTES)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    return total;
  }

  public void purge(LockedHistory task) {
    var h = history(task);
    var retained =
        task.contents().stream()
            .map(
                table ->
                    new RetainedTable(
                        table.name(),
                        table.rows().stream()
                            .map(
                                row ->
                                    new RetainedRow(
                                        row.identity(),
                                        row.bodies().stream()
                                            .filter(body -> body.value() != null)
                                            .map(
                                                body ->
                                                    new RetainedBody(
                                                        body.field(),
                                                        digest.hash(body.value()),
                                                        bytes(body.value())))
                                            .toList()))
                            .toList()))
            .toList();
    if (task.scoped()) {
      if (task.expectedVersion() == 5) task.purgeLineage();
      task.purgeScopeQuery();
      task.purgeScopeStep();
      task.purgeScopeTargets();
      task.purgeScope();
    }
    String receipt = records.receipt(retained, h.bodyBytes(), h.digest());
    task.purgePublicEvents();
    if (task.searchable()) task.purgeSearch();
    task.purgeTrace();
    task.purgeRead();
    task.purgeInvocations();
    task.purgeSteps();
    task.purgeDispatches();
    task.purgeTask();
    task.markPurged(receipt);
    task.event();
    var snapshot = task.purgeMetadata();
    if (snapshot != null) {
      // 只改变这三个数字及reservation.state；其它标量来自event后的真实同锁快照。
      long size = 0;
      for (int pass = 0; pass < 5; pass++) {
        long digits = 1;
        for (long n = size; n >= 10; n /= 10) digits++;
        task.operation(digits + 1);
        long next = Math.addExact(snapshot.fixedBytes(), 3 * digits);
        if (next < 0 || next > SessionHistoryPolicyV1.CLOSED_METADATA_BYTES)
          throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
        if (next == size) {
          task.settleHistory(size);
          task.settleReservation(size);
          // Work最终阶段仍完整独立读取18投影并readBack；这里不复用权限或原行容器。
          task.releaseBudget(SessionHistoryPolicyV1.TASK_RESERVE_BYTES - size);
          return;
        }
        size = next;
      }
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    }
    // Decimal widths of history counters and SETTLED/actual participate in the same fixed point.
    for (int pass = 0; pass < 5; pass++) {
      long size = metadata(task);
      task.settleHistory(size);
      task.settleReservation(size);
      if (metadata(task) == size) {
        task.releaseBudget(SessionHistoryPolicyV1.TASK_RESERVE_BYTES - size);
        return;
      }
    }
    throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }
}
