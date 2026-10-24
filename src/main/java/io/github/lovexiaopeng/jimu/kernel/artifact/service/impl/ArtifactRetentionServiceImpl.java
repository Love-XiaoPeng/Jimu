package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactRetentions;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactRetentionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 保持标记事务、事务外存储删除和释放事务的原有故障恢复边界。 */
public final class ArtifactRetentionServiceImpl implements ArtifactRetentionService {
  private final ArtifactRetentions records;
  private final LedgerUnitOfWork work;
  private final Clock clock;

  public ArtifactRetentionServiceImpl(
      ArtifactRetentions records, LedgerUnitOfWork work, Clock clock) {
    this.records = records;
    this.work = work;
    this.clock = clock;
  }

  @Override
  public void cleanup(ArtifactStore store) {
    for (var candidate : records.candidates()) {
      boolean ready =
          work.required(
              () -> {
                records.lockBudget(candidate.tenant());
                var task = records.lockTask(candidate);
                boolean expired =
                    records.lockArtifactExpiry(candidate).isBefore(Instant.now(clock));
                // Even a currently quiescent RUNNING task may acquire another attempt later.
                if (expired && !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.state()))
                  return false;
                if (!expired && !List.of("FAILED", "CANCELLED").contains(task.state()))
                  return false;
                if (!task.writersQuiescent()) return false;
                records.mark(candidate, expired ? "EXPIRED" : "ABANDONED");
                return true;
              });
      if (!ready) continue;
      // Failure leaves the retained state/hold retryable; no filesystem operation holds DB locks.
      store.removeUpload(candidate.tenant(), candidate.uploadId());
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            records.lockTask(candidate);
            var hold = records.hold(candidate);
            if (!"RELEASED".equals(hold.state())) {
              records.releaseStorage(candidate.tenant(), hold.amount());
              records.releaseHold(candidate);
            }
            return null;
          });
    }
  }
}
