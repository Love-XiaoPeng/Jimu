package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.time;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryDispatchReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryInvocationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryPathReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryStepReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.TaskDirectoryReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryDispatchReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryPathReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.TaskDirectoryReadMapper;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 沿用原账本锁和同DS/SqlSession/query；只映射事实及执行具名持久化操作。 */
final class MapperDirectoryRetentions implements DirectoryRetentions {
  private final JdbcLedger ledger;
  private final DirectoryRetentionMapper sql;

  MapperDirectoryRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(DirectoryRetentionMapper.class);
  }

  private List<Map<String, Object>> select(Class<?> mapper, Object wrapper) {
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", parameters);
  }

  Map<String, Object> projectionRow(String tenant, String task) {
    var wrapper = new LambdaQueryWrapper<TaskDirectoryReadEntity>();
    wrapper
        .eq(TaskDirectoryReadEntity::getTenantId, tenant)
        .eq(TaskDirectoryReadEntity::getTaskId, task);
    var rows = select(TaskDirectoryReadMapper.class, wrapper);
    if (rows.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return rows.getFirst();
  }

  long storageBytes(String tenant, String task) {
    return sql.storageBytes(ledger.queryContext(), tenant, task);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "task_id")))
        .toList();
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public Instant now() {
    return sql.now(ledger.queryContext()).toInstant(ZoneOffset.UTC);
  }

  public String metadata(List<RetainedBody> bodies) {
    var retained = new java.util.LinkedHashMap<String, Object>();
    for (var body : bodies)
      retained.put(body.key(), Map.of("sha256", body.sha256(), "bytes", body.bytes()));
    return Payloads.json(retained);
  }

  private static List<Body> bodies(Map<String, Object> row, String... fields) {
    return java.util.Arrays.stream(fields).map(field -> new Body(field, s(row, field))).toList();
  }

  public LockedTask lockTask(Candidate candidate) {
    var row = ledger.locked(candidate.tenant(), candidate.taskId());
    return new LockedTask() {
      public Projection projection() {
        var p = ledger.directoryProjection(row);
        return new Projection(
            s(p, "content_state"),
            time(p, "retain_until"),
            s(p, "cleanup_generation"),
            s(p, "filesystem_receipt"),
            s(p, "cwd"));
      }

      public String state() {
        return s(row, "state");
      }

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(row);
      }

      public boolean unsafeReservations() {
        return !sql.unsafeReservations(
                ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean activeInvocations() {
        return !sql.activeInvocations(ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean dispatchedModels() {
        return !sql.dispatchedModels(ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean directoryCapability() {
        return ledger.directoryTask(row);
      }

      public long storageBytes() {
        return MapperDirectoryRetentions.this.storageBytes(candidate.tenant(), candidate.taskId());
      }

      public List<Body> bodies() {
        return MapperDirectoryRetentions.bodies(row, "request_snapshot", "goal", "summary");
      }

      public List<Content> steps() {
        var wrapper = new LambdaQueryWrapper<DirectoryStepReadEntity>();
        wrapper
            .eq(DirectoryStepReadEntity::getTenantId, candidate.tenant())
            .eq(DirectoryStepReadEntity::getTaskId, candidate.taskId());
        return select(DirectoryStepReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperDirectoryRetentions.bodies(
                            r, "request_snapshot", "response_snapshot", "directory_cwd")))
            .toList();
      }

      public List<Content> invocations() {
        var wrapper = new LambdaQueryWrapper<DirectoryInvocationReadEntity>();
        wrapper
            .eq(DirectoryInvocationReadEntity::getTenantId, candidate.tenant())
            .eq(DirectoryInvocationReadEntity::getTaskId, candidate.taskId());
        return select(DirectoryInvocationReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperDirectoryRetentions.bodies(r, "input_snapshot", "output_snapshot")))
            .toList();
      }

      public List<Content> paths() {
        var wrapper = new LambdaQueryWrapper<DirectoryPathReadEntity>();
        wrapper
            .eq(DirectoryPathReadEntity::getTenantId, candidate.tenant())
            .eq(DirectoryPathReadEntity::getTaskId, candidate.taskId());
        return select(DirectoryPathReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "invocation_id"),
                        MapperDirectoryRetentions.bodies(
                            r,
                            "base_cwd",
                            "target_cwd",
                            "canonical_paths_snapshot",
                            "directory_evidence_snapshot")))
            .toList();
      }

      public List<Content> dispatches() {
        var wrapper = new LambdaQueryWrapper<DirectoryDispatchReadEntity>();
        wrapper
            .eq(DirectoryDispatchReadEntity::getTenantId, candidate.tenant())
            .eq(DirectoryDispatchReadEntity::getTaskId, candidate.taskId());
        return select(DirectoryDispatchReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"), MapperDirectoryRetentions.bodies(r, "usage_snapshot")))
            .toList();
      }

      public void purgeStep(String id, String tombstone) {
        sql.purgeStep(ledger.queryContext(), tombstone, candidate.tenant(), id);
      }

      public void purgeInvocation(String id, String tombstone) {
        sql.purgeInvocation(ledger.queryContext(), tombstone, candidate.tenant(), id);
      }

      public void purgePath(String id) {
        sql.purgePath(ledger.queryContext(), candidate.tenant(), id);
      }

      public void purgeDispatch(String id, String tombstone) {
        sql.purgeDispatch(ledger.queryContext(), tombstone, candidate.tenant(), id);
      }

      public void purgeTask(String tombstone) {
        sql.purgeTask(ledger.queryContext(), tombstone, candidate.tenant(), candidate.taskId());
      }

      public void markPurging(String retained) {
        sql.markPurging(
            ledger.queryContext(),
            retained,
            JdbcLedger.id(),
            candidate.tenant(),
            candidate.taskId());
      }

      public void storeReceipt(String receipt) {
        sql.storeReceipt(ledger.queryContext(), receipt, candidate.tenant(), candidate.taskId());
      }

      public void markPurged() {
        sql.markPurged(ledger.queryContext(), candidate.tenant(), candidate.taskId());
      }

      public void event() {
        ledger.event(row, "directory-purged", "working_directory.content_deleted", null);
      }

      public int releaseHold() {
        return sql.releaseHold(
            ledger.queryContext(), candidate.tenant(), candidate.taskId() + ":directory-storage");
      }

      public void releaseBudget() {
        sql.releaseBudget(ledger.queryContext(), candidate.tenant());
      }
    };
  }
}
