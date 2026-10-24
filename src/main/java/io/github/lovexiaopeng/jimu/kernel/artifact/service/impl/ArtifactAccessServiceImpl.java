package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactDownloads;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactAccessService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.time.Clock;

/** 先身份、owner保留态及当前task权限，再判到期；保留拒绝次序与原事务语义。 */
public final class ArtifactAccessServiceImpl implements ArtifactAccessService {
  private final ArtifactDownloads records;
  private final LedgerUnitOfWork work;
  private final Clock clock;

  public ArtifactAccessServiceImpl(ArtifactDownloads records, LedgerUnitOfWork work, Clock clock) {
    this.records = records;
    this.work = work;
    this.clock = clock;
  }

  @Override
  public Tasks.Download authorize(Tasks.Identity identity, String artifactId) {
    return work.required(
        () -> {
          records.requireIdentity(identity);
          var result = records.findOwnedRetained(identity, artifactId);
          records.requireTaskAccess(identity, result.taskId());
          if (result.artifact().expiresAt().isBefore(clock.instant()))
            throw new TaskFailure(410, "ARTIFACT_EXPIRED");
          return new Tasks.Download(result.artifact(), identity.tenant(), result.snapshotKey());
        });
  }
}
