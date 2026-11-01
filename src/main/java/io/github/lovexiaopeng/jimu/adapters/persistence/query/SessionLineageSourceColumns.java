package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 仅来源Fact新投影；真实Connector列来源/类型/编码逐项计费，不签发免验raw资格。 */
final class SessionLineageSourceColumns {
  private SessionLineageSourceColumns() {}

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
