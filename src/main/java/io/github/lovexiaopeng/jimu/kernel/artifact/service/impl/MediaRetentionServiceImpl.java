package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.MediaRetentions;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.MediaRetentionService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotObjectCodec;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;

/** 原媒体保留/清理用例；不从终态或Future取消推断writer已退出。 */
public final class MediaRetentionServiceImpl implements MediaRetentionService {
  private final MediaRetentions records;
  private final LedgerUnitOfWork work;
  private final SnapshotDigest digest;
  private final SnapshotObjectCodec codec;

  public MediaRetentionServiceImpl(
      MediaRetentions records,
      LedgerUnitOfWork work,
      SnapshotDigest digest,
      SnapshotObjectCodec codec) {
    this.records = records;
    this.work = work;
    this.digest = digest;
    this.codec = codec;
  }

  @Override
  public void cleanup(ArtifactStore store) {
    if (store == null) return;
    for (var candidate : records.candidates()) {
      boolean ready =
          work.required(
              () -> {
                records.lockBudget(candidate.tenant());
                var task = records.lockTask(candidate);
                if (!"RETAINED".equals(task.cleanupState())
                    || !task.writersQuiescent()
                    || !terminal(task.state())) return false;
                if (records.hasUnknownHold(candidate)) return false;
                records.markDeleting(candidate);
                return true;
              });
      if (!ready) continue;
      // Only persisted host-issued identities. Failed deletion leaves marks and accounting
      // retryable.
      for (var image : records.deletingObjects(candidate))
        store.removeUpload(candidate.tenant(), image);
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            var task = records.lockTask(candidate);
            if ("DELETED".equals(task.cleanupState())) return null;
            if (!task.writersQuiescent()
                || !terminal(task.state())
                || records.hasUnknownHold(candidate))
              throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
            for (var checkpoint : records.lockCheckpoints(candidate)) {
              if (!digest.hash(checkpoint.snapshot()).equals(checkpoint.digest()))
                throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
              // Retain original request/body/config/history digests; remove only the large body
              // copy.
              String retained = codec.withoutField(checkpoint.snapshot(), "requestBody");
              records.purgeCheckpoint(candidate, checkpoint.id(), retained, digest.hash(retained));
            }
            for (var hold : records.lockHolds(candidate)) {
              long bytes = hold.actual() == null ? hold.amount() : hold.actual();
              if ("MEDIA_CHECKPOINT".equals(hold.category()))
                records.releaseCheckpointBytes(candidate.tenant(), bytes);
              else records.releaseStorageBytes(candidate.tenant(), bytes);
              records.releaseHold(candidate, hold.id());
            }
            records.purgeInputs(candidate);
            records.markObjectsDeleted(candidate);
            records.markTaskDeleted(candidate);
            return null;
          });
    }
  }

  private static boolean terminal(String state) {
    return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state);
  }
}
