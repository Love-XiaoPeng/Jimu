package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * V19 history metadata only, at most one row. MySQL TEXT is at most 65535 encoded bytes. Neither
 * TEXT column is BODY; its schema bound is independent of stored byte counters. Mapper returns an
 * exclusively owned batch; facades never retain its backing List/Map.
 */
final class SessionHistoryMetadataBatch implements SessionMetadataAccounting.Batch {
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

  SessionHistoryMetadataBatch(SessionQueryContext query, Supplier<List<Map<String, Object>>> load) {
    this.query = query;
    if (query != null) query.retainScratch(this, RETAINED_BYTES);
    try {
      rows = load.get();
      if (rows.size() > 1) throw JdbcSessionRecords.corrupt();
      for (var row : rows) {
        for (String key : row.keySet())
          if (!COLUMNS.contains(key)) throw JdbcSessionRecords.corrupt();
        for (int i = 0; i < COLUMNS.size(); i++) {
          Object value = row.get(COLUMNS.get(i));
          if (query != null) query.operation(1);
          boolean valid =
              switch (i) {
                case 0 -> bounded(value, 64);
                case 1 -> bounded(value, 36);
                case 2, 3 -> value instanceof Integer;
                case 4 -> value instanceof String text && text.length() <= 65535;
                case 5 -> bounded(value, 64);
                case 6 -> bounded(value, 16);
                case 7, 8, 9, 10, 11 -> value instanceof Long;
                case 12 -> value instanceof java.time.LocalDateTime;
                case 13 -> value == null || value instanceof String text && text.length() <= 65535;
                default -> false;
              };
          if (!valid) throw JdbcSessionRecords.corrupt();
        }
      }
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  private void open() {
    if (closed) throw new IllegalStateException("Closed metadata batch");
  }

  private boolean bounded(Object value, int codePoints) {
    if (!(value instanceof String text) || text.length() > 2 * codePoints) return false;
    if (query != null) query.operation(text.length());
    return text.codePointCount(0, text.length()) <= codePoints;
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
    open();
    if (value instanceof String s) {
      long size = 0;
      for (int i = 0; i < s.length(); i++) {
        if (query != null) query.operation(1);
        char c = s.charAt(i);
        if (c < 128) size++;
        else if (c < 2048) size += 2;
        else if (Character.isHighSurrogate(c)) {
          if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
            throw JdbcSessionRecords.corrupt();
          if (query != null) query.operation(1);
          size += 4;
        } else if (Character.isLowSurrogate(c)) throw JdbcSessionRecords.corrupt();
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
    if (!(value instanceof Integer) && !(value instanceof Long)) throw JdbcSessionRecords.corrupt();
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
