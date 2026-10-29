package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;

/** 十五列history独立读回；每次同锁长度仅替代binding的字节计量，不复用动态行或权限。 */
public final class SessionHistoryRows {
  static final String RAW =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.history";
  static final String SIZE =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.historyBindingSize";

  private SessionHistoryRows() {}

  public static List<Map<String, Object>> load(
      SqlSessionTemplate sql, SessionQueryContext query, String tenant, String task) {
    var owner = SessionQueryTransaction.current(query);
    query.operation(4);
    if (owner == null || !owner.lineage() || query.additionalMeter() || !query.sqlUtf8Lengths())
      return sql.selectList(RAW, Map.of("query", query, "tenant", tenant, "taskId", task));
    Slot slot = null;
    Map<String, Object> args = null;
    List<Map<String, Object>> sizes = null, rows = null;
    boolean returned = false;
    try {
      slot = owner.beginHistory(tenant, task);
      if (slot == null)
        return sql.selectList(RAW, Map.of("query", query, "tenant", tenant, "taskId", task));
      query.operation(6); // args allocation, three puts, holder stores and cleanup
      args = new HashMap<>();
      args.put("query", query);
      args.put("tenant", tenant);
      args.put("taskId", task);
      sizes = sql.selectList(SIZE, args);
      query.operation(5);
      if (sizes.isEmpty()) {
        slot.emptySize();
        sizes.clear();
        sizes = null;
        // 前查仅优化资格；缺失、PURGED及cleanup均执行原raw/receipt完整扫描。
        rows = sql.selectList(RAW, args);
        returned = true;
        return rows;
      }
      if (sizes.size() != 1
          || sizes.getFirst().size() != 1
          || !slot.sizeEnded
          || slot.capability == null)
        throw new IllegalStateException("Unconfirmed history length result");
      sizes.getFirst().clear();
      sizes.clear();
      sizes = null;
      long bytes = slot.capability.bytes();
      query.operation(4); // arithmetic plus scratch replacement before driver raw fetch
      query.retainScratch(slot.scratch, Math.addExact(16384, Math.multiplyExact(8, bytes)));
      rows = sql.selectList(RAW, args);
      query.operation(5);
      if (rows.size() != 1 || !slot.rawEnded || !slot.consumed)
        throw new IllegalStateException("Changed history length source");
      // Map及list从一开始就由slot持有；异常也必须由同一owner清理。
      slot.rows = rows;
      Map<String, Object> row = rows.getFirst();
      query.operation(15); // size, two lookups, equality and PRESENT seven chars, handoff
      if (row.size() != 15
          || !"PRESENT".equals(row.get("content_state"))
          || row.get("cleanup_receipt") != null)
        throw new IllegalStateException("Changed history size eligibility");
      slot.finished = true;
      returned = true;
      return rows;
    } catch (RuntimeException failure) {
      if (slot != null) slot.invalidate();
      throw owner.poisonProof(failure);
    } finally {
      if (sizes != null) {
        for (var row : sizes) row.clear();
        sizes.clear();
      }
      sizes = null;
      if (!returned && rows != null) {
        for (var row : rows) row.clear();
        rows.clear();
      }
      rows = null;
      if (args != null) args.clear();
      args = null;
    }
  }

  /** 四份raw分别连续持有；仅最近一份可在途，写介入使能力失效但不退款旧raw。 */
  static final class Slot {
    Slot previous;
    private SessionQueryWork owner;
    private SessionQueryContext query;
    private Object token;
    private final Object scratch;
    private String tenant, task;
    private Connection connection;
    private Statement sizeStatement, rawStatement;
    private ResultSet sizeResult, rawResult;
    private boolean sizePrepared, rawPrepared, rawStarted, sizeEnded, rawEnded;
    private boolean invalid, fallback, finished, consumed;
    private int sizeRows, rawRows;
    private SessionQueryContext.SqlUtf8Length capability;
    private List<Map<String, Object>> rows;

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
      query.operation(10); // field stores, actual token and connection lookup
      token = owner.token();
      connection = SessionQueryTransaction.connection(query);
      if (connection == null) throw new IllegalStateException("Missing history connection");
    }

    boolean pending() {
      query.operation(2);
      return !fallback && !finished;
    }

    boolean sizeSource() {
      query.operation(1);
      return !rawPrepared;
    }

    void prepared(String statement, Object parameter, Connection actual, Statement prepared) {
      boolean size = !sizeEnded;
      verify(statement, parameter, actual, size);
      query.operation(5);
      if (size) {
        if (sizePrepared || prepared == null)
          throw new IllegalStateException("Repeated history length prepare");
        sizePrepared = true;
        sizeStatement = prepared;
      } else {
        if (rawPrepared || prepared == null || capability == null)
          throw new IllegalStateException("Unconfirmed history raw prepare");
        rawPrepared = true;
        rawStatement = prepared;
      }
    }

    private void verify(String statement, Object parameter, Connection actual, boolean size) {
      query.operation(
          15L + (size ? SIZE.length() : RAW.length()) + tenant.length() + task.length());
      if (invalid
          || !(size ? SIZE : RAW).equals(statement)
          || SessionQueryTransaction.current(query) != owner
          || owner.token() != token
          || connection != actual
          || !(parameter instanceof Map<?, ?> map)
          || map.get("query") != query
          || !tenant.equals(map.get("tenant"))
          || !task.equals(map.get("taskId")))
        throw new IllegalStateException("Different history length source");
      owner.requireProjectionLocks(tenant, task);
    }

    void bind(String statement, Object parameter, Statement prepared, ResultSet result)
        throws SQLException {
      boolean size = sizeSource();
      verify(statement, parameter, SessionQueryTransaction.connection(query), size);
      query.operation(11);
      if (prepared != (size ? sizeStatement : rawStatement)
          || prepared == null
          || (size ? sizeResult != null : rawStarted)
          || prepared.getConnection() != connection
          || prepared.getResultSet() != result)
        throw new IllegalStateException("Different prepared history length statement");
      var metadata = result.getMetaData();
      int count = metadata.getColumnCount();
      if (count != (size ? 1 : 15)) throw new IllegalStateException("Changed history length shape");
      if (!size) {
        for (int column = 1; column <= count; column++) {
          String expected =
              switch (column) {
                case 1 -> "tenant_id";
                case 2 -> "task_id";
                case 3 -> "record_version";
                case 4 -> "history_version";
                case 5 -> "binding_snapshot";
                case 6 -> "binding_digest";
                case 7 -> "content_state";
                case 8 -> "next_seq";
                case 9 -> "body_bytes";
                case 10 -> "metadata_bytes";
                case 11 -> "reserved_bytes";
                case 12 -> "held_bytes";
                case 13 -> "retain_until";
                case 14 -> "cleanup_receipt";
                case 15 -> "retained";
                default -> throw new IllegalStateException("Extra history column");
              };
          query.operation(6L + expected.length());
          if (!expected.equals(metadata.getColumnLabel(column)))
            throw new IllegalStateException("Changed history labels");
        }
        if (!sizeEnded || capability == null)
          throw new IllegalStateException("Missing history length capability");
        rawStarted = true;
        rawResult = result;
      } else sizeResult = result;
    }

    void next(boolean size, boolean present) {
      query.operation(4);
      if (invalid) throw new IllegalStateException("Invalidated history length");
      if (present) {
        if (size ? ++sizeRows != 1 : ++rawRows != 1)
          throw new IllegalStateException("Multiple history length rows");
      } else if (size) sizeEnded = true;
      else rawEnded = true;
    }

    void size(SessionQueryContext.Result meter) {
      query.operation(2);
      capability = meter.takeSqlUtf8Length();
    }

    void column(SessionQueryContext.Result meter, int column, String label, Object value) {
      // bind已核定十五列的准确顺序；provider真实ordinal替代每列重复字符串分派。
      // 第5列仍由columnSqlUtf8重新核label、query和准确raw statement，非binding完整扫描。
      query.operation(4); // ordinal传递/比较、分派与原cap状态；不新增容器或缓存。
      if (column == 5) {
        if (consumed || capability == null || invalid)
          throw new IllegalStateException("Missing history column capability");
        meter.columnSqlUtf8(label, value, capability);
        consumed = true;
        capability = null;
      } else meter.column(label, value);
    }

    void emptySize() {
      query.operation(3);
      if (!sizeEnded || sizeRows != 0 || capability != null)
        throw new IllegalStateException("Invalid empty history size");
      fallback = true;
    }

    void closed(boolean size) {
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
      // Work先预付整个固定链的清理成本；失败清理也不调用会被首错阻断的operation。
      if (previous != null) previous.clear();
      previous = null;
      invalidate();
      sizeResult = null;
      rawResult = null;
      sizeStatement = null;
      rawStatement = null;
      connection = null;
      token = null;
      tenant = null;
      task = null;
      if (rows != null) {
        for (var row : rows) row.clear();
        rows.clear();
      }
      rows = null;
      owner = null;
      var cleanup = query;
      query = null;
      cleanup.releaseScratch(scratch);
    }
  }
}
