package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.apache.ibatis.cursor.Cursor;

/** 原Mapper的流式metadata投影；facade只借当前行，前移/关闭后失效，不保留旧Map。 */
final class SessionCursorMetadataBatch implements SessionMetadataAccounting.Batch {
  /** 仅metadataTaskSized的具名长度列可构造，不赋予普通Long长度语义。 */
  private record SqlUtf8Length(long bytes) {}

  private final SessionQueryContext query;
  private final SessionMetadataProjection projection;
  private Cursor<Map<String, Object>> cursor;
  private Iterator<Map<String, Object>> source;
  private Map<String, Object> current;
  private Object[] slots;
  private final Object slotsOwner = new Object();
  private final boolean finalMode;
  private final io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork finalOwner;
  private SessionQueryContext.MeterScope scalarScope;
  private FinalColumns measured;
  private boolean closed, iterated, delivered, ended;
  private int count, generation, valueCount;

  SessionCursorMetadataBatch(
      SessionQueryContext query,
      SessionMetadataProjection projection,
      Supplier<Cursor<Map<String, Object>>> load) {
    this(query, projection, load, false, null);
  }

  private SessionCursorMetadataBatch(
      SessionQueryContext query,
      SessionMetadataProjection projection,
      Supplier<Cursor<Map<String, Object>>> load,
      boolean finalMode,
      SessionMetadataAccounting.Table finalTable) {
    this.query = query;
    this.projection = projection;
    this.finalMode = finalMode;
    this.finalOwner =
        finalMode
            ? io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                .currentOwner()
            : null;
    try {
      if (finalMode) checkFinal();
      if (query != null)
        query.retainScratch(
            this,
            projection.retainedBytes
                + (finalMode && finalOwner != null ? 1024L + 24L * projection.columns.size() : 0));
      if (finalMode && finalOwner != null) {
        query.operation(
            28L
                + 5L
                    * projection.columns
                        .size()); // collector/three arrays, zeroing, cleanup, fields and scope
        measured = new FinalColumns(finalTable);
        scalarScope = query.metadataMeter(measured);
      }
      if (query != null && !finalMode)
        query.retainScratch(slotsOwner, 256L + 16L * projection.columns.size());
      cursor = load.get();
      if (finalMode) checkFinal();
      source = cursor.iterator();
      if (finalMode) checkFinal();
    } catch (RuntimeException | Error failure) {
      fail(failure);
      throw failure;
    }
  }

  private void checkFinal() {
    SessionMetadataScalars.operation(query, 12);
    var actual =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (closed
        || actual != finalOwner
        || (actual != null
            && io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                    .current(query)
                != actual))
      throw new IllegalStateException("Foreign or closed final metadata batch");
    if (query != null) query.checkHealthy();
  }

  static SessionMetadataAccounting.FinalTable measureFinal(
      SessionQueryContext query,
      SessionMetadataAccounting.Table table,
      SessionMetadataProjection projection,
      Supplier<Cursor<Map<String, Object>>> load) {
    if (projection
        != (table == SessionMetadataAccounting.Table.TASK
            ? SessionMetadataProjection.taskSized()
            : SessionMetadataProjection.forTable(table)))
      throw new IllegalArgumentException("Different metadata projection");
    try (var batch = new SessionCursorMetadataBatch(query, projection, load, true, table)) {
      return batch.measure(table);
    }
  }

  private SessionMetadataAccounting.FinalTable measure(SessionMetadataAccounting.Table table) {
    try {
      checkFinal();
      SessionMetadataScalars.operation(query, 5);
      long rows = 0, bytes = 0, reservations = 0;
      SessionMetadataAccounting.HistoryReservation reservation = null;
      while (true) {
        SessionMetadataScalars.operation(query, 1);
        boolean next = source.hasNext();
        checkFinal();
        if (!next) break;
        current = source.next();
        checkFinal();
        if (measured != null) measured.consume(current);
        SessionMetadataScalars.operation(query, 5);
        rows = Math.addExact(rows, 1);
        if (projection.maximumRows > 0 && rows > projection.maximumRows)
          throw JdbcSessionRecords.corrupt();
        long rowBytes =
            io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1.ROW_HEADER_BYTES;
        int recognized = 0;
        String category = null, state = null;
        long amount = 0;
        Long actual = null;
        int scalarIndex = 0;
        for (var column : projection.columns) {
          int measuredIndex = scalarIndex++;
          SessionMetadataScalars.operation(
              query, measured == null ? 2 : 4); // lookup/presence and measured index handoff.
          var value = current.get(column.name());
          boolean present;
          if (value == null) {
            SessionMetadataScalars.operation(query, 2L + column.name().length());
            present = current.containsKey(column.name());
          } else present = true;
          SessionMetadataScalars.operation(query, 3);
          if (present) recognized++;
          long scalar;
          if (measured != null) measured.verify(measuredIndex, value);
          if (value == null) {
            if (!column.nullable()) throw JdbcSessionRecords.corrupt();
            if (!present) continue;
            scalar =
                measured == null
                    ? SessionMetadataScalars.bytes(null, query)
                    : measured.bytes(measuredIndex);
          } else if (column.kind() == 'T' || column.kind() == 'M' || column.kind() == 'X') {
            SessionMetadataScalars.operation(query, 8);
            if (!projection.taskSized || !(value instanceof Long || value instanceof Integer))
              throw JdbcSessionRecords.corrupt();
            scalar = ((Number) value).longValue();
            long maximum =
                switch (column.kind()) {
                  case 'T' -> 65535L;
                  case 'M' -> 16777215L;
                  default -> 4294967295L;
                };
            if (scalar < 0 || scalar > maximum) throw JdbcSessionRecords.corrupt();
          } else {
            boolean valid =
                switch (column.kind()) {
                  case 'S' -> value instanceof String s && bounded(s, column.points());
                  case 'I' -> value instanceof Integer;
                  case 'L' -> value instanceof Long;
                  case 'B' -> value instanceof Boolean;
                  case 'D' -> value instanceof java.time.LocalDateTime;
                  default -> false;
                };
            if (!valid) throw JdbcSessionRecords.corrupt();
            scalar =
                measured == null
                    ? SessionMetadataScalars.bytes(value, query)
                    : measured.bytes(measuredIndex);
          }
          SessionMetadataScalars.operation(query, 3); // two exact additions and store.
          rowBytes =
              Math.addExact(
                  rowBytes,
                  Math.addExact(
                      io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1
                          .FIELD_HEADER_BYTES,
                      scalar));
          if (table == SessionMetadataAccounting.Table.RESERVATION) {
            SessionMetadataScalars.operation(query, 2L + column.name().length());
            switch (column.name()) {
              case "category" -> category = (String) value;
              case "amount" -> amount = ((Number) value).longValue();
              case "actual" -> actual = (Long) value;
              case "state" -> state = (String) value;
              default -> {}
            }
          }
        }
        SessionMetadataScalars.operation(query, 4);
        if (recognized != current.size()) throw JdbcSessionRecords.corrupt();
        bytes = Math.addExact(bytes, rowBytes);
        if (table == SessionMetadataAccounting.Table.RESERVATION) {
          SessionMetadataScalars.operation(query, 8L + "SESSION_HISTORY_STORAGE".length());
          if ("SESSION_HISTORY_STORAGE".equals(category)) {
            reservations = Math.addExact(reservations, 1);
            reservation = new SessionMetadataAccounting.HistoryReservation(amount, actual, state);
          }
        }
        current = null;
      }
      checkFinal();
      if (measured != null) measured.completed();
      SessionMetadataScalars.operation(query, 9); // scalar receiver, six fields, return and clear.
      return new SessionMetadataAccounting.FinalTable(
          rows, bytes, 0, null, reservations, reservation);
    } catch (RuntimeException | Error failure) {
      fail(failure);
      throw failure;
    }
  }

  /** 只在本 FINAL 游标内保留一行长度；原 mapper 交接精确行引用，不从 Map 取长度。 */
  private final class FinalColumns
      implements SessionQueryContext.MetadataMeter, SessionQueryContext.MetadataColumns {
    private final SessionMetadataAccounting.Table finalTable;

    FinalColumns(SessionMetadataAccounting.Table table) {
      this.finalTable = table;
    }

    private final long[] sizes = new long[projection.columns.size()];
    private final Object[] values = new Object[projection.columns.size()];
    private final String[] labels = new String[projection.columns.size()];
    private int getters;
    private Object mapped;
    private int columns;
    private boolean begun, active, consumed, invalidated;

    public SessionQueryContext.MetadataColumns begin(String statement) {
      checkFinal();
      String prefix =
          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadata";
      String name = finalTable.name();
      query.operation(12L + prefix.length() + 6L * name.length());
      if (begun || !statement.startsWith(prefix)) throw JdbcSessionRecords.corrupt();
      int at = prefix.length();
      if (projection.taskSized) {
        query.operation(12);
        if (!statement.regionMatches(at, "TaskSized", 0, 9) || statement.length() != at + 9)
          throw JdbcSessionRecords.corrupt();
      } else {
        boolean capital = true;
        for (int i = 0; i < name.length(); i++) {
          char c = name.charAt(i);
          if (c == '_') {
            capital = true;
            continue;
          }
          if (at >= statement.length()
              || statement.charAt(at++) != (capital ? c : Character.toLowerCase(c)))
            throw JdbcSessionRecords.corrupt();
          capital = false;
        }
        if (at != statement.length()) throw JdbcSessionRecords.corrupt();
      }
      begun = true;
      return this;
    }

    public void row() {
      checkFinal();
      query.operation(5);
      if (invalidated || (active && !consumed)) throw JdbcSessionRecords.corrupt();
      query.operation(values.length + 2L);
      java.util.Arrays.fill(values, null);
      mapped = null;
      columns = 0;
      getters = 0;
      active = true;
      consumed = false;
    }

    public void column(String label, Object value, long bytes) {
      query.operation(5L + label.length());
      if (!active
          || columns >= sizes.length
          || !projection.columns.get(columns).name().equals(label))
        throw JdbcSessionRecords.corrupt();
      query.operation(3);
      sizes[columns] = bytes;
      values[columns] = value;
      labels[columns++] = label;
    }

    public Object getter(Object column) {
      query.operation(6);
      if (!active || consumed || getters >= columns) throw JdbcSessionRecords.corrupt();
      boolean match;
      if (column instanceof Integer index) match = index == getters + 1;
      else if (column == labels[getters]) match = true;
      else if (column instanceof String label) {
        query.operation(label.length());
        match = labels[getters].equals(label);
      } else match = false;
      if (!match) throw JdbcSessionRecords.corrupt();
      return values[getters++];
    }

    void verify(int index, Object value) {
      query.operation(4);
      var original = values[index];
      if (value == original) return;
      query.operation(4);
      if (value instanceof Long number
          && original instanceof Long old
          && number.longValue() == old.longValue()) return;
      if (value instanceof Integer number
          && original instanceof Integer old
          && number.intValue() == old.intValue()) return;
      throw JdbcSessionRecords.corrupt();
    }

    public void mapped(Object row) {
      checkFinal();
      query.operation(5);
      if (!active || mapped != null || columns != sizes.length || getters != columns)
        throw JdbcSessionRecords.corrupt();
      mapped = row;
    }

    void consume(Object row) {
      checkFinal();
      query.operation(6);
      if (!active || consumed || mapped != row || columns != sizes.length)
        throw JdbcSessionRecords.corrupt();
      consumed = true;
      mapped = null;
    }

    void completed() {
      query.operation(5);
      if (!begun || !invalidated || (columns != 0 && !consumed)) throw JdbcSessionRecords.corrupt();
    }

    long bytes(int index) {
      query.operation(2);
      if (!active || !consumed) throw JdbcSessionRecords.corrupt();
      return sizes[index];
    }

    public void invalidate() {
      // Repeated close must not repeat prepaid array clearing or leave row/label aliases resident.
      if (invalidated) return;
      java.util.Arrays.fill(values, null);
      java.util.Arrays.fill(labels, null);
      active = false;
      invalidated = true;
      mapped = null;
    }
  }

  private void fail(Throwable failure) {
    poison(failure);
    try {
      close();
    } catch (RuntimeException | Error closeFailure) {
      if (failure != closeFailure) failure.addSuppressed(closeFailure);
    }
  }

  private void poison(Throwable failure) {
    if (finalMode) {
      var actual =
          io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
              .currentOwner();
      if (finalOwner != null) {
        if (failure instanceof Error error) finalOwner.poisonLineageSource(error);
        else finalOwner.poisonProof((RuntimeException) failure);
      }
      if (actual != null && actual != finalOwner) {
        if (failure instanceof Error error) actual.poisonLineageSource(error);
        else actual.poisonProof((RuntimeException) failure);
      }
      return;
    }
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query);
    if (owner != null && failure instanceof Error error) owner.poisonLineageSource(error);
    else if (owner != null)
      owner.poisonProof(
          failure instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException("Metadata cursor failure", failure));
  }

  private RuntimeException closeFailure(RuntimeException failure) {
    poison(failure);
    if (io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query)
        == null) return failure;
    var first = query.recordFailure(failure);
    if (first != failure) first.addSuppressed(failure);
    return first;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Closed metadata batch");
  }

  private boolean advance() {
    open();
    if (delivered) {
      current = null;
      slots = null;
      generation++;
      delivered = false;
    }
    if (current != null) return true;
    if (ended) return false;
    try {
      SessionMetadataScalars.operation(query, 1);
      if (!source.hasNext()) {
        ended = true;
        return false;
      }
      // hasNext materializes only one row; this owner's reservation preceded opening the cursor.
      current = source.next();
      if (projection.maximumRows > 0 && ++count > projection.maximumRows)
        throw JdbcSessionRecords.corrupt();
      validate();
      return true;
    } catch (RuntimeException | Error failure) {
      fail(failure);
      throw failure;
    }
  }

  private void validate() {
    // 原逐列读取已区分缺列/显式NULL；计数准确已知键，再比较Map大小即可拒绝所有多列。
    // 不再另建keySet视图、遍历行键及逐键hash/比较，不缓存值或省略原类型检查。
    SessionMetadataScalars.operation(query, 1); // 局部计数器初始化。
    int recognized = 0;
    // 仅保留紧凑数组；缺失nullable列不进入逻辑前缀，显式NULL仍占位。
    // 分配前预付数组创建、字段写入与清零，原slotsOwner预留不缩减。
    SessionMetadataScalars.operation(query, 2L + projection.columns.size());
    slots = new Object[projection.columns.size()];
    for (var column : projection.columns) {
      SessionMetadataScalars.operation(query, 1);
      var value = current.get(column.name());
      // 保留原3项预付：局部索引、数组值写入与局部presence写入。
      SessionMetadataScalars.operation(query, 3);
      int index = recognized;
      slots[index] = value;
      boolean present;
      if (value == null) {
        SessionMetadataScalars.operation(query, 2L + column.name().length());
        present = current.containsKey(column.name());
      } else present = true;
      SessionMetadataScalars.operation(query, 3); // 保留presence读取、分支及至多一次计数推进。
      if (present) recognized++;
      if (value == null) {
        if (!column.nullable()) throw JdbcSessionRecords.corrupt();
        continue;
      }
      boolean valid =
          switch (column.kind()) {
            case 'S' -> value instanceof String text && bounded(text, column.points());
            case 'I' -> value instanceof Integer;
            case 'L' -> value instanceof Long;
            case 'B' -> value instanceof Boolean;
            case 'D' -> value instanceof java.time.LocalDateTime;
            case 'T', 'M', 'X' -> sizedLength(column, value, index);
            default -> false;
          };
      if (!valid) throw JdbcSessionRecords.corrupt();
    }
    SessionMetadataScalars.operation(query, 5); // 集合大小校验及新增valueCount字段写入均先付费。
    if (recognized != current.size()) throw JdbcSessionRecords.corrupt();
    valueCount = recognized;
  }

  private boolean sizedLength(SessionMetadataProjection.Column column, Object value, int index) {
    if (!projection.taskSized || !(value instanceof Long || value instanceof Integer)) return false;
    long bytes = ((Number) value).longValue();
    long maximum =
        switch (column.kind()) {
          case 'T' -> 65535L;
          case 'M' -> 16777215L;
          case 'X' -> 4294967295L;
          default -> throw new IllegalStateException("Not a SQL length column");
        };
    SessionMetadataScalars.operation(query, 3L + column.name().length());
    if (bytes < 0 || bytes > maximum) return false;
    // 两行workspace及额外4096已在开cursor前保留，包含11个wrapper与Map替换。
    var wrapped = new SqlUtf8Length(bytes);
    current.put(column.name(), wrapped);
    SessionMetadataScalars.operation(query, 1);
    slots[index] = wrapped;
    return true;
  }

  private boolean bounded(String text, int points) {
    if (text.length() > 2 * points) return false;
    SessionMetadataScalars.operation(query, 2); // 新增长度读取及充分条件比较。
    if (text.length() <= points) return true;
    SessionMetadataScalars.operation(query, text.length());
    return text.codePointCount(0, text.length()) <= points;
  }

  public Iterator<SessionMetadataAccounting.Row> iterator() {
    open();
    if (finalMode) throw new IllegalStateException("Final metadata has no row facade");
    if (iterated) throw new IllegalStateException("Metadata cursor already consumed");
    iterated = true;
    return new Iterator<>() {
      public boolean hasNext() {
        return advance();
      }

      public SessionMetadataAccounting.Row next() {
        if (!advance()) throw new NoSuchElementException();
        delivered = true;
        return new Facade(generation);
      }
    };
  }

  private final class Facade implements SessionMetadataAccounting.Row {
    private final int version;

    Facade(int version) {
      this.version = version;
    }

    private void valid() {
      try {
        open();
        if (version != generation || current == null)
          throw new IllegalStateException("Expired metadata row");
      } catch (RuntimeException | Error failure) {
        fail(failure);
        throw failure;
      }
    }

    public Object value(String name) {
      try {
        valid();
        SessionMetadataScalars.operation(query, 1L + name.length());
        return current.get(name);
      } catch (RuntimeException | Error failure) {
        fail(failure);
        throw failure;
      }
    }

    public Iterable<?> values() {
      valid();
      return () ->
          new Iterator<>() {
            private int index;

            public boolean hasNext() {
              try {
                valid();
                SessionMetadataScalars.operation(query, 1); // 只比较紧凑前缀边界，每次仍先valid。
                return index < valueCount;
              } catch (RuntimeException | Error failure) {
                fail(failure);
                throw failure;
              }
            }

            public Object next() {
              try {
                if (!hasNext()) throw new NoSuchElementException();
                SessionMetadataScalars.operation(query, 2); // 索引前移及借用数组读取。
                return slots[index++];
              } catch (RuntimeException | Error failure) {
                fail(failure);
                throw failure;
              }
            }
          };
    }
  }

  public long scalarBytes(Object value) {
    try {
      open();
      if (projection.taskSized && value instanceof SqlUtf8Length length) {
        SessionMetadataScalars.operation(query, 1);
        return length.bytes();
      }
      return SessionMetadataScalars.bytes(value, query);
    } catch (RuntimeException | Error failure) {
      fail(failure);
      throw failure;
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    current = null;
    slots = null;
    source = null;
    if (measured != null) measured.invalidate();
    measured = null;
    if (scalarScope != null) scalarScope.close();
    scalarScope = null;
    var prior = cursor;
    cursor = null;
    try {
      if (prior != null) prior.close();
    } catch (java.io.IOException failure) {
      var error = new IllegalStateException("Metadata cursor close failed", failure);
      throw closeFailure(error);
    } catch (RuntimeException failure) {
      throw closeFailure(failure);
    } catch (Error failure) {
      poison(failure);
      throw failure;
    } finally {
      prior = null;
      if (query != null) {
        query.releaseScratch(this);
        query.releaseScratch(slotsOwner);
      }
    }
  }
}
