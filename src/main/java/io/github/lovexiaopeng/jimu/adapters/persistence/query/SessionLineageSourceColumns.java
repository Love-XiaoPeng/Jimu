package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 仅来源Fact新投影；真实Connector列来源/类型/编码逐项计费，不签发免验raw资格。 */
final class SessionLineageSourceColumns {
  private SessionLineageSourceColumns() {}

  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.";

  private record Column(String table, String name, String label, com.mysql.cj.MysqlType type) {}

  private static final Column[] COLUMNS = {
    new Column("task", "tenant_id", "tenant_id", com.mysql.cj.MysqlType.VARCHAR),
    new Column("task", "id", "id", com.mysql.cj.MysqlType.CHAR),
    new Column("task", "created_at", "created_at", com.mysql.cj.MysqlType.DATETIME),
    new Column("task", "request_snapshot", "request_snapshot", com.mysql.cj.MysqlType.MEDIUMTEXT),
    new Column("task", "request_digest", "request_digest", com.mysql.cj.MysqlType.CHAR),
    new Column("task", "capabilities", "capabilities", com.mysql.cj.MysqlType.TEXT),
    new Column("task", "owner_actor_id", "owner_actor_id", com.mysql.cj.MysqlType.VARCHAR),
    new Column("task", "tools_digest", "tools_digest", com.mysql.cj.MysqlType.CHAR),
    new Column("task", "installation_epoch", "installation_epoch", com.mysql.cj.MysqlType.BIGINT),
    new Column("task", "state", "state", com.mysql.cj.MysqlType.VARCHAR),
    new Column(
        "task_session_scope", "authority_digest", "authority_digest", com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_scope", "authority_bytes", "authority_bytes", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_scope", "content_state", "scope_content", com.mysql.cj.MysqlType.VARCHAR),
    new Column(
        "task_session_scope", "record_version", "record_version", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_scope", "authority_version", "authority_version", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_scope", "owner_actor_id", "scope_owner", com.mysql.cj.MysqlType.VARCHAR),
    new Column(
        "task_session_scope",
        "caller_tools_digest",
        "caller_tools_digest",
        com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_scope",
        "caller_binding_digest",
        "caller_binding_digest",
        com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_scope", "candidate_count", "candidate_count", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_scope", "created_at", "scope_created", com.mysql.cj.MysqlType.DATETIME),
    new Column(
        "task_session_scope", "retain_until", "scope_retain", com.mysql.cj.MysqlType.DATETIME),
    new Column(
        "task_session_history", "binding_digest", "binding_digest", com.mysql.cj.MysqlType.CHAR),
    new Column(
        "task_session_history", "history_version", "history_version", com.mysql.cj.MysqlType.INT),
    new Column(
        "task_session_history", "retain_until", "history_retain", com.mysql.cj.MysqlType.DATETIME)
  };

  static void verify(
      ResultSet rows, SessionQueryContext query, String statement, SessionQueryWork owner)
      throws SQLException {
    query.operation(8L + statement.length());
    boolean size = (PREFIX + "sourceFactSizes").equals(statement);
    if (!size && !(PREFIX + "sourceFact").equals(statement))
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
    if (mysql.getColumnCount() != (size ? 1 : COLUMNS.length))
      throw new IllegalStateException("Source projection shape");
    if (size) {
      query.operation(24);
      int type = mysql.getColumnType(1);
      if (!"request_bytes".equals(mysql.getColumnLabel(1))
          || (type != java.sql.Types.BIGINT && type != java.sql.Types.INTEGER))
        throw new IllegalStateException("Source length projection");
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
