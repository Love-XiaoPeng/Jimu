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
    String id = script.getStringAttribute("id");
    if ("metadataOrdinaryRows".equals(id)
        && ordinarySql().equals(bound.getSql())
        && ordinaryMappings(bound)) return new RowSource(configuration, bound, type, -2);
    String expected = rowSql(id);
    if (expected != null && expected.equals(bound.getSql()) && rowMappings(bound))
      return new RowSource(configuration, bound, type, rowTable(id));
    if (SessionLineageSourceColumns.STEP_SQL.equals(bound.getSql()) && stepMappings(bound))
      return new RowSource(configuration, bound, type, -1);
    if (matches(bound)) return new HistorySource(configuration, bound, type, false);
    if (nodeMatches(bound)) return new HistorySource(configuration, bound, type, true);
    return source;
  }

  private static String ordinarySql() {
    String[] names = {
      "SessionPublicEvent",
      "InvocationSessionRead",
      "InvocationSessionSearch",
      "InvocationSessionTrace",
      "TaskSessionScope",
      "TaskSessionScopeTarget",
      "StepSessionScope",
      "InvocationSessionScopeQuery",
      "TaskSessionLineage",
      "InvocationSessionLineageTrace",
      "TaskAttempt",
      "Step",
      "Invocation",
      "ModelDispatch",
      "Event"
    };
    var sql = new StringBuilder();
    for (int i = 0; i < names.length; i++) {
      if (i != 0) sql.append(" UNION ALL ");
      sql.append("SELECT CAST(")
          .append(i < 14 ? i + 2 : 17)
          .append(" AS SIGNED) AS table_tag,row_bytes FROM (")
          .append(rowSql("metadata" + names[i] + "Row"))
          .append(") AS row_source");
    }
    return sql.append(" ORDER BY table_tag").toString();
  }

  private static boolean ordinaryMappings(BoundSql bound) {
    int cursor = 0;
    var mappings = bound.getParameterMappings();
    var branches = bound.getSql().split(" UNION ALL ");
    if (branches.length != 15) return false;
    for (var branch : branches) {
      long parameters = branch.chars().filter(c -> c == '?').count();
      for (int i = 0; i < parameters; i++) {
        if (cursor >= mappings.size()) return false;
        var mapping = mappings.get(cursor++);
        String expected =
            i < parameters - 2 ? "nullBytes" : i == parameters - 2 ? "tenant" : "taskId";
        if (mapping.getMode() != ParameterMode.IN || !expected.equals(mapping.getProperty()))
          return false;
      }
    }
    return cursor == mappings.size();
  }

  // Exact, immutable ordinary-row projections. The SQL keeps the original scalar formula,
  // NULL presence and text bounds; TASK and history retain their existing special readers.
  private static String rowSql(String id) {
    if (id == null) return null;
    return switch (id) {
      case "metadataTaskSizedRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>36 OR owner_actor_id IS NULL OR CHAR_LENGTH(owner_actor_id)>64 OR idempotency_key IS NULL OR CHAR_LENGTH(idempotency_key)>128 OR request_digest IS NULL OR CHAR_LENGTH(request_digest)>64 OR installation_epoch IS NULL OR policy_version IS NULL OR record_version IS NULL OR state IS NULL OR CHAR_LENGTH(state)>32 OR revision IS NULL OR created_at IS NULL OR (created_at IS NOT NULL AND NOT COALESCE(MONTH(created_at) BETWEEN 1 AND 12 AND DAYOFMONTH(created_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(created_at)),FALSE)) OR updated_at IS NULL OR (updated_at IS NOT NULL AND NOT COALESCE(MONTH(updated_at) BETWEEN 1 AND 12 AND DAYOFMONTH(updated_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(updated_at)),FALSE)) OR deadline IS NULL OR (deadline IS NOT NULL AND NOT COALESCE(MONTH(deadline) BETWEEN 1 AND 12 AND DAYOFMONTH(deadline) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(deadline)),FALSE)) OR ready_at IS NULL OR (ready_at IS NOT NULL AND NOT COALESCE(MONTH(ready_at) BETWEEN 1 AND 12 AND DAYOFMONTH(ready_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(ready_at)),FALSE)) OR (cancel_requested_at IS NOT NULL AND NOT COALESCE(MONTH(cancel_requested_at) BETWEEN 1 AND 12 AND DAYOFMONTH(cancel_requested_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(cancel_requested_at)),FALSE)) OR CHAR_LENGTH(current_attempt_id)>36 OR CHAR_LENGTH(worker_id)>36 OR fence IS NULL OR (lease_until IS NOT NULL AND NOT COALESCE(MONTH(lease_until) BETWEEN 1 AND 12 AND DAYOFMONTH(lease_until) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(lease_until)),FALSE)) OR next_event_seq IS NULL OR max_models IS NULL OR max_tools IS NULL OR models_used IS NULL OR tools_used IS NULL OR token_held IS NULL OR max_tokens IS NULL OR CHAR_LENGTH(code)>64 OR CHAR_LENGTH(model_digest)>64 OR CHAR_LENGTH(evaluation_run_id)>64 OR cost_held IS NULL OR max_cost_micros IS NULL OR CHAR_LENGTH(tools_digest)>64 OR (report_time IS NOT NULL AND NOT COALESCE(MONTH(report_time) BETWEEN 1 AND 12 AND DAYOFMONTH(report_time) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(report_time)),FALSE)) OR CHAR_LENGTH(evaluation_digest)>64 OR CHAR_LENGTH(evaluation_phase)>1 OR CHAR_LENGTH(evaluation_sample)>8 OR CHAR_LENGTH(evaluation_model)>160 OR CHAR_LENGTH(material_digest)>64 OR CHAR_LENGTH(material_system_digest)>64 OR CHAR_LENGTH(material_file_digest)>64 OR CHAR_LENGTH(execution_scope_digest)>64 OR CHAR_LENGTH(business_policy_digest)>64 OR CHAR_LENGTH(media_digest)>64 OR CHAR_LENGTH(media_cleanup_state)>24 OR CHAR_LENGTH(mcp_digest)>64 OR CHAR_LENGTH(question_binding_digest)>64 OR CHAR_LENGTH(waiting_question_id)>36 OR CHAR_LENGTH(question_continuation_step_id)>36 OR CHAR_LENGTH(question_cleanup_state)>24,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(owner_actor_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(idempotency_key USING utf8mb4))+8+OCTET_LENGTH(CONVERT(request_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(installation_epoch AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(policy_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+8+OCTET_LENGTH(CAST(revision AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(DATE_FORMAT(deadline,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(DATE_FORMAT(ready_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+IF(cancel_requested_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(cancel_requested_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+IF(current_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(current_attempt_id USING utf8mb4)))+IF(worker_id IS NULL,?,8+OCTET_LENGTH(CONVERT(worker_id USING utf8mb4)))+8+OCTET_LENGTH(CAST(fence AS CHAR CHARACTER SET utf8mb4))+IF(lease_until IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(lease_until,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CAST(next_event_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(max_models AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(max_tools AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(models_used AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(tools_used AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(token_held AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(max_tokens AS CHAR CHARACTER SET utf8mb4))+IF(code IS NULL,?,8+OCTET_LENGTH(CONVERT(code USING utf8mb4)))+IF(model_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(model_digest USING utf8mb4)))+IF(evaluation_run_id IS NULL,?,8+OCTET_LENGTH(CONVERT(evaluation_run_id USING utf8mb4)))+8+OCTET_LENGTH(CAST(cost_held AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(max_cost_micros AS CHAR CHARACTER SET utf8mb4))+IF(tools_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(tools_digest USING utf8mb4)))+IF(report_time IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(report_time,'%Y-%m-%dT%H:%i:%s.%fZ')))+IF(evaluation_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(evaluation_digest USING utf8mb4)))+IF(evaluation_phase IS NULL,?,8+OCTET_LENGTH(CONVERT(evaluation_phase USING utf8mb4)))+IF(evaluation_sample IS NULL,?,8+OCTET_LENGTH(CONVERT(evaluation_sample USING utf8mb4)))+IF(evaluation_model IS NULL,?,8+OCTET_LENGTH(CONVERT(evaluation_model USING utf8mb4)))+IF(material_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(material_digest USING utf8mb4)))+IF(material_system_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(material_system_digest USING utf8mb4)))+IF(material_file_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(material_file_digest USING utf8mb4)))+IF(execution_scope_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(execution_scope_digest USING utf8mb4)))+IF(business_policy_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(business_policy_digest USING utf8mb4)))+IF(media_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(media_digest USING utf8mb4)))+IF(media_cleanup_state IS NULL,?,8+OCTET_LENGTH(CONVERT(media_cleanup_state USING utf8mb4)))+IF(mcp_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(mcp_digest USING utf8mb4)))+IF(question_binding_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(question_binding_digest USING utf8mb4)))+IF(waiting_question_id IS NULL,?,8+OCTET_LENGTH(CONVERT(waiting_question_id USING utf8mb4)))+IF(question_continuation_step_id IS NULL,?,8+OCTET_LENGTH(CONVERT(question_continuation_step_id USING utf8mb4)))+IF(question_cleanup_state IS NULL,?,8+OCTET_LENGTH(CONVERT(question_cleanup_state USING utf8mb4)))) AS SIGNED) AS base_bytes,OCTET_LENGTH(CONVERT(capabilities USING utf8mb4)) AS capabilities,OCTET_LENGTH(CONVERT(model_snapshot USING utf8mb4)) AS model_snapshot,OCTET_LENGTH(CONVERT(tools_snapshot USING utf8mb4)) AS tools_snapshot,OCTET_LENGTH(CONVERT(evaluation_snapshot USING utf8mb4)) AS evaluation_snapshot,OCTET_LENGTH(CONVERT(material_snapshot USING utf8mb4)) AS material_snapshot,OCTET_LENGTH(CONVERT(material_file_snapshot USING utf8mb4)) AS material_file_snapshot,OCTET_LENGTH(CONVERT(business_policy_snapshot USING utf8mb4)) AS business_policy_snapshot,OCTET_LENGTH(CONVERT(media_snapshot USING utf8mb4)) AS media_snapshot,OCTET_LENGTH(CONVERT(mcp_snapshot USING utf8mb4)) AS mcp_snapshot,OCTET_LENGTH(CONVERT(question_binding USING utf8mb4)) AS question_binding,OCTET_LENGTH(CONVERT(question_retained_digests USING utf8mb4)) AS question_retained_digests FROM task WHERE tenant_id=? AND id=?";
      case "metadataSessionPublicEventRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR seq IS NULL OR record_version IS NULL OR event_version IS NULL OR fact_key IS NULL OR CHAR_LENGTH(fact_key)>96 OR type IS NULL OR CHAR_LENGTH(type)>32 OR occurred_at IS NULL OR (occurred_at IS NOT NULL AND NOT COALESCE(MONTH(occurred_at) BETWEEN 1 AND 12 AND DAYOFMONTH(occurred_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(occurred_at)),FALSE)) OR event_digest IS NULL OR CHAR_LENGTH(event_digest)>64 OR event_bytes IS NULL OR source_digest IS NULL OR CHAR_LENGTH(source_digest)>64 OR CHAR_LENGTH(source_step_id)>36 OR CHAR_LENGTH(source_invocation_id)>36 OR CHAR_LENGTH(committed_attempt_id)>36 OR reliable_event_seq IS NULL OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(event_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(fact_key USING utf8mb4))+8+OCTET_LENGTH(CONVERT(type USING utf8mb4))+8+OCTET_LENGTH(DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(CONVERT(event_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(event_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(source_digest USING utf8mb4))+IF(source_step_id IS NULL,?,8+OCTET_LENGTH(CONVERT(source_step_id USING utf8mb4)))+IF(source_invocation_id IS NULL,?,8+OCTET_LENGTH(CONVERT(source_invocation_id USING utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+8+OCTET_LENGTH(CAST(reliable_event_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM session_public_event WHERE tenant_id=? AND task_id=?";
      case "metadataInvocationSessionReadRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR invocation_id IS NULL OR CHAR_LENGTH(invocation_id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR record_version IS NULL OR binding_digest IS NULL OR CHAR_LENGTH(binding_digest)>64 OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR upper_seq IS NULL OR target_seq IS NULL OR before_count IS NULL OR after_count IS NULL OR outcome IS NULL OR CHAR_LENGTH(outcome)>16 OR CHAR_LENGTH(failure_code)>64 OR CHAR_LENGTH(target_digest)>64 OR CHAR_LENGTH(output_digest)>64 OR CHAR_LENGTH(committed_attempt_id)>36 OR (committed_at IS NOT NULL AND NOT COALESCE(MONTH(committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(committed_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16 OR relation_version IS NULL OR CHAR_LENGTH(read_sources_digest)>64,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(invocation_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(upper_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(target_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(before_count AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(after_count AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(outcome USING utf8mb4))+IF(failure_code IS NULL,?,8+OCTET_LENGTH(CONVERT(failure_code USING utf8mb4)))+IF(window_start IS NULL,?,8+OCTET_LENGTH(CAST(window_start AS CHAR CHARACTER SET utf8mb4)))+IF(window_end IS NULL,?,8+OCTET_LENGTH(CAST(window_end AS CHAR CHARACTER SET utf8mb4)))+IF(target_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(target_digest USING utf8mb4)))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+IF(output_bytes IS NULL,?,8+OCTET_LENGTH(CAST(output_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(receipt_seq IS NULL,?,8+OCTET_LENGTH(CAST(receipt_seq AS CHAR CHARACTER SET utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))+8+OCTET_LENGTH(CAST(relation_version AS CHAR CHARACTER SET utf8mb4))+IF(read_sources_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(read_sources_digest USING utf8mb4)))+IF(read_sources_bytes IS NULL,?,8+OCTET_LENGTH(CAST(read_sources_bytes AS CHAR CHARACTER SET utf8mb4)))) AS SIGNED) AS row_bytes FROM invocation_session_read WHERE tenant_id=? AND task_id=?";
      case "metadataInvocationSessionSearchRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR invocation_id IS NULL OR CHAR_LENGTH(invocation_id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR record_version IS NULL OR binding_digest IS NULL OR CHAR_LENGTH(binding_digest)>64 OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR upper_seq IS NULL OR normalized_query_digest IS NULL OR CHAR_LENGTH(normalized_query_digest)>64 OR normalized_query_bytes IS NULL OR outcome IS NULL OR CHAR_LENGTH(outcome)>16 OR CHAR_LENGTH(failure_code)>64 OR CHAR_LENGTH(scan_digest)>64 OR CHAR_LENGTH(output_digest)>64 OR CHAR_LENGTH(committed_attempt_id)>36 OR (committed_at IS NOT NULL AND NOT COALESCE(MONTH(committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(committed_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(invocation_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(upper_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(normalized_query_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(normalized_query_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(outcome USING utf8mb4))+IF(failure_code IS NULL,?,8+OCTET_LENGTH(CONVERT(failure_code USING utf8mb4)))+IF(scan_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(scan_digest USING utf8mb4)))+IF(scan_rows IS NULL,?,8+OCTET_LENGTH(CAST(scan_rows AS CHAR CHARACTER SET utf8mb4)))+IF(scan_event_bytes IS NULL,?,8+OCTET_LENGTH(CAST(scan_event_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+IF(output_bytes IS NULL,?,8+OCTET_LENGTH(CAST(output_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(receipt_event_seq IS NULL,?,8+OCTET_LENGTH(CAST(receipt_event_seq AS CHAR CHARACTER SET utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM invocation_session_search WHERE tenant_id=? AND task_id=?";
      case "metadataInvocationSessionTraceRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR invocation_id IS NULL OR CHAR_LENGTH(invocation_id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR record_version IS NULL OR binding_digest IS NULL OR CHAR_LENGTH(binding_digest)>64 OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR upper_seq IS NULL OR target_seq IS NULL OR relation_policy IS NULL OR CHAR_LENGTH(relation_policy)>32 OR outcome IS NULL OR CHAR_LENGTH(outcome)>16 OR CHAR_LENGTH(failure_code)>64 OR CHAR_LENGTH(scan_digest)>64 OR CHAR_LENGTH(relations_digest)>64 OR CHAR_LENGTH(target_digest)>64 OR CHAR_LENGTH(output_digest)>64 OR CHAR_LENGTH(committed_attempt_id)>36 OR (committed_at IS NOT NULL AND NOT COALESCE(MONTH(committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(committed_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(invocation_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(upper_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(target_seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(relation_policy USING utf8mb4))+8+OCTET_LENGTH(CONVERT(outcome USING utf8mb4))+IF(failure_code IS NULL,?,8+OCTET_LENGTH(CONVERT(failure_code USING utf8mb4)))+IF(scan_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(scan_digest USING utf8mb4)))+IF(relations_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(relations_digest USING utf8mb4)))+IF(target_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(target_digest USING utf8mb4)))+IF(scan_rows IS NULL,?,8+OCTET_LENGTH(CAST(scan_rows AS CHAR CHARACTER SET utf8mb4)))+IF(scan_event_bytes IS NULL,?,8+OCTET_LENGTH(CAST(scan_event_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(relation_edges IS NULL,?,8+OCTET_LENGTH(CAST(relation_edges AS CHAR CHARACTER SET utf8mb4)))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+IF(output_bytes IS NULL,?,8+OCTET_LENGTH(CAST(output_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(receipt_event_seq IS NULL,?,8+OCTET_LENGTH(CAST(receipt_event_seq AS CHAR CHARACTER SET utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM invocation_session_trace WHERE tenant_id=? AND task_id=?";
      case "metadataTaskSessionScopeRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR caller_task_id IS NULL OR CHAR_LENGTH(caller_task_id)>36 OR record_version IS NULL OR authority_version IS NULL OR owner_actor_id IS NULL OR CHAR_LENGTH(owner_actor_id)>64 OR caller_binding_digest IS NULL OR CHAR_LENGTH(caller_binding_digest)>64 OR caller_tools_digest IS NULL OR CHAR_LENGTH(caller_tools_digest)>64 OR authority_digest IS NULL OR CHAR_LENGTH(authority_digest)>64 OR authority_bytes IS NULL OR candidate_count IS NULL OR grant_state IS NULL OR CHAR_LENGTH(grant_state)>16 OR created_at IS NULL OR (created_at IS NOT NULL AND NOT COALESCE(MONTH(created_at) BETWEEN 1 AND 12 AND DAYOFMONTH(created_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(created_at)),FALSE)) OR retain_until IS NULL OR (retain_until IS NOT NULL AND NOT COALESCE(MONTH(retain_until) BETWEEN 1 AND 12 AND DAYOFMONTH(retain_until) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(retain_until)),FALSE)) OR (revoked_at IS NOT NULL AND NOT COALESCE(MONTH(revoked_at) BETWEEN 1 AND 12 AND DAYOFMONTH(revoked_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(revoked_at)),FALSE)) OR (invalidated_at IS NOT NULL AND NOT COALESCE(MONTH(invalidated_at) BETWEEN 1 AND 12 AND DAYOFMONTH(invalidated_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(invalidated_at)),FALSE)) OR next_validation_at IS NULL OR (next_validation_at IS NOT NULL AND NOT COALESCE(MONTH(next_validation_at) BETWEEN 1 AND 12 AND DAYOFMONTH(next_validation_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(next_validation_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(authority_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(owner_actor_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_tools_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(authority_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(authority_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(candidate_count AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(grant_state USING utf8mb4))+8+OCTET_LENGTH(DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(DATE_FORMAT(retain_until,'%Y-%m-%dT%H:%i:%s.%fZ'))+IF(revoked_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(revoked_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+IF(invalidated_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(invalidated_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(DATE_FORMAT(next_validation_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM task_session_scope WHERE tenant_id=? AND caller_task_id=?";
      case "metadataTaskSessionScopeTargetRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR caller_task_id IS NULL OR CHAR_LENGTH(caller_task_id)>36 OR ordinal IS NULL OR record_version IS NULL OR target_digest IS NULL OR CHAR_LENGTH(target_digest)>64 OR target_bytes IS NULL OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(ordinal AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(target_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(target_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM task_session_scope_target WHERE tenant_id=? AND caller_task_id=?";
      case "metadataStepSessionScopeRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR caller_task_id IS NULL OR CHAR_LENGTH(caller_task_id)>36 OR record_version IS NULL OR authority_digest IS NULL OR CHAR_LENGTH(authority_digest)>64 OR context_digest IS NULL OR CHAR_LENGTH(context_digest)>64 OR context_bytes IS NULL OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(authority_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(context_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(context_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM step_session_scope WHERE tenant_id=? AND caller_task_id=?";
      case "metadataInvocationSessionScopeQueryRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR invocation_id IS NULL OR CHAR_LENGTH(invocation_id)>36 OR caller_task_id IS NULL OR CHAR_LENGTH(caller_task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR record_version IS NULL OR operation IS NULL OR CHAR_LENGTH(operation)>8 OR binding_digest IS NULL OR CHAR_LENGTH(binding_digest)>64 OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR authority_digest IS NULL OR CHAR_LENGTH(authority_digest)>64 OR context_digest IS NULL OR CHAR_LENGTH(context_digest)>64 OR normalized_digest IS NULL OR CHAR_LENGTH(normalized_digest)>64 OR normalized_bytes IS NULL OR outcome IS NULL OR CHAR_LENGTH(outcome)>16 OR CHAR_LENGTH(failure_code)>64 OR CHAR_LENGTH(scan_digest)>64 OR CHAR_LENGTH(read_refs_digest)>64 OR CHAR_LENGTH(output_digest)>64 OR CHAR_LENGTH(committed_attempt_id)>36 OR (committed_at IS NOT NULL AND NOT COALESCE(MONTH(committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(committed_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(invocation_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(operation USING utf8mb4))+8+OCTET_LENGTH(CONVERT(binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(authority_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(context_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(normalized_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(normalized_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(outcome USING utf8mb4))+IF(failure_code IS NULL,?,8+OCTET_LENGTH(CONVERT(failure_code USING utf8mb4)))+IF(scan_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(scan_digest USING utf8mb4)))+IF(scan_rows IS NULL,?,8+OCTET_LENGTH(CAST(scan_rows AS CHAR CHARACTER SET utf8mb4)))+IF(scan_event_bytes IS NULL,?,8+OCTET_LENGTH(CAST(scan_event_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(read_refs_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(read_refs_digest USING utf8mb4)))+IF(read_refs_bytes IS NULL,?,8+OCTET_LENGTH(CAST(read_refs_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+IF(output_bytes IS NULL,?,8+OCTET_LENGTH(CAST(output_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(receipt_event_seq IS NULL,?,8+OCTET_LENGTH(CAST(receipt_event_seq AS CHAR CHARACTER SET utf8mb4)))+IF(native_read_seq IS NULL,?,8+OCTET_LENGTH(CAST(native_read_seq AS CHAR CHARACTER SET utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM invocation_session_scope_query WHERE tenant_id=? AND caller_task_id=?";
      case "metadataTaskAttemptRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR worker_id IS NULL OR CHAR_LENGTH(worker_id)>36 OR fence IS NULL OR state IS NULL OR CHAR_LENGTH(state)>24 OR started_at IS NULL OR (started_at IS NOT NULL AND NOT COALESCE(MONTH(started_at) BETWEEN 1 AND 12 AND DAYOFMONTH(started_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(started_at)),FALSE)) OR (ended_at IS NOT NULL AND NOT COALESCE(MONTH(ended_at) BETWEEN 1 AND 12 AND DAYOFMONTH(ended_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(ended_at)),FALSE)) OR record_version IS NULL OR quiescent IS NULL,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(worker_id USING utf8mb4))+8+OCTET_LENGTH(CAST(fence AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+8+OCTET_LENGTH(DATE_FORMAT(started_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+IF(ended_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(ended_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+IF(quiescent,4,5)) AS SIGNED) AS row_bytes FROM task_attempt WHERE tenant_id=? AND task_id=?";
      case "metadataStepRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR attempt_id IS NULL OR CHAR_LENGTH(attempt_id)>36 OR ordinal IS NULL OR state IS NULL OR CHAR_LENGTH(state)>24 OR request_digest IS NULL OR CHAR_LENGTH(request_digest)>64 OR CHAR_LENGTH(response_digest)>64 OR CHAR_LENGTH(dispatch_id)>36 OR dispatches IS NULL OR model_version IS NULL OR CHAR_LENGTH(model_version)>160 OR record_version IS NULL OR request_cleanup_state IS NULL OR CHAR_LENGTH(request_cleanup_state)>24 OR CHAR_LENGTH(retained_request_digest)>64 OR CHAR_LENGTH(directory_cwd)>512 OR CHAR_LENGTH(directory_binding_digest)>64 OR CHAR_LENGTH(session_context_digest)>64 OR CHAR_LENGTH(session_binding_digest)>64,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(attempt_id USING utf8mb4))+8+OCTET_LENGTH(CAST(ordinal AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+8+OCTET_LENGTH(CONVERT(request_digest USING utf8mb4))+IF(response_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(response_digest USING utf8mb4)))+IF(dispatch_id IS NULL,?,8+OCTET_LENGTH(CONVERT(dispatch_id USING utf8mb4)))+8+OCTET_LENGTH(CAST(dispatches AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(model_version USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(request_cleanup_state USING utf8mb4))+IF(retained_request_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(retained_request_digest USING utf8mb4)))+IF(directory_revision IS NULL,?,8+OCTET_LENGTH(CAST(directory_revision AS CHAR CHARACTER SET utf8mb4)))+IF(directory_cwd IS NULL,?,8+OCTET_LENGTH(CONVERT(directory_cwd USING utf8mb4)))+IF(directory_binding_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(directory_binding_digest USING utf8mb4)))+IF(session_upper_seq IS NULL,?,8+OCTET_LENGTH(CAST(session_upper_seq AS CHAR CHARACTER SET utf8mb4)))+IF(session_context_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(session_context_digest USING utf8mb4)))+IF(session_binding_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(session_binding_digest USING utf8mb4)))) AS SIGNED) AS row_bytes FROM step WHERE tenant_id=? AND task_id=?";
      case "metadataInvocationRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR attempt_id IS NULL OR CHAR_LENGTH(attempt_id)>36 OR ordinal IS NULL OR slot IS NULL OR CHAR_LENGTH(slot)>160 OR provider_id IS NULL OR CHAR_LENGTH(provider_id)>128 OR effect_intent_id IS NULL OR CHAR_LENGTH(effect_intent_id)>36 OR tool IS NULL OR CHAR_LENGTH(tool)>64 OR plugin_version IS NULL OR CHAR_LENGTH(plugin_version)>32 OR plugin_digest IS NULL OR CHAR_LENGTH(plugin_digest)>64 OR installation_epoch IS NULL OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR state IS NULL OR CHAR_LENGTH(state)>32 OR CHAR_LENGTH(output_digest)>64 OR projection_version IS NULL OR updated_at IS NULL OR (updated_at IS NOT NULL AND NOT COALESCE(MONTH(updated_at) BETWEEN 1 AND 12 AND DAYOFMONTH(updated_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(updated_at)),FALSE)) OR record_version IS NULL OR CHAR_LENGTH(todo_committed_attempt_id)>36 OR (todo_committed_at IS NOT NULL AND NOT COALESCE(MONTH(todo_committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(todo_committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(todo_committed_at)),FALSE)),-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(attempt_id USING utf8mb4))+8+OCTET_LENGTH(CAST(ordinal AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(slot USING utf8mb4))+8+OCTET_LENGTH(CONVERT(provider_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(effect_intent_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(tool USING utf8mb4))+8+OCTET_LENGTH(CONVERT(plugin_version USING utf8mb4))+8+OCTET_LENGTH(CONVERT(plugin_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(installation_epoch AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+8+OCTET_LENGTH(CAST(projection_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+IF(todo_expected_revision IS NULL,?,8+OCTET_LENGTH(CAST(todo_expected_revision AS CHAR CHARACTER SET utf8mb4)))+IF(todo_applied_revision IS NULL,?,8+OCTET_LENGTH(CAST(todo_applied_revision AS CHAR CHARACTER SET utf8mb4)))+IF(todo_committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(todo_committed_attempt_id USING utf8mb4)))+IF(todo_committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(todo_committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(todo_event_seq IS NULL,?,8+OCTET_LENGTH(CAST(todo_event_seq AS CHAR CHARACTER SET utf8mb4)))+IF(todo_committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(todo_committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))) AS SIGNED) AS row_bytes FROM invocation WHERE tenant_id=? AND task_id=?";
      case "metadataModelDispatchRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>36 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR config_digest IS NULL OR CHAR_LENGTH(config_digest)>64 OR request_digest IS NULL OR CHAR_LENGTH(request_digest)>64 OR state IS NULL OR CHAR_LENGTH(state)>24 OR CHAR_LENGTH(code)>64 OR started_at IS NULL OR (started_at IS NOT NULL AND NOT COALESCE(MONTH(started_at) BETWEEN 1 AND 12 AND DAYOFMONTH(started_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(started_at)),FALSE)) OR (ended_at IS NOT NULL AND NOT COALESCE(MONTH(ended_at) BETWEEN 1 AND 12 AND DAYOFMONTH(ended_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(ended_at)),FALSE)) OR CHAR_LENGTH(price_version)>128 OR CHAR_LENGTH(currency)>3 OR cost_quality IS NULL OR CHAR_LENGTH(cost_quality)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(config_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(request_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+IF(code IS NULL,?,8+OCTET_LENGTH(CONVERT(code USING utf8mb4)))+8+OCTET_LENGTH(DATE_FORMAT(started_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+IF(ended_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(ended_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+IF(input_tokens IS NULL,?,8+OCTET_LENGTH(CAST(input_tokens AS CHAR CHARACTER SET utf8mb4)))+IF(output_tokens IS NULL,?,8+OCTET_LENGTH(CAST(output_tokens AS CHAR CHARACTER SET utf8mb4)))+IF(total_tokens IS NULL,?,8+OCTET_LENGTH(CAST(total_tokens AS CHAR CHARACTER SET utf8mb4)))+IF(price_version IS NULL,?,8+OCTET_LENGTH(CONVERT(price_version USING utf8mb4)))+IF(currency IS NULL,?,8+OCTET_LENGTH(CONVERT(currency USING utf8mb4)))+IF(reserved_cost IS NULL,?,8+OCTET_LENGTH(CAST(reserved_cost AS CHAR CHARACTER SET utf8mb4)))+IF(actual_cost IS NULL,?,8+OCTET_LENGTH(CAST(actual_cost AS CHAR CHARACTER SET utf8mb4)))+8+OCTET_LENGTH(CONVERT(cost_quality USING utf8mb4))) AS SIGNED) AS row_bytes FROM model_dispatch WHERE tenant_id=? AND task_id=?";
      case "metadataReservationRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR id IS NULL OR CHAR_LENGTH(id)>80 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR CHAR_LENGTH(dispatch_id)>36 OR category IS NULL OR CHAR_LENGTH(category)>24 OR amount IS NULL OR usage_quality IS NULL OR CHAR_LENGTH(usage_quality)>16 OR cost_quality IS NULL OR CHAR_LENGTH(cost_quality)>16 OR state IS NULL OR CHAR_LENGTH(state)>24 OR record_version IS NULL,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+IF(dispatch_id IS NULL,?,8+OCTET_LENGTH(CONVERT(dispatch_id USING utf8mb4)))+8+OCTET_LENGTH(CONVERT(category USING utf8mb4))+8+OCTET_LENGTH(CAST(amount AS CHAR CHARACTER SET utf8mb4))+IF(actual IS NULL,?,8+OCTET_LENGTH(CAST(actual AS CHAR CHARACTER SET utf8mb4)))+8+OCTET_LENGTH(CONVERT(usage_quality USING utf8mb4))+8+OCTET_LENGTH(CONVERT(cost_quality USING utf8mb4))+8+OCTET_LENGTH(CONVERT(state USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))) AS SIGNED) AS row_bytes,category,amount,actual,state FROM reservation WHERE tenant_id=? AND task_id=?";
      case "metadataEventRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR seq IS NULL OR event_id IS NULL OR CHAR_LENGTH(event_id)>64 OR fact_id IS NULL OR CHAR_LENGTH(fact_id)>128 OR type IS NULL OR CHAR_LENGTH(type)>64 OR CHAR_LENGTH(code)>64 OR status IS NULL OR CHAR_LENGTH(status)>32 OR CHAR_LENGTH(artifact_id)>36 OR occurred_at IS NULL OR (occurred_at IS NOT NULL AND NOT COALESCE(MONTH(occurred_at) BETWEEN 1 AND 12 AND DAYOFMONTH(occurred_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(occurred_at)),FALSE)) OR payload_version IS NULL,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(seq AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(event_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(fact_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(type USING utf8mb4))+IF(code IS NULL,?,8+OCTET_LENGTH(CONVERT(code USING utf8mb4)))+8+OCTET_LENGTH(CONVERT(status USING utf8mb4))+IF(artifact_id IS NULL,?,8+OCTET_LENGTH(CONVERT(artifact_id USING utf8mb4)))+8+OCTET_LENGTH(DATE_FORMAT(occurred_at,'%Y-%m-%dT%H:%i:%s.%fZ'))+8+OCTET_LENGTH(CAST(payload_version AS CHAR CHARACTER SET utf8mb4))) AS SIGNED) AS row_bytes FROM event WHERE tenant_id=? AND task_id=?";
      case "metadataInvocationSessionLineageTraceRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR invocation_id IS NULL OR CHAR_LENGTH(invocation_id)>36 OR caller_task_id IS NULL OR CHAR_LENGTH(caller_task_id)>36 OR step_id IS NULL OR CHAR_LENGTH(step_id)>36 OR record_version IS NULL OR binding_digest IS NULL OR CHAR_LENGTH(binding_digest)>64 OR input_digest IS NULL OR CHAR_LENGTH(input_digest)>64 OR authority_digest IS NULL OR CHAR_LENGTH(authority_digest)>64 OR graph_digest IS NULL OR CHAR_LENGTH(graph_digest)>64 OR context_digest IS NULL OR CHAR_LENGTH(context_digest)>64 OR normalized_digest IS NULL OR CHAR_LENGTH(normalized_digest)>64 OR normalized_bytes IS NULL OR outcome IS NULL OR CHAR_LENGTH(outcome)>16 OR CHAR_LENGTH(failure_code)>64 OR CHAR_LENGTH(scan_digest)>64 OR CHAR_LENGTH(output_digest)>64 OR CHAR_LENGTH(committed_attempt_id)>36 OR (committed_at IS NOT NULL AND NOT COALESCE(MONTH(committed_at) BETWEEN 1 AND 12 AND DAYOFMONTH(committed_at) BETWEEN 1 AND DAYOFMONTH(LAST_DAY(committed_at)),FALSE)) OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(invocation_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(caller_task_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(step_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(binding_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(input_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(authority_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(graph_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(context_digest USING utf8mb4))+8+OCTET_LENGTH(CONVERT(normalized_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(normalized_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(outcome USING utf8mb4))+IF(failure_code IS NULL,?,8+OCTET_LENGTH(CONVERT(failure_code USING utf8mb4)))+IF(scan_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(scan_digest USING utf8mb4)))+IF(node_count IS NULL,?,8+OCTET_LENGTH(CAST(node_count AS CHAR CHARACTER SET utf8mb4)))+IF(edge_count IS NULL,?,8+OCTET_LENGTH(CAST(edge_count AS CHAR CHARACTER SET utf8mb4)))+IF(proof_reads IS NULL,?,8+OCTET_LENGTH(CAST(proof_reads AS CHAR CHARACTER SET utf8mb4)))+IF(proof_bytes IS NULL,?,8+OCTET_LENGTH(CAST(proof_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(output_digest IS NULL,?,8+OCTET_LENGTH(CONVERT(output_digest USING utf8mb4)))+IF(output_bytes IS NULL,?,8+OCTET_LENGTH(CAST(output_bytes AS CHAR CHARACTER SET utf8mb4)))+IF(receipt_event_seq IS NULL,?,8+OCTET_LENGTH(CAST(receipt_event_seq AS CHAR CHARACTER SET utf8mb4)))+IF(committed_attempt_id IS NULL,?,8+OCTET_LENGTH(CONVERT(committed_attempt_id USING utf8mb4)))+IF(committed_fence IS NULL,?,8+OCTET_LENGTH(CAST(committed_fence AS CHAR CHARACTER SET utf8mb4)))+IF(committed_at IS NULL,?,8+OCTET_LENGTH(DATE_FORMAT(committed_at,'%Y-%m-%dT%H:%i:%s.%fZ')))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM invocation_session_lineage_trace WHERE tenant_id=? AND caller_task_id=?";
      case "metadataTaskSessionLineageRow" ->
          "SELECT CAST(IF(tenant_id IS NULL OR CHAR_LENGTH(tenant_id)>64 OR task_id IS NULL OR CHAR_LENGTH(task_id)>36 OR record_version IS NULL OR genesis_digest IS NULL OR CHAR_LENGTH(genesis_digest)>64 OR genesis_bytes IS NULL OR graph_digest IS NULL OR CHAR_LENGTH(graph_digest)>64 OR graph_bytes IS NULL OR content_state IS NULL OR CHAR_LENGTH(content_state)>16,-1,32+8+OCTET_LENGTH(CONVERT(tenant_id USING utf8mb4))+8+OCTET_LENGTH(CONVERT(task_id USING utf8mb4))+8+OCTET_LENGTH(CAST(record_version AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(genesis_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(genesis_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(graph_digest USING utf8mb4))+8+OCTET_LENGTH(CAST(graph_bytes AS CHAR CHARACTER SET utf8mb4))+8+OCTET_LENGTH(CONVERT(content_state USING utf8mb4))) AS SIGNED) AS row_bytes FROM task_session_lineage WHERE tenant_id=? AND task_id=?";
      default -> null;
    };
  }

  private static int rowTable(String id) {
    return switch (id) {
      case "metadataTaskSizedRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table.TASK
              .ordinal();
      case "metadataSessionPublicEventRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .SESSION_PUBLIC_EVENT
              .ordinal();
      case "metadataInvocationSessionReadRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .INVOCATION_SESSION_READ
              .ordinal();
      case "metadataInvocationSessionSearchRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .INVOCATION_SESSION_SEARCH
              .ordinal();
      case "metadataInvocationSessionTraceRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .INVOCATION_SESSION_TRACE
              .ordinal();
      case "metadataTaskSessionScopeRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .TASK_SESSION_SCOPE
              .ordinal();
      case "metadataTaskSessionScopeTargetRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .TASK_SESSION_SCOPE_TARGET
              .ordinal();
      case "metadataStepSessionScopeRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .STEP_SESSION_SCOPE
              .ordinal();
      case "metadataInvocationSessionScopeQueryRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .INVOCATION_SESSION_SCOPE_QUERY
              .ordinal();
      case "metadataTaskAttemptRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .TASK_ATTEMPT
              .ordinal();
      case "metadataStepRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table.STEP
              .ordinal();
      case "metadataInvocationRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table.INVOCATION
              .ordinal();
      case "metadataModelDispatchRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .MODEL_DISPATCH
              .ordinal();
      case "metadataReservationRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .RESERVATION
              .ordinal();
      case "metadataEventRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table.EVENT
              .ordinal();
      case "metadataInvocationSessionLineageTraceRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .INVOCATION_SESSION_LINEAGE_TRACE
              .ordinal();
      case "metadataTaskSessionLineageRow" ->
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table
              .TASK_SESSION_LINEAGE
              .ordinal();
      default -> -1;
    };
  }

  static boolean ownsModelStep(SqlSource source) {
    return source instanceof RowSource pinned && pinned.table == -1;
  }

  private static boolean stepMappings(BoundSql bound) {
    var mappings = bound.getParameterMappings();
    return mappings.size() == 3
        && "tenant".equals(mappings.get(0).getProperty())
        && "stepId".equals(mappings.get(1).getProperty())
        && "taskId".equals(mappings.get(2).getProperty())
        && mappings.stream().allMatch(p -> p.getMode() == ParameterMode.IN);
  }

  private static boolean rowMappings(BoundSql bound) {
    var mappings = bound.getParameterMappings();
    if (mappings.size() < 2) return false;
    for (int i = 0; i < mappings.size(); i++) {
      String name =
          i == mappings.size() - 2 ? "tenant" : i == mappings.size() - 1 ? "taskId" : "nullBytes";
      if (!name.equals(mappings.get(i).getProperty())
          || mappings.get(i).getMode() != ParameterMode.IN) return false;
    }
    return true;
  }

  public static void verifyRowCommand(
      org.apache.ibatis.mapping.MappedStatement command,
      int table,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      Object parameters) {
    query.operation(12);
    if (command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != java.util.Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty())
      throw new IllegalStateException("Changed metadata row command");
    if (command.getSqlSource() instanceof RowSource pinned && pinned.table == table) return;
    if (table == -2) throw new IllegalStateException("Changed ordinary metadata SQL");
    var id = command.getId();
    query.operation(4L + id.length());
    String name = id.substring(id.lastIndexOf('.') + 1);
    String expected = rowSql(name);
    if (expected == null
        || rowTable(name) != table
        || !(command.getSqlSource() instanceof RawSqlSource))
      throw new IllegalStateException("Unverified metadata row source");
    var bound = command.getBoundSql(parameters);
    query.operation(8L + expected.length() + 12L * bound.getParameterMappings().size());
    if (!expected.equals(bound.getSql()) || !rowMappings(bound))
      throw new IllegalStateException("Changed metadata row SQL");
  }

  private static final class RowSource extends RawSqlSource {
    private final Configuration configuration;
    private final String sql;
    private final List<ParameterMapping> mappings;
    private final int table;

    RowSource(Configuration configuration, BoundSql bound, Class<?> type, int table) {
      super(configuration, bound.getSql(), type);
      this.configuration = configuration;
      this.sql = bound.getSql();
      this.mappings = List.copyOf(bound.getParameterMappings());
      this.table = table;
    }

    @Override
    public BoundSql getBoundSql(Object parameter) {
      return new BoundSql(configuration, sql, mappings, parameter);
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
    return source instanceof HistorySource pinned && !pinned.node;
  }

  static boolean ownsNode(SqlSource source) {
    return source instanceof HistorySource pinned && pinned.node;
  }

  private static boolean nodeMatches(BoundSql bound) {
    var mappings = bound.getParameterMappings();
    return SessionLineageSourceColumns.NODE_SQL.equals(bound.getSql())
        && mappings.size() == 2
        && "tenant".equals(mappings.get(0).getProperty())
        && "id".equals(mappings.get(1).getProperty())
        && mappings.get(0).getMode() == ParameterMode.IN
        && mappings.get(1).getMode() == ParameterMode.IN;
  }

  /** RawSqlSource 的私有固定副本；不把可修改的参数 List 暴露给调用方。 */
  private static final class HistorySource extends RawSqlSource {
    private final Configuration configuration;
    private final String sql;
    private final List<ParameterMapping> mappings;
    private final boolean node;

    private HistorySource(
        Configuration configuration, BoundSql bound, Class<?> type, boolean node) {
      super(configuration, bound.getSql(), type);
      this.configuration = configuration;
      this.node = node;
      sql = bound.getSql();
      mappings = List.copyOf(bound.getParameterMappings());
      if (!(node ? nodeMatches(bound) : matches(bound)))
        throw new IllegalStateException("Changed pinned SQL source");
    }

    @Override
    public BoundSql getBoundSql(Object parameter) {
      return new BoundSql(configuration, sql, mappings, parameter);
    }
  }
}
