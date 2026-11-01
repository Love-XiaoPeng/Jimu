package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 仅来源Fact新投影；真实Connector列来源/类型/编码逐项计费，不签发免验raw资格。 */
final class SessionLineageSourceColumns {
  private SessionLineageSourceColumns() {}

  static final String MODEL_TASK =
      "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.selectModelAdmissionTaskForUpdate";
  static final String EXTENDED_MODEL_TASK =
      "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.selectExtendedModelAdmissionTaskForUpdate";
  static final String EXTENDED_MODEL_TASK_SQL =
      "SELECT tenant_id,id,owner_actor_id,request_digest,goal,capabilities,installation_epoch,record_version,state,revision,created_at,cancel_requested_at,current_attempt_id,worker_id,fence,max_models,max_tools,models_used,tools_used,token_held,max_tokens,model_snapshot,model_digest,evaluation_run_id,cost_held,max_cost_micros,tools_snapshot,tools_digest,report_time,evaluation_snapshot,evaluation_digest,evaluation_phase,evaluation_sample,evaluation_model,material_snapshot,material_digest,material_system_digest,material_file_snapshot,material_file_digest,execution_scope_digest,business_policy_snapshot,business_policy_digest,media_snapshot,media_digest,media_cleanup_state,mcp_snapshot,mcp_digest,question_binding,question_binding_digest,waiting_question_id,question_continuation_step_id,question_cleanup_state,question_retained_digests,lease_until>CURRENT_TIMESTAMP(6) AS live,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND id=? FOR UPDATE";

  static final String MODEL_TASK_SQL =
      "SELECT tenant_id,id,owner_actor_id,request_digest,goal,capabilities,installation_epoch,record_version,state,revision,created_at,cancel_requested_at,current_attempt_id,worker_id,fence,max_models,max_tools,models_used,tools_used,token_held,max_tokens,model_snapshot,model_digest,evaluation_run_id,cost_held,max_cost_micros,tools_snapshot,tools_digest,report_time,lease_until>CURRENT_TIMESTAMP(6) AS live,deadline>CURRENT_TIMESTAMP(6) AS within_deadline,(evaluation_snapshot IS NOT NULL OR evaluation_digest IS NOT NULL OR evaluation_phase IS NOT NULL OR evaluation_sample IS NOT NULL OR evaluation_model IS NOT NULL OR material_snapshot IS NOT NULL OR material_digest IS NOT NULL OR material_system_digest IS NOT NULL OR material_file_snapshot IS NOT NULL OR material_file_digest IS NOT NULL OR execution_scope_digest IS NOT NULL OR business_policy_snapshot IS NOT NULL OR business_policy_digest IS NOT NULL OR media_snapshot IS NOT NULL OR media_digest IS NOT NULL OR media_cleanup_state IS NOT NULL OR mcp_snapshot IS NOT NULL OR mcp_digest IS NOT NULL OR question_binding IS NOT NULL OR question_binding_digest IS NOT NULL OR waiting_question_id IS NOT NULL OR question_continuation_step_id IS NOT NULL OR question_cleanup_state IS NOT NULL OR question_retained_digests IS NOT NULL) AS model_admission_extended FROM task WHERE tenant_id=? AND id=? FOR UPDATE";

  static boolean modelTask(String statement) {
    return MODEL_TASK.equals(statement) || EXTENDED_MODEL_TASK.equals(statement);
  }

  static int modelTaskWidth(String statement) {
    return MODEL_TASK.equals(statement) ? 32 : 55;
  }

  static String modelTaskLabel(int ordinal, int width) {
    if (width == 32 && ordinal == 31) return "model_admission_extended";
    return modelTaskLabel(width == 32 && ordinal >= 29 ? ordinal + 24 : ordinal);
  }

  // Fixed source shape uses switch constants; no per-class Column array or descriptor allocation.
  static String modelTaskLabel(int ordinal) {
    return switch (ordinal) {
      case 0 -> "tenant_id";
      case 1 -> "id";
      case 2 -> "owner_actor_id";
      case 3 -> "request_digest";
      case 4 -> "goal";
      case 5 -> "capabilities";
      case 6 -> "installation_epoch";
      case 7 -> "record_version";
      case 8 -> "state";
      case 9 -> "revision";
      case 10 -> "created_at";
      case 11 -> "cancel_requested_at";
      case 12 -> "current_attempt_id";
      case 13 -> "worker_id";
      case 14 -> "fence";
      case 15 -> "max_models";
      case 16 -> "max_tools";
      case 17 -> "models_used";
      case 18 -> "tools_used";
      case 19 -> "token_held";
      case 20 -> "max_tokens";
      case 21 -> "model_snapshot";
      case 22 -> "model_digest";
      case 23 -> "evaluation_run_id";
      case 24 -> "cost_held";
      case 25 -> "max_cost_micros";
      case 26 -> "tools_snapshot";
      case 27 -> "tools_digest";
      case 28 -> "report_time";
      case 29 -> "evaluation_snapshot";
      case 30 -> "evaluation_digest";
      case 31 -> "evaluation_phase";
      case 32 -> "evaluation_sample";
      case 33 -> "evaluation_model";
      case 34 -> "material_snapshot";
      case 35 -> "material_digest";
      case 36 -> "material_system_digest";
      case 37 -> "material_file_snapshot";
      case 38 -> "material_file_digest";
      case 39 -> "execution_scope_digest";
      case 40 -> "business_policy_snapshot";
      case 41 -> "business_policy_digest";
      case 42 -> "media_snapshot";
      case 43 -> "media_digest";
      case 44 -> "media_cleanup_state";
      case 45 -> "mcp_snapshot";
      case 46 -> "mcp_digest";
      case 47 -> "question_binding";
      case 48 -> "question_binding_digest";
      case 49 -> "waiting_question_id";
      case 50 -> "question_continuation_step_id";
      case 51 -> "question_cleanup_state";
      case 52 -> "question_retained_digests";
      case 53 -> "live";
      case 54 -> "within_deadline";
      default -> throw new IllegalArgumentException("Model task column");
    };
  }

  private static com.mysql.cj.MysqlType modelTaskType(int ordinal) {
    return switch (ordinal) {
      case 0, 2, 8, 23, 32, 33, 44, 51 -> com.mysql.cj.MysqlType.VARCHAR;
      case 1, 3, 12, 13, 22, 27, 30, 31, 35, 36, 38, 39, 41, 43, 46, 48, 49, 50 ->
          com.mysql.cj.MysqlType.CHAR;
      case 4, 5, 47, 52 -> com.mysql.cj.MysqlType.TEXT;
      case 6, 9, 14, 19, 20, 24, 25, 53, 54 -> com.mysql.cj.MysqlType.BIGINT;
      case 7, 15, 16, 17, 18 -> com.mysql.cj.MysqlType.INT;
      case 10, 11, 28 -> com.mysql.cj.MysqlType.DATETIME;
      case 21, 26, 29, 42, 45 -> com.mysql.cj.MysqlType.MEDIUMTEXT;
      case 34, 37, 40 -> com.mysql.cj.MysqlType.LONGTEXT;
      default -> throw new IllegalArgumentException("Model task column");
    };
  }

  static void verifyModelTaskCommand(
      org.apache.ibatis.mapping.MappedStatement command,
      SessionQueryContext query,
      Object parameter) {
    String statement = command.getId();
    query.operation(28L + statement.length());
    boolean compact = MODEL_TASK.equals(statement);
    String sql = compact ? MODEL_TASK_SQL : EXTENDED_MODEL_TASK_SQL;
    if (!modelTask(statement)
        || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource)
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != java.util.Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty()
        || !(parameter instanceof java.util.Map<?, ?> args)
        || args.get("query") != query)
      throw new IllegalStateException("Changed model task command");
    if (!SessionHistoryMetadataLanguageDriver.ownsModelTask(command.getSqlSource(), compact)) {
      query.operation(4L + sql.length());
      var bound = command.getBoundSql(parameter);
      if (!sql.equals(bound.getSql())
          || bound.getParameterMappings().size() != 2
          || !"tenant".equals(bound.getParameterMappings().get(0).getProperty())
          || !"taskId".equals(bound.getParameterMappings().get(1).getProperty())
          || bound.getParameterMappings().get(0).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN
          || bound.getParameterMappings().get(1).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN)
        throw new IllegalStateException("Changed model task SQL or parameters");
    }
    SessionQueryTransaction.current(query)
        .requireProjectionLocks((String) args.get("tenant"), (String) args.get("taskId"));
    if (compact) query.operation(47); // Prepay all 24 SQL NULL predicates and 23 ORs before JDBC.
  }

  static void verifyModelTask(
      ResultSet rows,
      SessionQueryContext query,
      int width,
      Object parameter,
      SessionQueryWork owner,
      long version)
      throws SQLException {
    query.operation(17);
    var args = (java.util.Map<?, ?>) parameter;
    owner.requireProjectionLocks((String) args.get("tenant"), (String) args.get("taskId"));
    checkModelStep(query, owner, version);
    var statement = rows.getStatement();
    checkModelStep(query, owner, version);
    checkModelStep(query, owner, version);
    var connection = statement.getConnection();
    checkModelStep(query, owner, version);
    if (connection != SessionQueryTransaction.connection(query))
      throw new IllegalStateException("Foreign model task connection");
    checkModelStep(query, owner, version);
    var metadata = rows.getMetaData();
    checkModelStep(query, owner, version);
    if (!(metadata instanceof com.mysql.cj.jdbc.result.ResultSetMetaData mysql))
      throw new IllegalStateException("Model task source metadata unavailable");
    checkModelStep(query, owner, version);
    var fields = mysql.getFields();
    checkModelStep(query, owner, version);
    if (fields.length != width) throw new IllegalStateException("Changed model task width");
    // Exact Connector accessors below read the current native fields. Keep the live charset
    // mapping (including custom collations); never substitute the static collation table.
    // Wrappers/subclasses retain the individually guarded metadata callbacks.
    query.operation(16); // exact-class routes, native field reads and local reference handoffs
    com.mysql.cj.NativeCharsetSettings charsetSettings = null;
    if (rows.getClass() == com.mysql.cj.jdbc.result.ResultSetImpl.class
        && mysql.getClass() == com.mysql.cj.jdbc.result.ResultSetMetaData.class) {
      var session = ((com.mysql.cj.jdbc.result.ResultSetImpl) rows).getSession();
      if (session != null && session.getClass() == com.mysql.cj.NativeSession.class) {
        var server = session.getServerSession();
        if (server != null
            && server.getClass() == com.mysql.cj.protocol.a.NativeServerSession.class) {
          var settings = server.getCharsetSettings();
          if (settings != null && settings.getClass() == com.mysql.cj.NativeCharsetSettings.class)
            charsetSettings = (com.mysql.cj.NativeCharsetSettings) settings;
        }
      }
    }
    for (int i = 0; i < width; i++) {
      String label = modelTaskLabel(i, width);
      int original = width == 32 && i >= 29 ? i + 24 : i;
      var type = original >= 53 ? com.mysql.cj.MysqlType.BIGINT : modelTaskType(original);
      String table = original < 53 ? "task" : null;
      String name = original < 53 ? label : null;
      query.operation(
          (width == 32 ? 40L : 34L)
              + (table == null ? 0 : table.length())
              + (name == null ? 0 : name.length())
              + label.length());
      var field = fields[i];
      if (field == null
          || field.getClass() != com.mysql.cj.result.Field.class
          || !java.util.Objects.equals(table, field.getOriginalTableName())
          || !java.util.Objects.equals(name, field.getOriginalName())
          || !label.equals(field.getName())
          || field.getMysqlType() != type)
        throw new IllegalStateException("Changed model task source column " + label + ": " + field);
      if (type == com.mysql.cj.MysqlType.CHAR
          || type == com.mysql.cj.MysqlType.VARCHAR
          || type == com.mysql.cj.MysqlType.TEXT
          || type == com.mysql.cj.MysqlType.MEDIUMTEXT
          || type == com.mysql.cj.MysqlType.LONGTEXT) {
        String charset;
        query.operation(1);
        if (charsetSettings != null) {
          query.operation(6); // current collation, boxing, live map/static lookup and handoff
          charset = charsetSettings.getMysqlCharsetNameForCollationIndex(field.getCollationIndex());
        } else {
          checkModelStep(query, owner, version);
          charset = mysql.getColumnCharacterSet(i + 1);
          checkModelStep(query, owner, version);
        }
        query.operation(3); // exact native metadata's encoding accessor only delegates to Field
        String encoding;
        if (mysql.getClass() == com.mysql.cj.jdbc.result.ResultSetMetaData.class)
          encoding = field.getEncoding();
        else {
          checkModelStep(query, owner, version);
          encoding = mysql.getColumnCharacterEncoding(i + 1);
          checkModelStep(query, owner, version);
        }
        if (!"utf8mb4".equalsIgnoreCase(charset) || !"UTF-8".equalsIgnoreCase(encoding))
          throw new IllegalStateException("Changed model task charset");
      }
    }
    for (int i = 1; i <= 2; i++) {
      checkModelStep(query, owner, version);
      Object value = rows.getObject(i);
      checkModelStep(query, owner, version);
      String key = i == 1 ? "tenant" : "taskId";
      query.operation(5L + ((String) args.get(key)).length());
      if (!args.get(key).equals(value))
        throw new IllegalStateException("Changed model task identity");
    }
  }

  private static final String STEP =
      "io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.selectLineageModelAdmission";
  private static final String STEP_COLUMNS =
      "tenant_id,id,task_id,ordinal,request_snapshot,request_digest,response_snapshot,dispatch_id,dispatches,record_version,session_upper_seq,session_context_digest,session_binding_digest";
  static final String STEP_SQL =
      "SELECT " + STEP_COLUMNS + " FROM step WHERE tenant_id=? AND id=? AND task_id=?";

  public static void verifyModelStepCommand(
      org.apache.ibatis.mapping.MappedStatement command, SessionQueryContext query, Object args) {
    query.operation(24L + STEP.length());
    if (!STEP.equals(command.getId())
        || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource)
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != java.util.Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty())
      throw new IllegalStateException("Changed model step command");
    if (SessionHistoryMetadataLanguageDriver.ownsModelStep(command.getSqlSource())) return;
    query.operation(4L + STEP_SQL.length());
    var bound = command.getBoundSql(args);
    if (!STEP_SQL.equals(bound.getSql()) || bound.getParameterMappings().size() != 3)
      throw new IllegalStateException("Changed model step SQL");
    String[] names = {"tenant", "stepId", "taskId"};
    for (int i = 0; i < names.length; i++) {
      query.operation(8L + names[i].length());
      var mapping = bound.getParameterMappings().get(i);
      if (!names[i].equals(mapping.getProperty())
          || mapping.getMode() != org.apache.ibatis.mapping.ParameterMode.IN)
        throw new IllegalStateException("Changed model step parameters");
    }
  }

  static void verifyModelStep(
      ResultSet rows,
      SessionQueryContext query,
      String statement,
      Object parameter,
      SessionQueryWork owner,
      long generation)
      throws SQLException {
    query.operation(18L + statement.length());
    if (!STEP.equals(statement)
        || owner == null
        || SessionQueryTransaction.current(query) != owner
        || !(parameter instanceof java.util.Map<?, ?> args))
      throw new IllegalStateException("Foreign model step result");
    owner.requireProjectionLocks((String) args.get("tenant"), (String) args.get("taskId"));
    if (rows.getStatement().getConnection() != SessionQueryTransaction.connection(query))
      throw new IllegalStateException("Foreign model step connection");
    if (!(rows.getMetaData() instanceof com.mysql.cj.jdbc.result.ResultSetMetaData mysql))
      throw new IllegalStateException("Model step metadata unavailable");
    query.operation(15); // fixed array allocation, thirteen stores and reference handoff
    String[] labels = {
      "tenant_id",
      "id",
      "task_id",
      "ordinal",
      "request_snapshot",
      "request_digest",
      "response_snapshot",
      "dispatch_id",
      "dispatches",
      "record_version",
      "session_upper_seq",
      "session_context_digest",
      "session_binding_digest"
    };
    var fields = mysql.getFields();
    if (mysql.getColumnCount() != labels.length)
      throw new IllegalStateException("Model step shape");
    for (int i = 0; i < labels.length; i++) {
      query.operation(32L + 3L * labels[i].length() + 12);
      var expected =
          switch (i) {
            case 3, 8, 9 -> com.mysql.cj.MysqlType.INT;
            case 10 -> com.mysql.cj.MysqlType.BIGINT;
            case 4, 6 -> com.mysql.cj.MysqlType.MEDIUMTEXT;
            case 0 -> com.mysql.cj.MysqlType.VARCHAR;
            default -> com.mysql.cj.MysqlType.CHAR;
          };
      var field = fields[i];
      if (!"step".equals(field.getOriginalTableName())
          || !labels[i].equals(field.getOriginalName())
          || !labels[i].equals(mysql.getColumnLabel(i + 1))
          || field.getMysqlType() != expected)
        throw new IllegalStateException("Changed model step projection");
      if (expected != com.mysql.cj.MysqlType.INT
          && expected != com.mysql.cj.MysqlType.BIGINT
          && (!"utf8mb4".equalsIgnoreCase(mysql.getColumnCharacterSet(i + 1))
              || !"UTF-8".equalsIgnoreCase(mysql.getColumnCharacterEncoding(i + 1))))
        throw new IllegalStateException("Changed model step charset");
    }
    query.operation(
        12L
            + ((String) args.get("tenant")).length()
            + ((String) args.get("taskId")).length()
            + ((String) args.get("stepId")).length());
    for (int i = 1; i <= 3; i++) {
      checkModelStep(query, owner, generation);
      Object identity = rows.getObject(i);
      checkModelStep(query, owner, generation);
      query.operation(3); // identity dispatch/parameter lookup and loop control
      if (!args.get(i == 1 ? "tenant" : i == 2 ? "stepId" : "taskId").equals(identity))
        throw new IllegalStateException("Changed model step identity");
    }
  }

  private static void checkModelStep(
      SessionQueryContext query, SessionQueryWork owner, long generation) {
    query.operation(2); // callback boundary and generation comparison
    if (owner.metadataFinalVersion(query) != generation)
      throw owner.poisonProof(new IllegalStateException("Write during model step identity read"));
  }

  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.";

  static final String NODE_SQL =
      "SELECT t.tenant_id,t.id,t.request_digest,t.request_snapshot,\n      l.tenant_id AS lineage_tenant,l.task_id AS lineage_task,l.record_version,\n      l.genesis_snapshot,l.genesis_digest,l.genesis_bytes,\n      l.graph_snapshot,l.graph_digest,l.graph_bytes,l.content_state\n    FROM task t\n    JOIN task_session_lineage l ON l.tenant_id=t.tenant_id AND l.task_id=t.id\n    WHERE t.tenant_id=? AND t.id=?";

  static void verifyNodeInputCommand(
      org.apache.ibatis.mapping.MappedStatement command, SessionQueryContext query, Object args) {
    query.operation(18L + PREFIX.length());
    if (!(PREFIX + "nodeInput").equals(command.getId())
        || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource)
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != java.util.Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty())
      throw new IllegalStateException("Changed node input command");
    query.operation(3);
    if (SessionHistoryMetadataLanguageDriver.ownsNode(command.getSqlSource())) return;
    query.operation(6L + NODE_SQL.length());
    var bound = command.getBoundSql(args);
    if (!NODE_SQL.equals(bound.getSql()) || bound.getParameterMappings().size() != 2)
      throw new IllegalStateException("Changed node input SQL");
    String[] names = {"tenant", "id"};
    for (int i = 0; i < names.length; i++) {
      query.operation(8L + names[i].length());
      var mapping = bound.getParameterMappings().get(i);
      if (!names[i].equals(mapping.getProperty())
          || mapping.getMode() != org.apache.ibatis.mapping.ParameterMode.IN)
        throw new IllegalStateException("Changed node input parameters");
    }
  }

  static void verifyNodeInput(
      ResultSet rows,
      SessionQueryContext query,
      String statement,
      Object parameter,
      SessionQueryWork owner,
      long generation)
      throws SQLException {
    query.operation(12);
    if (!(PREFIX + "nodeInput").equals(statement)
        || owner == null
        || !owner.lineageSourceActive()
        || !(parameter instanceof java.util.Map<?, ?> args))
      throw new IllegalStateException("Foreign node input result");
    owner.requireProjectionLocks((String) args.get("tenant"), (String) args.get("id"));
    verify(rows, query, statement, owner);
    for (int column : new int[] {1, 2, 5, 6}) {
      checkModelStep(query, owner, generation);
      Object identity = rows.getObject(column);
      checkModelStep(query, owner, generation);
      String key = column == 1 || column == 5 ? "tenant" : "id";
      query.operation(5L + ((String) args.get(key)).length());
      if (!args.get(key).equals(identity)) throw new IllegalStateException("Changed node identity");
    }
  }

  private record Column(String table, String name, String label, com.mysql.cj.MysqlType type) {}

  private static final Column[] COLUMNS = {
    new Column("task", "tenant_id", "tenant_id", com.mysql.cj.MysqlType.VARCHAR),
    new Column("task", "id", "id", com.mysql.cj.MysqlType.CHAR),
    new Column("task", "request_digest", "request_digest", com.mysql.cj.MysqlType.CHAR),
    new Column("task", "request_snapshot", "request_snapshot", com.mysql.cj.MysqlType.MEDIUMTEXT),
    new Column(
        "task_session_lineage", "tenant_id", "lineage_tenant", com.mysql.cj.MysqlType.VARCHAR),
    new Column("task_session_lineage", "task_id", "lineage_task", com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_lineage", "record_version", "record_version", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_lineage",
        "genesis_snapshot",
        "genesis_snapshot",
        com.mysql.cj.MysqlType.TEXT),
    new Column(
        "task_session_lineage", "genesis_digest", "genesis_digest", com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_lineage", "genesis_bytes", "genesis_bytes", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_lineage", "graph_snapshot", "graph_snapshot", com.mysql.cj.MysqlType.TEXT),
    new Column("task_session_lineage", "graph_digest", "graph_digest", com.mysql.cj.MysqlType.CHAR),
    new Column("task_session_lineage", "graph_bytes", "graph_bytes", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_lineage", "content_state", "content_state", com.mysql.cj.MysqlType.VARCHAR)
  };

  static void verify(
      ResultSet rows, SessionQueryContext query, String statement, SessionQueryWork owner)
      throws SQLException {
    query.operation(8L + statement.length());
    boolean size = (PREFIX + "nodeInputSizes").equals(statement);
    if (!size && !(PREFIX + "nodeInput").equals(statement))
      throw new IllegalStateException("Unexpected SQL during source borrow");
    if (SessionQueryTransaction.metadataOwner(query) != owner)
      throw new IllegalStateException("Foreign source projection");
    query.checkHealthy();
    if (rows.getStatement().getConnection() != SessionQueryTransaction.connection(query))
      throw new IllegalStateException("Foreign source projection connection");
    var metadata = rows.getMetaData();
    if (!(metadata instanceof com.mysql.cj.jdbc.result.ResultSetMetaData mysql))
      throw new IllegalStateException("Source projection metadata unavailable");
    query.operation(5);
    if (mysql.getColumnCount() != (size ? 3 : COLUMNS.length))
      throw new IllegalStateException("Source projection shape");
    if (size) {
      String[] labels = {"request_bytes", "genesis_bytes", "graph_bytes"};
      for (int i = 0; i < labels.length; i++) {
        query.operation(24L + labels[i].length());
        int type = mysql.getColumnType(i + 1);
        if (!labels[i].equals(mysql.getColumnLabel(i + 1))
            || (type != java.sql.Types.BIGINT && type != java.sql.Types.INTEGER))
          throw new IllegalStateException("Source length projection");
      }
      return;
    }
    var fields = mysql.getFields();
    for (int i = 0; i < COLUMNS.length; i++) {
      var expected = COLUMNS[i];
      query.operation(
          18L
              + expected.table().length()
              + expected.name().length()
              + expected.label().length()
              + 12);
      var field = fields[i];
      if (!java.util.Objects.equals(expected.table(), field.getOriginalTableName())
          || !java.util.Objects.equals(expected.name(), field.getOriginalName())
          || !expected.label().equals(mysql.getColumnLabel(i + 1))
          || expected.type() != field.getMysqlType())
        throw new IllegalStateException("Changed source projection column");
      if (expected.type() == com.mysql.cj.MysqlType.CHAR
          || expected.type() == com.mysql.cj.MysqlType.VARCHAR
          || expected.type() == com.mysql.cj.MysqlType.TEXT
          || expected.type() == com.mysql.cj.MysqlType.MEDIUMTEXT) {
        if (!"utf8mb4".equalsIgnoreCase(mysql.getColumnCharacterSet(i + 1))
            || !"UTF-8".equalsIgnoreCase(mysql.getColumnCharacterEncoding(i + 1)))
          throw new IllegalStateException("Changed source projection charset");
      }
    }
  }
}
