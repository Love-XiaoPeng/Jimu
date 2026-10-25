package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryViews;
import java.util.List;
import java.util.Map;

/** 原 seq/type 两列 MP 查询、完整键和计量作用域；保留原行对象及值类型。 */
final class MapperSessionRecentEvents implements SessionHistoryViews.RecentQuery {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;

  MapperSessionRecentEvents(JdbcSessionRecords records, Map<String, Object> task) {
    this.records = records;
    this.task = task;
  }

  record Result(List<Map<String, Object>> values) implements SessionHistoryViews.RecentRows {
    public int size() {
      return values.size();
    }
  }

  public SessionHistoryViews.RecentRows read(long upper) {
    var wrapper =
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                    .ScopeQueryNativeEntity>()
            .select(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq,
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getType)
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTenantId,
                JdbcSessionRecords.s(task, "tenant_id"))
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTaskId,
                JdbcSessionRecords.s(task, "id"))
            .between(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq,
                Math.max(0, upper - 7),
                upper)
            .orderByAsc(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq)
            .last("LIMIT 8");
    var args = new java.util.HashMap<String, Object>();
    args.put("query", records.appendQuery());
    args.put("ew", wrapper);
    List<Map<String, Object>> result;
    try (var ignored = records.recentMeter()) {
      result =
          records
              .mapperSession()
              .selectList(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper.selectMaps",
                  args);
    }
    return new Result(result);
  }
}
