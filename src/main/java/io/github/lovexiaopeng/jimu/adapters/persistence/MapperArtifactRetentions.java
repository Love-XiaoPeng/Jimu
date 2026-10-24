package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.first;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.time;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.budget.entity.ReservationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationReadMapper;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactRetentions;
import java.time.Instant;
import java.util.List;

/** 复用原budget/task锁、当前SqlSession和显式query；只作事实转换和具名持久调用。 */
final class MapperArtifactRetentions implements ArtifactRetentions {
  private final JdbcLedger ledger;
  private final ArtifactRetentionMapper sql;

  MapperArtifactRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(ArtifactRetentionMapper.class);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "id"), s(r, "task_id"), s(r, "upload_id")))
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

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(task);
      }
    };
  }

  public Instant lockArtifactExpiry(Candidate candidate) {
    return time(
        first(sql.lockArtifact(ledger.queryContext(), candidate.tenant(), candidate.artifactId())),
        "expires_at");
  }

  public void mark(Candidate candidate, String state) {
    sql.mark(ledger.queryContext(), candidate.tenant(), candidate.artifactId(), state);
  }

  public Hold hold(Candidate candidate) {
    var wrapper = new LambdaQueryWrapper<ReservationReadEntity>();
    wrapper
        .eq(ReservationReadEntity::getTenantId, candidate.tenant())
        .eq(ReservationReadEntity::getRowId, candidate.artifactId());
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    var row =
        first(
            ledger
                .mappers
                .session()
                .selectList(ReservationReadMapper.class.getName() + ".selectMaps", parameters));
    return new Hold(s(row, "state"), n(row, "amount"));
  }

  public void releaseStorage(String tenant, long amount) {
    sql.releaseStorage(ledger.queryContext(), tenant, amount);
  }

  public void releaseHold(Candidate candidate) {
    ledger
        .mappers
        .reservations()
        .releaseQueueSlot(ledger.queryContext(), candidate.tenant(), candidate.artifactId());
  }
}
