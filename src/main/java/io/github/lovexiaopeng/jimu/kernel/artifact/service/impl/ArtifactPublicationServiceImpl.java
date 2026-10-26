package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactPublications;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactPublicationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 保留首行复用、预算短路、忽略更新计数及事件去重；每个入口仍只有一个租约事务。 */
public final class ArtifactPublicationServiceImpl implements ArtifactPublicationService {
  private final ArtifactPublications records;

  public ArtifactPublicationServiceImpl(ArtifactPublications records) {
    this.records = records;
  }

  @Override
  public Upload stage(Lease lease, String hash, long size) {
    return records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          if (task.publicReport()) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var budget = records.bucket(lease);
          var existing = records.taskArtifacts(lease);
          if (!existing.isEmpty()) {
            var artifact = existing.getFirst();
            if (!hash.equals(artifact.hash()) || size != artifact.size())
              throw new TaskFailure(409, "ARTIFACT_MISMATCH");
            return new Upload(
                artifact.id(),
                artifact.uploadId(),
                artifact.state(),
                hash,
                size,
                artifact.snapshotKey());
          }
          String producer = records.lastSucceededInvocation(lease);
          if (size > 1048576 || budget.storageHeld() + size > budget.storageLimit())
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String artifact = records.newId(), upload = records.newId();
          records.insert(lease, artifact, producer, upload, hash, size);
          records.holdStorage(lease, size);
          records.reserveStorage(lease, artifact, size);
          task.event("staging:" + artifact, "artifact.staging");
          return new Upload(artifact, upload, "STAGING", hash, size, null);
        });
  }

  @Override
  public void verified(Lease lease, String artifactId, String key) {
    records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          records.verify(lease, artifactId, key);
          task.event("verified:" + artifactId, "artifact.verified");
          return null;
        });
  }

  @Override
  public void publish(Lease lease, String artifactId) {
    records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          if (task.publicReport()) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var artifact = records.artifact(lease, artifactId);
          if ("PUBLISHED".equals(artifact.state())) return null;
          if (!"VERIFIED".equals(artifact.state()) || artifact.snapshotKey() == null)
            throw new TaskFailure(409, "ARTIFACT_UNVERIFIED");
          records.publish(lease, artifactId);
          records.settleStorage(lease, artifactId);
          task.event("published:" + artifactId, "artifact.created");
          return null;
        });
  }
}
