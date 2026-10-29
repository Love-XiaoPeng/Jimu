package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork.ProjectionRole;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;

/** 仅v5 accepted的同锁投影；长度查询先于文本物化，借出Map的owner持续到事务尾部。 */
final class SessionAcceptedTaskRows {
  private SessionAcceptedTaskRows() {}

  static Map<String, Object> load(
      TaskAdmissionMapper sql,
      SessionQueryContext query,
      String tenant,
      String task,
      boolean event) {
    return load(
        sql,
        query,
        tenant,
        task,
        event ? ProjectionRole.ACCEPTED_EVENT : ProjectionRole.ACCEPTED_VIEW);
  }

  static Map<String, Object> lineage(
      TaskAdmissionMapper sql, SessionQueryContext query, String tenant, String task) {
    return load(sql, query, tenant, task, ProjectionRole.LINEAGE_SCOPE);
  }

  private static Map<String, Object> load(
      TaskAdmissionMapper sql,
      SessionQueryContext query,
      String tenant,
      String task,
      ProjectionRole role) {
    var owner = java.util.Objects.requireNonNull(SessionQueryTransaction.current(query));
    owner.requireProjectionLocks(tenant, task);
    Object sizeOwner = new Object();
    io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork.Projection projection;
    query.retainScratch(sizeOwner, 4096);
    try {
      boolean lineage = role == ProjectionRole.LINEAGE_SCOPE;
      var sizes =
          JdbcLedger.first(
              lineage
                  ? sql.lockLineageScopeSizes(query, tenant, task)
                  : sql.lockAcceptedSizes(query, tenant, task));
      long textBytes = 0;
      for (String key :
          lineage
              ? List.of("capabilities", "tools_snapshot")
              : List.of("goal", "capabilities", "summary", "tools_snapshot", "mcp_snapshot")) {
        query.operation(2L + key.length());
        var value = sizes.get(key);
        if (value == null) continue;
        if (!(value instanceof Long || value instanceof Integer)
            || ((Number) value).longValue() < 0) throw JdbcSessionRecords.corrupt();
        textBytes = Math.addExact(textBytes, ((Number) value).longValue());
      }
      // 两份provider/Map的UTF16文本、固定21列/标量/labels与转交；不信旧raw或DDL最大值。
      projection =
          owner.reserveProjection(
              role, tenant, task, Math.addExact(8192, Math.multiplyExact(8, textBytes)));
      sizes = null;
    } catch (RuntimeException failure) {
      throw owner.poisonProof(failure);
    } finally {
      query.releaseScratch(sizeOwner);
    }
    try {
      return projection.loaded(
          JdbcLedger.first(
              switch (role) {
                case LINEAGE_SCOPE -> sql.lockLineageScope(query, tenant, task);
                case ACCEPTED_EVENT -> sql.lockAcceptedEvent(query, tenant, task);
                case ACCEPTED_VIEW -> sql.lockAcceptedView(query, tenant, task);
              }));
    } catch (RuntimeException failure) {
      throw owner.poisonProof(failure);
    }
  }
}
