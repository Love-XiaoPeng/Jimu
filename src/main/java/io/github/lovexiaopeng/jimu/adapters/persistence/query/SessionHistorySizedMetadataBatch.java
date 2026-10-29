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
public final class SessionHistorySizedMetadataBatch implements SessionMetadataAccounting.Batch {
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
      ops(query, 40); // batch allocation, fields, handoff and failure/close actions prepaid.
      batch = new SessionHistorySizedMetadataBatch(query, owner);
      query.retainScratch(BOOTSTRAP, 64); // only the allocated receiver exists at this point.
      query.retainScratch(batch, RETAINED_BYTES + EXTRA_BYTES);
      query.releaseScratch(BOOTSTRAP);
      bootstrap = false;
      batch.read(session, tenant, task);
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

  private void read(org.apache.ibatis.session.SqlSession session, String tenant, String task) {
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
    ops(query, 3); // select handoff, row reference assignment, row-count guard.
    rows = session.selectList(STATEMENT, args); // normal interceptor/Result metering remains.
    connection(session);
    ops(query, 4); // current owner, query identity, dispatch and row-count comparison.
    if (SessionQueryTransaction.current(query) != owner || rows.size() > 1) throw corrupt();
    for (var row : rows) {
      ops(query, 3L + tenant.length() + task.length());
      if (!tenant.equals(row.get("tenant_id")) || !task.equals(row.get("task_id"))) throw corrupt();
      for (String key : row.keySet()) {
        // Delete repeated List.contains scans. Frozen labels have distinct String hash codes.
        // Pay getter/store, then full uncached hash + full equality + fixed dispatch.
        ops(query, 2);
        int units = key.length();
        ops(query, 8L + 2L * units);
        boolean known =
            switch (key) {
              case "tenant_id",
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
                      "cleanup_receipt" ->
                  true;
              default -> false;
            };
        if (!known) throw corrupt();
      }
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
    }
  }

  private void open() {
    SessionQueryWork current = null;
    try {
      current = SessionQueryTransaction.metadataOwner();
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
              return value(COLUMNS.get(column++));
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
        return scalarBytes(
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                .format(time));
      } finally {
        if (query != null) query.releaseScratch(temporary);
      }
    }
    if (!(value instanceof Integer) && !(value instanceof Long)) throw corrupt();
    long n = ((Number) value).longValue();
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
    if (query != null) query.releaseScratch(this);
  }
}
