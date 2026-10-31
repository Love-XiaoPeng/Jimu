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
    if (matchesTask(bound)) return new TaskSource(configuration, bound, type);
    if (!matches(bound)) return source;
    return new HistorySource(configuration, bound, type);
  }

  /** 原 TASK 具名长度投影；只在 Mapper 初始化固定 SQL，绝不保存行资格。 */
  public static final String TASK_SQL =
      "SELECT tenant_id,id,owner_actor_id,idempotency_key,request_digest,OCTET_LENGTH(CONVERT(capabilities USING utf8mb4)) AS capabilities,installation_epoch,policy_version,record_version,state,revision,created_at,updated_at,deadline,ready_at,cancel_requested_at,current_attempt_id,worker_id,fence,lease_until,next_event_seq,max_models,max_tools,models_used,tools_used,token_held,max_tokens,code,OCTET_LENGTH(CONVERT(model_snapshot USING utf8mb4)) AS model_snapshot,model_digest,evaluation_run_id,cost_held,max_cost_micros,OCTET_LENGTH(CONVERT(tools_snapshot USING utf8mb4)) AS tools_snapshot,tools_digest,report_time,OCTET_LENGTH(CONVERT(evaluation_snapshot USING utf8mb4)) AS evaluation_snapshot,evaluation_digest,evaluation_phase,evaluation_sample,evaluation_model,OCTET_LENGTH(CONVERT(material_snapshot USING utf8mb4)) AS material_snapshot,material_digest,material_system_digest,OCTET_LENGTH(CONVERT(material_file_snapshot USING utf8mb4)) AS material_file_snapshot,material_file_digest,execution_scope_digest,OCTET_LENGTH(CONVERT(business_policy_snapshot USING utf8mb4)) AS business_policy_snapshot,business_policy_digest,OCTET_LENGTH(CONVERT(media_snapshot USING utf8mb4)) AS media_snapshot,media_digest,media_cleanup_state,OCTET_LENGTH(CONVERT(mcp_snapshot USING utf8mb4)) AS mcp_snapshot,mcp_digest,OCTET_LENGTH(CONVERT(question_binding USING utf8mb4)) AS question_binding,question_binding_digest,waiting_question_id,question_continuation_step_id,question_cleanup_state,OCTET_LENGTH(CONVERT(question_retained_digests USING utf8mb4)) AS question_retained_digests FROM task WHERE tenant_id=? AND id=?";

  public static boolean ownsTask(SqlSource source) {
    return source instanceof TaskSource;
  }

  private static boolean matchesTask(BoundSql bound) {
    var mappings = bound.getParameterMappings();
    return TASK_SQL.equals(bound.getSql())
        && mappings.size() == 2
        && "tenant".equals(mappings.get(0).getProperty())
        && "taskId".equals(mappings.get(1).getProperty())
        && mappings.get(0).getMode() == ParameterMode.IN
        && mappings.get(1).getMode() == ParameterMode.IN;
  }

  private static final class TaskSource extends RawSqlSource {
    private final Configuration configuration;
    private final List<ParameterMapping> mappings;

    private TaskSource(Configuration configuration, BoundSql bound, Class<?> type) {
      super(configuration, bound.getSql(), type);
      this.configuration = configuration;
      mappings = List.copyOf(bound.getParameterMappings());
    }

    @Override
    public BoundSql getBoundSql(Object parameter) {
      return new BoundSql(configuration, TASK_SQL, mappings, parameter);
    }
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
