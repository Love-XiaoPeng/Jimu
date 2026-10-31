package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 仅来源Fact新投影；真实Connector列来源/类型/编码逐项计费，不签发免验raw资格。 */
final class SessionLineageSourceColumns {
  private SessionLineageSourceColumns() {}

  private static final String STEP =
      "io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.selectModelStep";
  private static final String STEP_COLUMNS =
      "tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest,dispatch_id,dispatches,model_version,record_version,request_cleanup_state,retained_request_digest,directory_revision,directory_cwd,directory_binding_digest,session_upper_seq,session_context_digest,session_binding_digest";
  private static final String STEP_SQL =
      "SELECT " + STEP_COLUMNS + " FROM step WHERE tenant_id=? AND id=? AND task_id=?";

  public static void verifyModelStepCommand(
      org.apache.ibatis.mapping.MappedStatement command, SessionQueryContext query, Object args) {
    query.operation(24L + STEP.length() + STEP_SQL.length());
    var bound = command.getBoundSql(args);
    if (!STEP.equals(command.getId())
        || !STEP_SQL.equals(bound.getSql())
        || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource)
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != java.util.Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty()
        || bound.getParameterMappings().size() != 3)
      throw new IllegalStateException("Changed model step command");
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
      SessionQueryWork owner)
      throws SQLException {
    query.operation(18L + statement.length() + STEP_COLUMNS.length());
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
    String[] labels = STEP_COLUMNS.split(","); // caller reserves this bounded projection workspace
    var fields = mysql.getFields();
    if (mysql.getColumnCount() != labels.length)
      throw new IllegalStateException("Model step shape");
    for (int i = 0; i < labels.length; i++) {
      query.operation(32L + 3L * labels[i].length() + 12);
      var expected =
          switch (i) {
            case 4, 11, 13 -> com.mysql.cj.MysqlType.INT;
            case 16, 19 -> com.mysql.cj.MysqlType.BIGINT;
            case 6, 8 -> com.mysql.cj.MysqlType.MEDIUMTEXT;
            case 0, 5, 12, 14, 17 -> com.mysql.cj.MysqlType.VARCHAR;
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
    if (!args.get("tenant").equals(rows.getObject(1))
        || !args.get("stepId").equals(rows.getObject(2))
        || !args.get("taskId").equals(rows.getObject(3)))
      throw new IllegalStateException("Changed model step identity");
  }

  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.";

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
      if (!expected.table().equals(field.getOriginalTableName())
          || !expected.name().equals(field.getOriginalName())
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
