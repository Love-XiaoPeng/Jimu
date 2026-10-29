package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;

/** eventHistory单槽长度能力；大小来自同锁SQL，不接受调用方数值或本地合成raw。 */
public final class SessionEventHistoryRows {
  static final String RAW =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.eventHistory";
  static final String SIZE =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.eventHistoryBindingSize";

  private SessionEventHistoryRows() {}

  public static List<Map<String, Object>> load(
      SqlSessionTemplate sql, SessionQueryContext query, String tenant, String task) {
    var owner = SessionQueryTransaction.current(query);
    if (owner == null || !owner.lineage() || query.additionalMeter() || !query.sqlUtf8Lengths())
      return sql.selectList(RAW, Map.of("query", query, "tenant", tenant, "taskId", task));
    Slot slot = null;
    Map<String, Object> args = null;
    try {
      slot = owner.beginEventHistory(tenant, task);
      if (slot == null)
        return sql.selectList(RAW, Map.of("query", query, "tenant", tenant, "taskId", task));
      query.operation(4);
      args = new HashMap<>();
      args.put("query", query);
      args.put("tenant", tenant);
      args.put("taskId", task);
      List<Map<String, Object>> sizes = sql.selectList(SIZE, args);
      query.operation(3);
      if (sizes.isEmpty()) {
        slot.emptySize();
        sizes = null;
        // 原独立缺失语义，仍有真实ResultSet与完整字段计量。
        return sql.selectList(RAW, args);
      }
      if (sizes.size() != 1
          || sizes.getFirst().size() != 1
          || !slot.sizeEnded
          || slot.capability == null)
        throw new IllegalStateException("Unconfirmed event history length result");
      sizes.getFirst().clear();
      sizes = null;
      long bytes = slot.capability.bytes();
      query.operation(2);
      // 两份provider/Map UTF16及raw转交重叠，固定8192包含八列labels/Map/能力/诊断/临时Long。
      query.retainScratch(slot.scratch, Math.addExact(8192, Math.multiplyExact(8, bytes)));
      List<Map<String, Object>> rows = sql.selectList(RAW, args);
      query.operation(4);
      if (rows.size() != 1 || !slot.rawEnded || !slot.consumed)
        throw new IllegalStateException("Changed event history length source");
      slot.row = rows.getFirst();
      slot.finished = true;
      return rows;
    } catch (RuntimeException failure) {
      if (slot != null) slot.invalidate();
      throw owner.poisonProof(failure);
    } finally {
      if (args != null) args.clear();
    }
  }

  /** 仅Work持有；QueryResults绑定实际ResultSet，构造/能力入口均不对生产调用方公开。 */
  static final class Slot {
    private final SessionQueryWork owner;
    private final SessionQueryContext query;
    private final Object token, scratch;
    private String tenant, task;
    private Connection connection;
    private ResultSet sizeResult, rawResult;
    private java.sql.Statement sizeStatement, rawStatement;
    private boolean sizeStarted, finished;
    private int sizeRows, rawRows;
    private boolean invalid, rawStarted, sizeEnded, rawEnded, consumed, fallback;
    private SessionQueryContext.SqlUtf8Length capability;
    private Map<String, Object> row;

    Slot(
        SessionQueryWork owner,
        SessionQueryContext query,
        Object scratch,
        String tenant,
        String task) {
      this.owner = owner;
      this.query = query;
      this.scratch = scratch;
      this.tenant = tenant;
      this.task = task;
      token = owner.token();
      connection = SessionQueryTransaction.connection(query);
      if (connection == null)
        throw owner.poisonProof(new IllegalStateException("Missing event history connection"));
    }

    boolean pending() {
      return !fallback && !finished;
    }

    boolean accepts(String statement) {
      return !fallback && !finished && (SIZE.equals(statement) || RAW.equals(statement));
    }

    void prepared(
        String statement, Object parameter, Connection actual, java.sql.Statement prepared) {
      verify(statement, parameter, actual);
      query.operation(2);
      if (SIZE.equals(statement)) {
        if (sizeStarted) throw new IllegalStateException("Repeated SQL length prepare");
        sizeStarted = true;
        sizeStatement = prepared;
      } else {
        if (!sizeEnded || rawStarted || capability == null)
          throw new IllegalStateException("Unconfirmed raw prepare");
        rawStatement = prepared;
      }
    }

    private void verify(String statement, Object parameter, Connection actual) {
      query.operation(10L + statement.length() + tenant.length() + task.length());
      if (invalid
          || SessionQueryTransaction.current(query) != owner
          || owner.token() != token
          || actual != connection
          || !(parameter instanceof Map<?, ?> map)
          || map.get("query") != query
          || !tenant.equals(map.get("tenant"))
          || !task.equals(map.get("taskId")))
        throw new IllegalStateException("Different event history length owner");
      owner.requireProjectionLocks(tenant, task);
    }

    void bind(
        String statement,
        Object parameter,
        java.sql.Statement prepared,
        ResultSet result,
        SessionQueryContext.Result meter)
        throws SQLException {
      verify(statement, parameter, SessionQueryTransaction.connection(query));
      query.operation(2);
      if (prepared != (SIZE.equals(statement) ? sizeStatement : rawStatement))
        throw new IllegalStateException("Different prepared length statement");
      var metadata = result.getMetaData();
      int count = metadata.getColumnCount();
      if (RAW.equals(statement)) {
        for (int column = 1; column <= count; column++) {
          query.operation(4);
          String expected =
              switch (column) {
                case 1 -> "record_version";
                case 2 -> "history_version";
                case 3 -> "binding_snapshot";
                case 4 -> "binding_digest";
                case 5 -> "content_state";
                case 6 -> "next_seq";
                case 7 -> "retain_until";
                case 8 -> "retained";
                default -> throw new IllegalStateException("Extra event history column");
              };
          query.operation(1L + expected.length());
          if (!expected.equals(metadata.getColumnLabel(column)))
            throw new IllegalStateException("Changed event history labels");
        }
      }
      query.operation(2);
      if (SIZE.equals(statement)) {
        if (sizeResult != null || rawStarted || count != 1)
          throw new IllegalStateException("Repeated event history length source");
        sizeResult = result;

      } else {
        if (rawResult != null || !sizeEnded || capability == null || count != 8)
          throw new IllegalStateException("Missing event history length source");
        rawStarted = true;
        rawResult = result;
      }
    }

    void next(boolean size, boolean present) {
      query.operation(2);
      if (invalid) throw new IllegalStateException("Invalidated event history length");
      if (present) {
        if (size) {
          if (++sizeRows != 1) throw new IllegalStateException("Multiple event history lengths");
        } else if (++rawRows != 1) throw new IllegalStateException("Multiple event histories");
      } else if (size) sizeEnded = true;
      else rawEnded = true;
    }

    void size(SessionQueryContext.Result result) {
      query.operation(1);
      capability = result.takeSqlUtf8Length();
    }

    void column(SessionQueryContext.Result result, String label, Object value) {
      query.operation(1L + label.length());
      if (label.equals("binding_snapshot")) {
        query.operation(2);
        if (consumed || capability == null || invalid)
          throw new IllegalStateException("Missing event history column capability");
        result.columnSqlUtf8(label, value, capability);
        consumed = true;
        capability = null;
      } else result.column(label, value);
    }

    void emptySize() {
      query.operation(2);
      if (!sizeEnded || sizeRows != 0 || capability != null)
        throw new IllegalStateException("Invalid empty SQL size");
      fallback = true;
      sizeResult = null;
      connection = null;
      tenant = null;
      task = null;
      query.releaseScratch(scratch);
    }

    void closed(boolean size) {
      // driver/metadata引用先断；能力只在大小游标正常结束后保留到紧邻raw的一次消费。
      if (size) {
        sizeResult = null;
        sizeStatement = null;
        if (!sizeEnded) invalidate();
      } else {
        rawResult = null;
        rawStatement = null;
        invalidate();
      }
    }

    void invalidate() {
      invalid = true;
      if (capability != null) capability.close();
      capability = null;
    }

    void clear() {
      invalidate();
      sizeResult = null;
      rawResult = null;
      sizeStatement = null;
      rawStatement = null;
      connection = null;
      tenant = null;
      task = null;
      if (row != null) row.clear();
      row = null;
      query.releaseScratch(scratch);
    }
  }
}
