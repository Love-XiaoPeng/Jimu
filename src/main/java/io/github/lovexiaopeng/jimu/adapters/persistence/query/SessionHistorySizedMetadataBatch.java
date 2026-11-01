package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * V19 history 专用元数据批；只把 binding 表示为受 SQL 来源约束的 UTF8 长度。 保留原 TEXT 保守驻留及独立 raw 校验；错误先污染当前
 * Work，行引用清空后退款。
 */
public final class SessionHistorySizedMetadataBatch
    implements SessionMetadataAccounting.Batch, QueryResults.FinalMetadata {
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "task_id",
          "record_version",
          "history_version",
          "binding_snapshot",
          "binding_digest",
          "content_state",
          "next_seq",
          "body_bytes",
          "metadata_bytes",
          "reserved_bytes",
          "held_bytes",
          "retain_until",
          "cleanup_receipt");
  // Two TEXT Strings: <=65535 UTF16 units each (encoded bytes bound implies this).
  // Four fixed strings; 2 INT + 5 BIGINT + DATETIME; 14 entries incl labels; facades/table/list.
  // Cursor retention is separately additive in SessionQueryContext.Result, never included here.
  static final long RETAINED_BYTES =
      2L * (65535L * 2 + 64) + 4L * (64 + 36 + 64 + 16) + 4L * 64 + 8L * 64 + 14L * 128 + 2048;
  private final SessionQueryContext query;
  private List<Map<String, Object>> rows;
  private boolean closed;
  // 专用元数据投影；长度资格仅由完整具名 SQL、当前事务和连接校验签发。
  // 旧 raw 行、普通数字与外部值不能取得该资格。
  private static final Object BOOTSTRAP = new Object();
  private static final long EXTRA_BYTES = 8192;
  public static final String STATEMENT =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadataTaskSessionHistorySized";
  public static final String SQL =
      "SELECT tenant_id,task_id,record_version,history_version,OCTET_LENGTH(CONVERT(binding_snapshot USING utf8mb4)) AS binding_snapshot,binding_digest,content_state,next_seq,body_bytes,metadata_bytes,reserved_bytes,held_bytes,retain_until,cleanup_receipt FROM task_session_history WHERE tenant_id=? AND task_id=?";

  private record SqlUtf8Length(SessionHistorySizedMetadataBatch owner, long bytes) {}

  private final SessionQueryWork owner;

  private static final class FinalColumn extends SessionQueryContext.MetadataLabel {
    final String label;
    final char type;

    FinalColumn(String label, char type, int points, boolean nullable) {
      super(label, type, points, nullable);
      this.label = label;
      this.type = type;
    }
  }

  private static final List<FinalColumn> FINAL_COLUMNS = finalColumns();

  private static List<FinalColumn> finalColumns() {
    var work = SessionQueryTransaction.currentOwner();
    if (work != null) work.inspect(24 + 2L * COLUMNS.size());
    var columns = new java.util.ArrayList<FinalColumn>();
    for (int i = 0; i < COLUMNS.size(); i++) {
      String label = COLUMNS.get(i);
      if (work != null) work.inspect(20L + label.length());
      char type =
          switch (i) {
            case 0, 1, 5, 6, 13 -> 'S';
            case 2, 3 -> 'I';
            case 12 -> 'D';
            default -> 'L';
          };
      int points =
          switch (i) {
            case 0, 5 -> 64;
            case 1 -> 36;
            case 6 -> 16;
            case 13 -> 65535;
            default -> 0;
          };
      columns.add(new FinalColumn(label, type, points, i == 13));
    }
    return List.copyOf(columns);
  }

  private org.apache.ibatis.mapping.MappedStatement finalCommand;
  private String finalTenant, finalTask;
  private long finalGeneration;
  private boolean finalConsumed;

  private void finalBoundary() {
    ops(query, 2); // version comparison and callback handoff
    if (owner.metadataFinalVersion(query) != finalGeneration)
      throw owner.poisonProof(new IllegalStateException("Write during history FINAL read"));
  }

  @Override
  public void handleResult(org.apache.ibatis.session.ResultContext<?> ignored) {
    throw new IllegalStateException("History FINAL must consume its actual ResultSet");
  }

  @Override
  public void read(
      java.sql.ResultSet source,
      SessionQueryContext context,
      org.apache.ibatis.mapping.MappedStatement command)
      throws java.sql.SQLException {
    try {
      ops(query, 12); // identity, result scope, summary locals and cleanup handoff
      finalBoundary();
      if (context != query || command != finalCommand || finalConsumed) throw corrupt();
      boolean nulls = command.getConfiguration().isCallSettersOnNulls();
      long count = 0, bytes = 0, metadata = 0, metadataSize = 0, body = 0, reserved = 0, held = 0;
      String content = null;
      try (var result = query.result(command.getId())) {
        while (true) {
          finalBoundary();
          boolean next = source.next();
          finalBoundary();
          if (!next) break;
          ops(query, 4);
          if (++count > 1) throw corrupt();
          var columns = source.getMetaData();
          finalBoundary();
          int width = columns.getColumnCount();
          finalBoundary();
          if (width != FINAL_COLUMNS.size()) throw corrupt();
          for (int i = 0; i < FINAL_COLUMNS.size(); i++) {
            var column = FINAL_COLUMNS.get(i);
            ops(query, 10L + column.label.length());
            finalBoundary();
            String label = columns.getColumnLabel(i + 1);
            finalBoundary();
            int type = columns.getColumnType(i + 1);
            finalBoundary();
            boolean valid =
                i == 4
                    ? type == java.sql.Types.INTEGER || type == java.sql.Types.BIGINT
                    : switch (column.type) {
                      case 'S' ->
                          type == java.sql.Types.VARCHAR
                              || type == java.sql.Types.CHAR
                              || type == java.sql.Types.LONGVARCHAR;
                      case 'I' -> type == java.sql.Types.INTEGER;
                      case 'L' -> type == java.sql.Types.BIGINT;
                      default -> type == java.sql.Types.TIMESTAMP;
                    };
            if (!column.label.equals(label) || !valid) throw corrupt();
          }
          result.row();
          bytes = 32;
          for (int i = 0; i < FINAL_COLUMNS.size(); i++) {
            ops(query, 10); // column/getter/normalization, NULL and summary dispatch
            var column = FINAL_COLUMNS.get(i);
            finalBoundary();
            Object value = source.getObject(i + 1);
            finalBoundary();
            if (value instanceof java.sql.Timestamp time) value = time.toLocalDateTime();
            if (i < 2) {
              String expected = i == 0 ? finalTenant : finalTask;
              ops(query, 3L + expected.length());
              if (!expected.equals(value)) throw corrupt();
            }
            long scalar;
            if (i == 4) {
              ops(query, 8);
              if (!(value instanceof Integer || value instanceof Long)) throw corrupt();
              scalar = ((Number) value).longValue();
              if (scalar < 0 || scalar > 65535) throw corrupt();
              result.metadataColumn(column, value);
            } else if (i == 13 && value != null) {
              scalar = finalText(value, 65535, 65535);
              result.metadataColumn(column, value);
            } else {
              scalar = result.metadataColumnChecked(column, value);
            }
            finalBoundary();
            if (value != null || nulls) bytes = Math.addExact(bytes, Math.addExact(8, scalar));
            switch (i) {
              case 6 -> content = (String) value;
              case 8 -> body = (Long) value;
              case 9 -> {
                metadata = (Long) value;
                metadataSize = scalar;
              }
              case 10 -> reserved = (Long) value;
              case 11 -> held = (Long) value;
              default -> {}
            }
          }
        }
        ops(query, 18);
        summary =
            new SessionMetadataAccounting.FinalTable(
                count,
                bytes,
                count,
                count == 0
                    ? null
                    : new SessionMetadataAccounting.HistoryScalars(
                        metadata, metadataSize, body, reserved, held, content),
                0,
                null);
      }
      finalBoundary();
      finalConsumed = true;
    } catch (RuntimeException failure) {
      throw owner.recordMetadataFailure(failure);
    } catch (Error failure) {
      owner.recordMetadataError(failure);
      throw failure;
    } catch (java.sql.SQLException failure) {
      owner.recordMetadataFailure(
          new org.springframework.dao.DataAccessResourceFailureException(
              "History FINAL unavailable", failure));
      throw failure;
    }
  }

  private SessionHistorySizedMetadataBatch(SessionQueryContext query, SessionQueryWork owner) {
    this.query = query;
    this.owner = owner;
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure corrupt() {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
        409, "SESSION_HISTORY_CORRUPT");
  }

  private static void ops(SessionQueryContext q, long n) {
    if (q != null) q.operation(n);
  }

  private RuntimeException fail(RuntimeException failure) {
    close();
    return owner.recordMetadataFailure(failure);
  }

  private void fail(Error failure) {
    close();
    owner.recordMetadataError(failure);
  }

  public static SessionHistorySizedMetadataBatch load(
      org.apache.ibatis.session.SqlSession session,
      SessionQueryContext query,
      String tenant,
      String task) {
    return load(session, query, tenant, task, false);
  }

  private SessionMetadataAccounting.FinalTable summary;

  public static SessionMetadataAccounting.FinalTable measureFinal(
      org.apache.ibatis.session.SqlSession session,
      SessionQueryContext query,
      String tenant,
      String task) {
    try (var batch = load(session, query, tenant, task, true)) {
      batch.open();
      ops(query, 3);
      var result = batch.summary;
      batch.summary = null;
      return result;
    }
  }

  private static SessionHistorySizedMetadataBatch load(
      org.apache.ibatis.session.SqlSession session,
      SessionQueryContext query,
      String tenant,
      String task,
      boolean finalMode) {
    // The lookup itself is new work: pay before consulting the transaction owner.
    ops(query, 10);
    var owner = SessionQueryTransaction.currentOwner();
    SessionHistorySizedMetadataBatch batch = null;
    boolean bootstrap = false;
    try {
      // The captured currentOwner and owner.owns(query) prove the same Settings/query pair.
      // No callback occurs between those identity reads. A second current(query) snapshot
      // cannot add provenance here; remove that provider call and its two call/compare units.
      // Keep all lineage/null/query identity, lock, connection and post-SELECT owner checks.
      if (owner == null || query == null || !query.lineage() || !owner.owns(query)) throw corrupt();
      owner.requireProjectionLocks(tenant, task);
      // Whole original 2-TEXT reservation retained; no speculative TEXT refund.
      // This synchronous factory has no user-supplied loader or reentrant callback.
      query.retainScratch(BOOTSTRAP, RETAINED_BYTES + EXTRA_BYTES + 64);
      bootstrap = true;
      ops(query, 52); // batch fields, FINAL aliases and all failure/close clears prepaid.
      batch = new SessionHistorySizedMetadataBatch(query, owner);
      query.retainScratch(BOOTSTRAP, 64); // only the allocated receiver exists at this point.
      query.retainScratch(batch, RETAINED_BYTES + EXTRA_BYTES);
      query.releaseScratch(BOOTSTRAP);
      bootstrap = false;
      if (finalMode) batch.open();
      batch.read(session, tenant, task, finalMode);
      return batch;
    } catch (RuntimeException failure) {
      if (batch != null) throw batch.fail(failure);
      throw owner == null ? failure : owner.recordMetadataFailure(failure);
    } catch (Error failure) {
      if (batch != null) batch.fail(failure);
      else if (owner != null) owner.recordMetadataError(failure);
      throw failure;
    } finally {
      if (bootstrap) query.releaseScratch(BOOTSTRAP);
    }
  }

  private void connection(org.apache.ibatis.session.SqlSession session) {
    ops(query, 14); // configuration/environment/DS/resource/holder/connection and identity guards.
    var config = session.getConfiguration();
    var ds = config.getEnvironment().getDataSource();
    Object resource =
        org.springframework.transaction.support.TransactionSynchronizationManager.getResource(ds);
    if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder))
      throw corrupt();
    var actual = SessionQueryTransaction.connection(query);
    if (actual == null
        || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                holder.getConnection())
            != actual
        || org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(
                session.getConnection())
            != actual) throw corrupt();
    // Connector 9.7.0 is the actual packaged provider. Check negotiated connection state;
    // never treat a historical server default or a content digest as encoding proof.
    ops(query, 8);
    com.mysql.cj.jdbc.JdbcConnection jdbc;
    try {
      jdbc =
          actual instanceof com.mysql.cj.jdbc.JdbcConnection nativeConnection
              ? nativeConnection
              : actual.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);
    } catch (java.sql.SQLException failure) {
      throw new org.springframework.dao.DataAccessResourceFailureException(
          "Metadata charset unavailable", failure);
    }
    ops(query, 18);
    if (jdbc == null
        || !"UTF-8"
            .equalsIgnoreCase(
                jdbc.getPropertySet()
                    .getStringProperty(com.mysql.cj.conf.PropertyKey.characterEncoding)
                    .getValue())) throw corrupt();
    ops(query, 12 + 20 + 24 + 21 + 3 * 8);
    var server = jdbc.getSession().getServerSession();
    String client = server.getServerVariable("character_set_client");
    String encoding = server.getServerVariable("character_set_connection");
    String results = server.getServerVariable("character_set_results");
    if (!"utf8mb4".equalsIgnoreCase(client)
        || !"utf8mb4".equalsIgnoreCase(encoding)
        || (results != null && !"utf8mb4".equalsIgnoreCase(results))) throw corrupt();
  }

  private void read(
      org.apache.ibatis.session.SqlSession session, String tenant, String task, boolean finalMode) {
    connection(session);
    ops(query, 26); // map/entries/config/statement/cache/command/result-map/static-source guards.
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("tenant", tenant);
    args.put("taskId", task);
    var config = session.getConfiguration();
    var command = config.getMappedStatement(STATEMENT);
    if (config.isCacheEnabled()
        || config.getLocalCacheScope() != org.apache.ibatis.session.LocalCacheScope.STATEMENT
        || command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT
        || command.isUseCache()
        || !command.isFlushCacheRequired()
        || command.getResultMaps().size() != 1
        || command.getResultMaps().getFirst().getType() != Map.class
        || !command.getResultMaps().getFirst().getResultMappings().isEmpty()
        || !(command.getSqlSource() instanceof org.apache.ibatis.scripting.defaults.RawSqlSource))
      throw corrupt();
    ops(query, 3); // actual command's source lookup and private immutable-source identity.
    if (!SessionHistoryMetadataLanguageDriver.owns(command.getSqlSource())) {
      ops(query, 4); // BoundSql production and getter/size before SQL comparison.
      var bound = command.getBoundSql(args);
      ops(query, 2L + SQL.length());
      if (!SQL.equals(bound.getSql()) || bound.getParameterMappings().size() != 2) throw corrupt();
      ops(query, 10 + "tenant".length() + "taskId".length());
      if (!"tenant".equals(bound.getParameterMappings().get(0).getProperty())
          || !"taskId".equals(bound.getParameterMappings().get(1).getProperty())
          || bound.getParameterMappings().get(0).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN
          || bound.getParameterMappings().get(1).getMode()
              != org.apache.ibatis.mapping.ParameterMode.IN) throw corrupt();
    }
    ops(query, 3); // select handoff, row reference assignment, row-count guard.
    if (finalMode) {
      ops(query, 12); // captured command/identities/version and finally field cleanup
      finalCommand = command;
      finalTenant = tenant;
      finalTask = task;
      finalGeneration = owner.metadataFinalVersion(query);
      try {
        session.select(STATEMENT, args, this);
        finalBoundary(); // provider close has completed before a summary can leave this read
        if (!finalConsumed) throw corrupt();
        connection(session);
        return;
      } finally {
        finalCommand = null;
        finalTenant = null;
        finalTask = null;
        finalGeneration = 0;
        finalConsumed = false;
      }
    }
    rows = session.selectList(STATEMENT, args); // normal interceptor/Result metering remains.
    connection(session);
    ops(query, 4); // current owner, query identity, dispatch and row-count comparison.
    if (SessionQueryTransaction.current(query) != owner || rows.size() > 1) throw corrupt();
    for (var row : rows) {
      ops(query, 3L + tenant.length() + task.length());
      if (!tenant.equals(row.get("tenant_id")) || !task.equals(row.get("task_id"))) throw corrupt();
      for (int i = 0; i < COLUMNS.size(); i++) {
        ops(query, 4); // column read/Map lookup/local/index step.
        Object value = row.get(COLUMNS.get(i));
        boolean valid;
        if (i == 4) {
          ops(query, 10); // type alternatives, unbox, two bounds, merge, branch.
          if (!(value instanceof Integer || value instanceof Long)) throw corrupt();
          long length = ((Number) value).longValue();
          if ((length < 0) | (length > 65535)) throw corrupt();
          ops(
              query,
              8 + "binding_snapshot".length()); // wrapper/2 fields/Map hash-put/ownership handoff.
          row.put("binding_snapshot", new SqlUtf8Length(this, length));
          valid = true;
        } else {
          // All original history guards, including NULL/presence and dynamic counters.
          valid =
              switch (i) {
                case 0 -> bounded(value, 64);
                case 1 -> bounded(value, 36);
                case 2, 3 -> value instanceof Integer;
                case 5 -> bounded(value, 64);
                case 6 -> bounded(value, 16);
                case 7, 8, 9, 10, 11 -> value instanceof Long;
                case 12 -> value instanceof java.time.LocalDateTime;
                case 13 -> value == null || value instanceof String text && text.length() <= 65535;
                default -> false;
              };
        }
        if (!valid) throw corrupt();
      }
      // The thirteen required columns above are non-null. Only cleanup_receipt may be absent
      // (MyBatis callSettersOnNulls=false) or explicitly null. One fresh presence read plus
      // exact Map size therefore rejects every extra key without another keySet/hash loop.
      // Full optional-key lookup 8+2L, plus presence/size/selector/local/branch work 7, prepaid.
      ops(query, 15L + 2L * "cleanup_receipt".length());
      int expectedColumns =
          row.containsKey("cleanup_receipt") ? COLUMNS.size() : COLUMNS.size() - 1;
      if (row.size() != expectedColumns) throw corrupt();
    }
  }

  private void open() {
    SessionQueryWork current = null;
    try {
      current = SessionQueryTransaction.metadataOwner(query);
      if (closed || current != owner || !owner.owns(query)) {
        // A closed receiver no longer owns storage. The actual Work's retained rejection is
        // used on that path; no exception/collection is allocated on repeated stale access.
        RuntimeException failure =
            current == null ? owner.metadataClosedFailure() : current.metadataClosedFailure();
        var first = fail(failure);
        throw current == null ? first : current.recordMetadataFailure(first);
      }
      query.checkHealthy();
    } catch (RuntimeException failure) {
      var first = fail(failure);
      throw current == null ? first : current.recordMetadataFailure(first);
    } catch (Error failure) {
      fail(failure);
      throw failure;
    }
  }

  private boolean bounded(Object value, int codePoints) {
    if (!(value instanceof String text) || text.length() > 2 * codePoints) return false;
    // UTF16 units upper-bound code points, even for unpaired surrogates. Prepay getter,
    // primitive store, comparison, branch and return/fallback handoff before reading the length.
    if (query != null) query.operation(5);
    int units = text.length();
    if (units <= codePoints) return true;
    if (query != null) query.operation(units);
    return text.codePointCount(0, units) <= codePoints;
  }

  public Iterator<SessionMetadataAccounting.Row> iterator() {
    open();
    return new Iterator<>() {
      private int index;

      public boolean hasNext() {
        open();
        return index < rows.size();
      }

      public SessionMetadataAccounting.Row next() {
        if (!hasNext()) throw new NoSuchElementException();
        if (query != null) query.operation(1);
        return new Facade(index++);
      }
    };
  }

  private final class Facade implements SessionMetadataAccounting.Row {
    private final int index;

    Facade(int index) {
      this.index = index;
    }

    public Iterable<?> values() {
      open();
      return () ->
          new Iterator<>() {
            private int column;

            public boolean hasNext() {
              open();
              // Preserve MyBatis callSettersOnNulls=false: absent NULL columns were not in
              // values().
              while (column < COLUMNS.size() && !rows.get(index).containsKey(COLUMNS.get(column))) {
                if (query != null) query.operation(1);
                column++;
              }
              return column < COLUMNS.size();
            }

            public Object next() {
              if (!hasNext()) throw new NoSuchElementException();
              // hasNext刚核实际Work/连接/截止；其后仅私有列和已持有Map的读取，无回调。
              // 直接读取避免同一次next再经公开value重复身份查找，独立value入口仍核验。
              if (query != null) query.operation(1);
              return rows.get(index).get(COLUMNS.get(column++));
            }
          };
    }

    public Object value(String name) {
      open();
      if (query != null) query.operation(1);
      return rows.get(index).get(name);
    }
  }

  public long scalarBytes(Object value) {
    try {
      return scalar(value);
    } catch (RuntimeException failure) {
      throw fail(failure);
    } catch (Error failure) {
      fail(failure);
      throw failure;
    }
  }

  private long scalar(Object value) {
    open();
    ops(query, 2); // marker test and dispatch, also on ordinary numeric/string path.
    if (value instanceof SqlUtf8Length length) {
      ops(query, 4); // owner read/compare, bytes getter, return.
      if (length.owner() != this) throw corrupt();
      return length.bytes();
    }
    return scalarValue(value, false);
  }

  private long scalarValue(Object value, boolean finalScan) {
    if (value instanceof String s) {
      long size = 0;
      for (int i = 0; i < s.length(); i++) {
        if (query != null) query.operation(1);
        char c = s.charAt(i);
        if (c < 128) size++;
        else if (c < 2048) size += 2;
        else if (Character.isHighSurrogate(c)) {
          if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) throw corrupt();
          if (query != null) query.operation(1);
          size += 4;
        } else if (Character.isLowSurrogate(c)) throw corrupt();
        else size += 3;
      }
      return size;
    }
    if (value == null) {
      if (query != null) query.operation(1);
      return 1;
    }
    if (value instanceof java.time.LocalDateTime time) {
      ops(query, 1); // extracted typed helper call on the public scalar path.
      return timeBytes(time, finalScan);
    }
    if (!(value instanceof Integer) && !(value instanceof Long)) throw corrupt();
    ops(query, 1); // extracted typed helper call on the public scalar path.
    return numberBytes(((Number) value).longValue());
  }

  private long finalText(Object value, int maximumPoints, int maximumUnits) {
    if (!(value instanceof String text)) throw corrupt();
    ops(query, 3); // one length read, UTF16 bound, and code-point counter initialization.
    int units = text.length(), points = units;
    if (units > maximumUnits) throw corrupt();
    long size = 0;
    for (int i = 0; i < units; i++) {
      ops(query, 1);
      char c = text.charAt(i);
      if (c < 128) size++;
      else if (c < 2048) size += 2;
      else if (Character.isHighSurrogate(c)) {
        if (++i >= units || !Character.isLowSurrogate(text.charAt(i))) throw corrupt();
        ops(query, 1);
        size += 4;
        ops(query, 1); // this same pair consumes one code point; no second traversal.
        points--;
      } else if (Character.isLowSurrogate(c)) throw corrupt();
      else size += 3;
    }
    ops(query, 1); // final code-point bound, including BMP-only strings.
    if (points > maximumPoints) throw corrupt();
    return size;
  }

  private long timeBytes(java.time.LocalDateTime time, boolean finalScan) {
    // The frozen six-digit fraction truncates; years 0000..9999 always use 27 ASCII bytes.
    if (query != null) query.operation(7);
    int year = time.getYear();
    if ((year >= 0) & (year <= 9999)) return 27;
    // Frozen UTC metadata encoding; formatter and its temporary text are covered before
    // allocation.
    Object temporary = new Object();
    if (query != null) query.retainScratch(temporary, 2048);
    try {
      if (query != null) query.operation(27);
      ops(query, 2);
      var formatted =
          java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
              .format(time);
      return finalScan ? scalarValue(formatted, true) : scalarBytes(formatted);
    } finally {
      if (query != null) query.releaseScratch(temporary);
    }
  }

  private long numberBytes(long n) {
    long size = n < 0 ? 1 : 0;
    if (n > 0) n = -n;
    do {
      if (query != null) query.operation(1);
      size++;
      n /= 10;
    } while (n != 0);
    return size;
  }

  public void close() {
    if (closed) return;
    closed = true;
    rows = null;
    summary = null;
    finalCommand = null;
    finalTenant = null;
    finalTask = null;
    finalGeneration = 0;
    finalConsumed = false;
    if (query != null) query.releaseScratch(this);
  }
}
