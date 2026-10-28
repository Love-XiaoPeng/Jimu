package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** V23单行8标量投影的独占物化批次；先预留，真实清引用后释放，关闭facade不可继续访问。 */
final class SessionLineageMetadataBatch implements SessionMetadataAccounting.Batch {
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "task_id",
          "record_version",
          "genesis_digest",
          "genesis_bytes",
          "graph_digest",
          "graph_bytes",
          "content_state");
  // V23: 64+36+64+64+16 UTF16 chars, 3 INT objects; list/map/8 entries/labels/facades。
  // 字符串列单独对象上界(244*2+5*64)，数字3*32，8个map entry各128，容器/迭代器1024。
  static final long RETAINED_BYTES = 244L * 2 + 5L * 64 + 3L * 32 + 8L * 128 + 1024;
  private final SessionQueryContext query;
  private List<Map<String, Object>> rows;
  private boolean closed;

  SessionLineageMetadataBatch(SessionQueryContext query, Supplier<List<Map<String, Object>>> load) {
    this.query = query;
    if (query != null) query.retainScratch(this, RETAINED_BYTES);
    try {
      rows = load.get();
      if (rows.size() > 1) throw JdbcSessionRecords.corrupt();
      for (var row : rows) {
        if (!row.keySet().equals(java.util.Set.copyOf(COLUMNS))) throw JdbcSessionRecords.corrupt();
        for (int i = 0; i < COLUMNS.size(); i++) {
          Object value = row.get(COLUMNS.get(i));
          int max =
              switch (i) {
                case 0, 3, 5 -> 64;
                case 1 -> 36;
                case 7 -> 16;
                default -> 0;
              };
          if (query != null) query.operation(1);
          if (max == 0
              ? !(value instanceof Integer)
              : !(value instanceof String s) || s.length() > max)
            throw JdbcSessionRecords.corrupt();
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
    if (!(value instanceof Integer number)) throw JdbcSessionRecords.corrupt();
    long n = number.longValue();
    long size = n < 0 ? 1 : 0;
    if (n < 0) n = -n;
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
    if (rows != null) {
      for (var row : rows) row.clear();
      rows.clear();
      rows = null;
    }
    if (query != null) query.releaseScratch(this);
  }
}
