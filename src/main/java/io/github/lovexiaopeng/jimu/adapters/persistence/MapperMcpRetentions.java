package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.mcp.entity.McpCursorEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpCursorMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpRetentionMapper;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRetentions;
import java.util.List;

/** 同一Mapper会话与显式query；保留原task时间参数对象，避免时间转换改变谓词。 */
final class MapperMcpRetentions implements McpRetentions {
  private final JdbcLedger ledger;
  private final McpRetentionMapper sql;
  private final ArtifactRetentionMapper budgets;

  MapperMcpRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(McpRetentionMapper.class);
    budgets = ledger.mappers.session().getMapper(ArtifactRetentionMapper.class);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "task_id")))
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

      public List<Hold> lockExpiredHolds() {
        return sql
            .lockExpiredHolds(
                ledger.queryContext(),
                candidate.tenant(),
                candidate.taskId(),
                task.get("updated_at"))
            .stream()
            .map(
                r ->
                    new Hold(
                        s(r, "id"),
                        n(r, "amount"),
                        r.get("actual") == null ? null : n(r, "actual")))
            .toList();
      }

      public void event(String fact, String type) {
        ledger.event(task, fact, type, null);
      }
    };
  }

  public boolean hasUnknownHold(Candidate candidate) {
    return !sql.unknownHolds(ledger.queryContext(), candidate.tenant(), candidate.taskId())
        .isEmpty();
  }

  public void deleteCursors(Candidate candidate, String root) {
    var wrapper = new LambdaQueryWrapper<McpCursorEntity>();
    wrapper
        .eq(McpCursorEntity::getTenantId, candidate.tenant())
        .eq(McpCursorEntity::getTaskId, candidate.taskId())
        .eq(McpCursorEntity::getRootInvocationId, root);
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    ledger.mappers.session().delete(McpCursorMapper.class.getName() + ".delete", parameters);
  }

  public void purgeResult(Candidate candidate, String root) {
    sql.purgeResult(ledger.queryContext(), candidate.tenant(), candidate.taskId(), root);
  }

  public void releaseStorage(String tenant, long bytes) {
    budgets.releaseStorage(ledger.queryContext(), tenant, bytes);
  }

  public void releaseHold(Candidate candidate, String id) {
    ledger.mappers.reservations().releaseQueueSlot(ledger.queryContext(), candidate.tenant(), id);
  }
}
