package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.media.entity.ImageObjectReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper.ImageObjectReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper.MediaRetentionMapper;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.MediaRetentions;
import io.github.lovexiaopeng.jimu.kernel.media.api.MediaCleanupState;
import java.util.List;

/** 同一SqlSession/DS/UoW与显式query；只转换事实，不决定保留或结算规则。 */
final class MapperMediaRetentions implements MediaRetentions {
  private final JdbcLedger ledger;
  private final MediaRetentionMapper sql;
  private final ArtifactRetentionMapper budgets;

  MapperMediaRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(MediaRetentionMapper.class);
    budgets = ledger.mappers.session().getMapper(ArtifactRetentionMapper.class);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "id")))
        .toList();
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public LockedTask lockTask(Candidate candidate) {
    var task = ledger.locked(candidate.tenant(), candidate.taskId());
    return new LockedTask() {
      public String state() {
        return s(task, "state");
      }

      public String cleanupState() {
        return s(task, "media_cleanup_state");
      }

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(task);
      }
    };
  }

  public boolean hasUnknownHold(Candidate candidate) {
    return !sql.unknownHolds(ledger.queryContext(), candidate.tenant(), candidate.taskId())
        .isEmpty();
  }

  public void markDeleting(Candidate candidate) {
    sql.markDeleting(ledger.queryContext(), candidate.tenant(), candidate.taskId());
  }

  public List<String> deletingObjects(Candidate candidate) {
    var wrapper = new LambdaQueryWrapper<ImageObjectReadEntity>();
    wrapper
        .select(ImageObjectReadEntity::getImageId)
        .eq(ImageObjectReadEntity::getTenantId, candidate.tenant())
        .eq(ImageObjectReadEntity::getTaskId, candidate.taskId())
        .eq(ImageObjectReadEntity::getCleanupState, MediaCleanupState.DELETING);
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .<java.util.Map<String, Object>>selectList(
            ImageObjectReadMapper.class.getName() + ".selectMaps", parameters)
        .stream()
        .map(r -> s(r, "image_id"))
        .toList();
  }

  public List<Checkpoint> lockCheckpoints(Candidate candidate) {
    return sql
        .lockCheckpoints(ledger.queryContext(), candidate.tenant(), candidate.taskId())
        .stream()
        .map(r -> new Checkpoint(s(r, "id"), s(r, "request_snapshot"), s(r, "request_digest")))
        .toList();
  }

  public void purgeCheckpoint(Candidate candidate, String id, String snapshot, String digest) {
    sql.purgeCheckpoint(ledger.queryContext(), candidate.tenant(), id, snapshot, digest);
  }

  public List<Hold> lockHolds(Candidate candidate) {
    return sql.lockHolds(ledger.queryContext(), candidate.tenant(), candidate.taskId()).stream()
        .map(
            r ->
                new Hold(
                    s(r, "id"),
                    s(r, "category"),
                    n(r, "amount"),
                    r.get("actual") == null ? null : n(r, "actual")))
        .toList();
  }

  public void releaseCheckpointBytes(String tenant, long bytes) {
    sql.releaseCheckpointBytes(ledger.queryContext(), tenant, bytes);
  }

  public void releaseStorageBytes(String tenant, long bytes) {
    budgets.releaseStorage(ledger.queryContext(), tenant, bytes);
  }

  public void releaseHold(Candidate candidate, String id) {
    ledger.mappers.reservations().releaseQueueSlot(ledger.queryContext(), candidate.tenant(), id);
  }

  public void purgeInputs(Candidate candidate) {
    sql.purgeInputs(ledger.queryContext(), candidate.tenant(), candidate.taskId());
  }

  public void markObjectsDeleted(Candidate candidate) {
    sql.markObjectsDeleted(ledger.queryContext(), candidate.tenant(), candidate.taskId());
  }

  public void markTaskDeleted(Candidate candidate) {
    sql.markTaskDeleted(ledger.queryContext(), candidate.tenant(), candidate.taskId());
  }
}
