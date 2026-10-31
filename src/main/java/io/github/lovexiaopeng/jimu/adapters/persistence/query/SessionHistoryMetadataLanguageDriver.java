package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import java.util.List;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;

/** 在原 Mapper 解析时固定 history 长度投影；不持有请求、连接或行证明。 */
public final class SessionHistoryMetadataLanguageDriver extends XMLLanguageDriver {
  @Override
  public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> type) {
    var owner = SessionQueryTransaction.currentOwner();
    // 运行中的 Work 不允许借解析 Mapper 把新增初始化工作挪到账外。
    // 正常 Mapper 初始化没有 Work；保留原 XML 驱动路径供动态脚本明确拒绝资格。
    if (owner != null) {
      owner.inspect(6); // owner lookup, branch and original-driver handoff; no pin is allocated.
      return super.createSqlSource(configuration, script, type);
    }
    var source = super.createSqlSource(configuration, script, type);
    if (source.getClass() != RawSqlSource.class) return source;
    var bound = source.getBoundSql(null);
    if (!matches(bound)) return source;
    return new HistorySource(configuration, bound, type);
  }

  private static boolean matches(BoundSql bound) {
    var mappings = bound.getParameterMappings();
    return SessionHistorySizedMetadataBatch.SQL.equals(bound.getSql())
        && mappings.size() == 2
        && "tenant".equals(mappings.get(0).getProperty())
        && "taskId".equals(mappings.get(1).getProperty())
        && mappings.get(0).getMode() == ParameterMode.IN
        && mappings.get(1).getMode() == ParameterMode.IN;
  }

  static boolean owns(SqlSource source) {
    return source instanceof HistorySource;
  }

  /** RawSqlSource 的私有固定副本；不把可修改的参数 List 暴露给调用方。 */
  private static final class HistorySource extends RawSqlSource {
    private final Configuration configuration;
    private final String sql;
    private final List<ParameterMapping> mappings;

    private HistorySource(Configuration configuration, BoundSql bound, Class<?> type) {
      super(configuration, bound.getSql(), type);
      this.configuration = configuration;
      sql = bound.getSql();
      mappings = List.copyOf(bound.getParameterMappings());
      if (!matches(bound)) throw new IllegalStateException("Changed history metadata source");
    }

    @Override
    public BoundSql getBoundSql(Object parameter) {
      return new BoundSql(configuration, sql, mappings, parameter);
    }
  }
}
