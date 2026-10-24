package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.ExecutionScopeEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.ExecutionScopeMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.OperatorMapper;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingRecords;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.mybatis.spring.SqlSessionTemplate;

/** 同DS的运维记录端口；每次调用显式传递同一绝对截止上下文。 */
final class MapperScopeBindingRecords implements ScopeBindingRecords {
  private final SqlSessionTemplate session;
  private final OperatorMapper sql;
  private final SessionQueryContext query;

  MapperScopeBindingRecords(SqlSessionTemplate session, SessionQueryContext query) {
    this.session = session;
    this.query = query;
    sql = session.getMapper(OperatorMapper.class);
  }

  private static String s(Map<String, Object> r, String c) {
    return (String) r.get(c);
  }

  private static long n(Map<String, Object> r, String c) {
    return ((Number) r.get(c)).longValue();
  }

  public Run lockRun(String tenant, String run) {
    var r = sql.lockRun(query, tenant, run);
    if (r.size() != 1) return null;
    var v = r.getFirst();
    return new Run(
        s(v, "id"),
        s(v, "plan_snapshot"),
        s(v, "plan_digest"),
        s(v, "state"),
        n(v, "max_requests"),
        n(v, "max_cost_micros"),
        n(v, "requests_used"),
        n(v, "cost_held"));
  }

  public List<Receipt> receipts(String tenant) {
    if (sql.receiptTable(query) == 0) return List.of();
    boolean version = !sql.receiptColumns(query).isEmpty();
    return sql.receipts(query, tenant, version).stream()
        .map(
            r ->
                new Receipt(
                    r.get("record_version") == null
                        ? null
                        : ((Number) r.get("record_version")).intValue(),
                    s(r, "issue_id"),
                    s(r, "instance_uuid"),
                    s(r, "run_id"),
                    s(r, "approval_snapshot"),
                    s(r, "approval_digest"),
                    s(r, "plan_digest"),
                    s(r, "materials_digest")))
        .toList();
  }

  public String serverUuid() {
    return sql.serverUuid(query);
  }

  public String schema() {
    return sql.schema(query);
  }

  public Binding binding(String tenant, String run) {
    var w =
        new LambdaQueryWrapper<ExecutionScopeEntity>()
            .eq(ExecutionScopeEntity::getTenantId, tenant)
            .eq(ExecutionScopeEntity::getRunId, run);
    List<Map<String, Object>> rows =
        session.selectList(
            ExecutionScopeMapper.class.getName() + ".selectMaps", Map.of("ew", w, "query", query));
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    return new Binding(s(row, "scope_snapshot"), s(row, "scope_digest"), row);
  }

  public Budget lockBudget(String tenant) {
    var rows = sql.lockBudget(query, tenant);
    return rows.size() != 1
        ? null
        : new Budget(n(rows.getFirst(), "cost_held"), n(rows.getFirst(), "model_used"));
  }

  public long dispatchCount(String tenant, String run) {
    return sql.dispatchCount(query, tenant, run);
  }

  public long accountedCost(String tenant, String run) {
    return sql.accountedCost(query, tenant, run);
  }

  public long invalidTasks(String tenant, String run) {
    return sql.invalidTasks(query, tenant, run);
  }

  public long tenantCost(String tenant) {
    return sql.tenantCost(query, tenant);
  }

  public long tenantModels(String tenant) {
    return sql.tenantModels(query, tenant);
  }

  public long invalidReservations(String tenant, String run) {
    return sql.invalidReservations(query, tenant, run);
  }

  public void insert(String tenant, String run, String snapshot, String digest, String boundBy) {
    if (sql.insert(query, tenant, run, snapshot, digest, boundBy) != 1)
      throw new TaskFailure(409, "EVALUATION_SCOPE_BINDING_CONFLICT");
  }

  public Map<String, Object> ledger(String tenant, String run) {
    var result = new TreeMap<String, Object>();
    result.put("tasks", sql.tasks(query, tenant, run));
    result.put("dispatches", sql.dispatches(query, tenant, run));
    result.put("reservations", sql.reservations(query, tenant, run));
    return result;
  }
}
