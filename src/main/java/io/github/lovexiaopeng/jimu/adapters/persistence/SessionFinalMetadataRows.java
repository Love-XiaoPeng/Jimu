package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistorySizedMetadataBatch;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSession;

/** 原 MyBatis SELECT 的同步 FINAL 消费；当前列直接 fold，不生成 Map 或逐列资格对象。 */
public final class SessionFinalMetadataRows {
  private SessionFinalMetadataRows() {}

  private static final Object BOOTSTRAP = new Object();
  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadata";
  private static final String TASK_SQL =
      "SELECT tenant_id,id,owner_actor_id,idempotency_key,request_digest,OCTET_LENGTH(CONVERT(capabilities USING utf8mb4)) AS capabilities,installation_epoch,policy_version,record_version,state,revision,created_at,updated_at,deadline,ready_at,cancel_requested_at,current_attempt_id,worker_id,fence,lease_until,next_event_seq,max_models,max_tools,models_used,tools_used,token_held,max_tokens,code,OCTET_LENGTH(CONVERT(model_snapshot USING utf8mb4)) AS model_snapshot,model_digest,evaluation_run_id,cost_held,max_cost_micros,OCTET_LENGTH(CONVERT(tools_snapshot USING utf8mb4)) AS tools_snapshot,tools_digest,report_time,OCTET_LENGTH(CONVERT(evaluation_snapshot USING utf8mb4)) AS evaluation_snapshot,evaluation_digest,evaluation_phase,evaluation_sample,evaluation_model,OCTET_LENGTH(CONVERT(material_snapshot USING utf8mb4)) AS material_snapshot,material_digest,material_system_digest,OCTET_LENGTH(CONVERT(material_file_snapshot USING utf8mb4)) AS material_file_snapshot,material_file_digest,execution_scope_digest,OCTET_LENGTH(CONVERT(business_policy_snapshot USING utf8mb4)) AS business_policy_snapshot,business_policy_digest,OCTET_LENGTH(CONVERT(media_snapshot USING utf8mb4)) AS media_snapshot,media_digest,media_cleanup_state,OCTET_LENGTH(CONVERT(mcp_snapshot USING utf8mb4)) AS mcp_snapshot,mcp_digest,OCTET_LENGTH(CONVERT(question_binding USING utf8mb4)) AS question_binding,question_binding_digest,waiting_question_id,question_continuation_step_id,question_cleanup_state,OCTET_LENGTH(CONVERT(question_retained_digests USING utf8mb4)) AS question_retained_digests FROM task WHERE tenant_id=? AND id=?";

  static SessionMetadataAccounting.FinalTable measure(
      SqlSession session,
      SessionQueryContext query,
      SessionMetadataAccounting.Table table,
      String tenant,
      String task) {
    query.operation(14); // route, owner/projection lookup, request and cleanup handoff
    var owner = SessionQueryTransaction.currentOwner();
    var projection =
        table == SessionMetadataAccounting.Table.TASK
            ? SessionMetadataProjection.taskSized()
            : SessionMetadataProjection.forTable(table);
    if (owner == null || projection == null || SessionQueryTransaction.current(query) != owner)
      throw JdbcSessionRecords.corrupt();
    Request request = null;
    try {
      owner.requireProjectionLocks(tenant, task);
      // 保原两行 getter/workspace 上界；另含 request/summary/list/局部标量及异常清理。
      query.retainScratch(BOOTSTRAP, projection.retainedBytes + 4096);
      query.operation(24); // request allocation/fields, close stores prepaid on error too
      request = new Request(session, query, owner, table, projection, tenant, task);
      query.retainScratch(BOOTSTRAP, 64);
      query.retainScratch(request, projection.retainedBytes + 4096);
      query.releaseScratch(BOOTSTRAP);
      request.prepare();
      SessionMetadataAccounting.FinalTable result =
          session.selectOne(request.command.getId(), request);
      request.check();
      query.operation(3);
      if (!request.consumed || result == null) throw JdbcSessionRecords.corrupt();
      return result;
    } catch (RuntimeException | Error failure) {
      poison(owner, failure);
      throw failure;
    } finally {
      if (request != null) {
        request.closed = true;
        request.command = null;
        query.releaseScratch(request);
      }
      query.releaseScratch(BOOTSTRAP);
    }
  }

  private static void poison(SessionQueryWork owner, Throwable failure) {
    if (owner != null) {
      if (failure instanceof Error error) owner.poisonLineageSource(error);
      else
        owner.recordMetadataFailure(
            failure instanceof RuntimeException runtime
                ? runtime
                : new org.springframework.dao.DataAccessResourceFailureException(
                    "Metadata SQL unavailable", failure));
    }
    var actual = SessionQueryTransaction.currentOwner();
    if (actual != null && actual != owner) {
      if (failure instanceof Error error) actual.poisonLineageSource(error);
      else
        actual.recordMetadataFailure(
            failure instanceof RuntimeException runtime
                ? runtime
                : new org.springframework.dao.DataAccessResourceFailureException(
                    "Metadata SQL unavailable", failure));
    }
  }

  private static String statement(SessionMetadataAccounting.Table table) {
    return switch (table) {
      case TASK -> PREFIX + "TaskSized";
      case SESSION_PUBLIC_EVENT -> PREFIX + "SessionPublicEvent";
      case INVOCATION_SESSION_READ -> PREFIX + "InvocationSessionRead";
      case INVOCATION_SESSION_SEARCH -> PREFIX + "InvocationSessionSearch";
      case INVOCATION_SESSION_TRACE -> PREFIX + "InvocationSessionTrace";
      case TASK_SESSION_SCOPE -> PREFIX + "TaskSessionScope";
      case TASK_SESSION_SCOPE_TARGET -> PREFIX + "TaskSessionScopeTarget";
      case STEP_SESSION_SCOPE -> PREFIX + "StepSessionScope";
      case INVOCATION_SESSION_SCOPE_QUERY -> PREFIX + "InvocationSessionScopeQuery";
      case INVOCATION_SESSION_LINEAGE_TRACE -> PREFIX + "InvocationSessionLineageTrace";
      case TASK_ATTEMPT -> PREFIX + "TaskAttempt";
      case STEP -> PREFIX + "Step";
      case INVOCATION -> PREFIX + "Invocation";
      case MODEL_DISPATCH -> PREFIX + "ModelDispatch";
      case RESERVATION -> PREFIX + "Reservation";
      case EVENT -> PREFIX + "Event";
      default -> throw JdbcSessionRecords.corrupt();
    };
  }

  /** 只由 measure 构造并同步传给原 MyBatis；没有公开行、长度 hint 或可重放证明。 */
  public static final class Request {
    private final SqlSession session;
    private final SessionQueryContext query;
    private final SessionQueryWork owner;
    private final SessionMetadataAccounting.Table table;
    private final SessionMetadataProjection projection;
    private final String tenant, taskId;
    private final long version;
    private MappedStatement command;
    private boolean started, consumed, closed;

    private Request(
        SqlSession session,
        SessionQueryContext query,
        SessionQueryWork owner,
        SessionMetadataAccounting.Table table,
        SessionMetadataProjection projection,
        String tenant,
        String task) {
      this.session = session;
      this.query = query;
      this.owner = owner;
      this.table = table;
      this.projection = projection;
      this.tenant = tenant;
      this.taskId = task;
      version = owner.metadataReadVersion(query);
    }

    public SessionQueryContext getQuery() {
      return query;
    }

    public String getTenant() {
      return tenant;
    }

    public String getTaskId() {
      return taskId;
    }

    private void check() {
      query.operation(6); // lifecycle, current Work, generation and query health
      if (closed
          || SessionQueryTransaction.current(query) != owner
          || version != owner.metadataReadVersion(query)) throw JdbcSessionRecords.corrupt();
      query.checkHealthy();
    }

    private void prepare() {
      check();
      query.operation(30); // config/command/source/result map/cache flags and dispatch
      var config = session.getConfiguration();
      query.operation(3); // frozen statement selector and command lookup, no name construction
      command = config.getMappedStatement(statement(table));
      if (config.isCacheEnabled()
          || config.getLocalCacheScope() != org.apache.ibatis.session.LocalCacheScope.STATEMENT
          || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
          || command.getResultMaps().size() != 1
          || command.getResultMaps().getFirst().getType() != java.util.Map.class
          || !command.getResultMaps().getFirst().getResultMappings().isEmpty()
          || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource))
        throw JdbcSessionRecords.corrupt();
      var bound = command.getBoundSql(this);
      query.operation(17L + "tenant".length() + "taskId".length());
      if (bound.getParameterMappings().size() != 2
          || !"tenant".equals(bound.getParameterMappings().get(0).getProperty())
          || !"taskId".equals(bound.getParameterMappings().get(1).getProperty())
          || bound.getParameterMappings().get(0).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN
          || bound.getParameterMappings().get(1).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN) throw JdbcSessionRecords.corrupt();
      if (projection.taskSized) {
        query.operation(2L + TASK_SQL.length());
        if (!TASK_SQL.equals(bound.getSql())) throw JdbcSessionRecords.corrupt();
      }
    }

    /** ResultSetHandler 在原已执行 Statement 上调用；不再进入 Map result mapping。 */
    public SessionMetadataAccounting.FinalTable consume(Object handler, Statement statement)
        throws SQLException {
      try {
        check();
        query.operation(
            17); // reflected identities, one-use guard, physical connection/result handoffs
        if (started
            || command == null
            || SystemMetaObject.forObject(handler).getValue("mappedStatement") != command
            || SystemMetaObject.forObject(handler).getValue("parameterHandler.parameterObject")
                != this) throw JdbcSessionRecords.corrupt();
        started = true;
        var physical = SessionQueryTransaction.connection(query);
        if (physical == null
            || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                    statement.getConnection())
                != physical
            || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                    session.getConnection())
                != physical) throw JdbcSessionRecords.corrupt();
        try (ResultSet rows = statement.getResultSet();
            var meter = query.result(command.getId())) {
          if (rows == null) throw JdbcSessionRecords.corrupt();
          query.operation(4);
          var metadata = rows.getMetaData();
          if (metadata.getColumnCount() != projection.columns.size())
            throw JdbcSessionRecords.corrupt();
          long count = 0, bytes = 0, reservations = 0;
          SessionMetadataAccounting.HistoryReservation reservation = null;
          while (true) {
            query.operation(1);
            boolean next = rows.next();
            check();
            if (!next) break;
            if (count == 0) SessionHistorySizedMetadataBatch.verifyConnection(session, query);
            meter.row();
            query.operation(6);
            count = Math.addExact(count, 1);
            if (projection.maximumRows > 0 && count > projection.maximumRows)
              throw JdbcSessionRecords.corrupt();
            long rowBytes = SessionHistoryPolicyV1.ROW_HEADER_BYTES;
            String category = null, state = null;
            long amount = 0;
            Long actual = null;
            int index = 0;
            for (var column : projection.columns) {
              // Provider label/index/getter/conversion 10; presence/kind/table routing and
              // local handoffs 10. Label comparison pays each character separately.
              query.operation(20L + column.name().length());
              String label = metadata.getColumnLabel(++index);
              if (!column.name().equals(label)) throw JdbcSessionRecords.corrupt();
              Object value = rows.getObject(index);
              if (value instanceof java.sql.Timestamp time) value = time.toLocalDateTime();
              validate(column, value);
              long scalar = meter.metadataColumn(label, value);
              // SQL 的数字传输长度与业务 OCTET_LENGTH 含义分别计算；NULL membership 遵循原配置。
              if (value == null && !session.getConfiguration().isCallSettersOnNulls()) continue;
              if (column.kind() == 'T' || column.kind() == 'M' || column.kind() == 'X') {
                if (value != null) scalar = ((Number) value).longValue();
              }
              query.operation(3);
              rowBytes =
                  Math.addExact(
                      rowBytes, Math.addExact(SessionHistoryPolicyV1.FIELD_HEADER_BYTES, scalar));
              if (table == SessionMetadataAccounting.Table.RESERVATION) {
                query.operation(2L + column.name().length());
                switch (column.name()) {
                  case "category" -> category = (String) value;
                  case "amount" -> amount = (Long) value;
                  case "actual" -> actual = (Long) value;
                  case "state" -> state = (String) value;
                  default -> {}
                }
              }
            }
            check(); // no row-derived state is folded if a provider reentered a write/foreign Work
            query.operation(4);
            bytes = Math.addExact(bytes, rowBytes);
            if (table == SessionMetadataAccounting.Table.RESERVATION) {
              query.operation(8L + "SESSION_HISTORY_STORAGE".length());
              if ("SESSION_HISTORY_STORAGE".equals(category)) {
                reservations = Math.addExact(reservations, 1);
                reservation =
                    new SessionMetadataAccounting.HistoryReservation(amount, actual, state);
              }
            }
          }
          query.operation(12); // summary, singleton receiver and close/return handoffs
          consumed = true;
          return new SessionMetadataAccounting.FinalTable(
              count, bytes, 0, null, reservations, reservation);
        }
      } catch (SQLException | RuntimeException | Error failure) {
        poison(owner, failure);
        throw failure;
      }
    }

    private void validate(SessionMetadataProjection.Column column, Object value) {
      query.operation(4);
      if (value == null) {
        if (!column.nullable()) throw JdbcSessionRecords.corrupt();
        return;
      }
      boolean valid =
          switch (column.kind()) {
            case 'S' -> value instanceof String text && bounded(text, column.points());
            case 'I' -> value instanceof Integer;
            case 'L' -> value instanceof Long;
            case 'B' -> value instanceof Boolean;
            case 'D' -> value instanceof java.time.LocalDateTime;
            case 'T', 'M', 'X' -> length(column, value);
            default -> false;
          };
      if (!valid) throw JdbcSessionRecords.corrupt();
    }

    private boolean bounded(String text, int points) {
      query.operation(3);
      int units = text.length();
      if (units <= points) return true;
      if (units > 2 * points) return false;
      query.operation(units);
      return text.codePointCount(0, units) <= points;
    }

    private boolean length(SessionMetadataProjection.Column column, Object value) {
      query.operation(8);
      if (!projection.taskSized || !(value instanceof Long || value instanceof Integer))
        return false;
      long bytes = ((Number) value).longValue();
      long maximum =
          switch (column.kind()) {
            case 'T' -> 65535L;
            case 'M' -> 16777215L;
            default -> 4294967295L;
          };
      return bytes >= 0 && bytes <= maximum;
    }
  }
}
