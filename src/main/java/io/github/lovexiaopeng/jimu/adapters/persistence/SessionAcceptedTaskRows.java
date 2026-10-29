package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
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
    var owner = java.util.Objects.requireNonNull(SessionQueryTransaction.current(query));
    Object sizeOwner = new Object(), rowOwner = new Object();
    query.retainScratch(sizeOwner, 4096);
    try {
      var sizes = JdbcLedger.first(sql.lockAcceptedSizes(query, tenant, task));
      long textBytes = 0;
      for (String key :
          List.of("goal", "capabilities", "summary", "tools_snapshot", "mcp_snapshot")) {
        query.operation(2L + key.length());
        var value = sizes.get(key);
        if (value == null) continue;
        if (!(value instanceof Long || value instanceof Integer)
            || ((Number) value).longValue() < 0) throw JdbcSessionRecords.corrupt();
        textBytes = Math.addExact(textBytes, ((Number) value).longValue());
      }
      // 两份provider/Map的UTF16文本、固定21列/标量/labels与转交；不信旧raw或DDL最大值。
      owner.retainAcceptedProjection(
          rowOwner, Math.addExact(8192, Math.multiplyExact(8, textBytes)));
      sizes = null;
    } finally {
      query.releaseScratch(sizeOwner);
    }
    return JdbcLedger.first(
        event
            ? sql.lockAcceptedEvent(query, tenant, task)
            : sql.lockAcceptedView(query, tenant, task));
  }
}
