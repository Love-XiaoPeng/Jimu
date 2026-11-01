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
  // V23: 64+36+64+64+16 code points (up to two UTF16 units each), 3 INT objects.
  // Strings (244*4+5*64), numbers 3*32, eight entries incl labels 8*128, facades/table/list 1024.
  static final long RETAINED_BYTES = 244L * 4 + 5L * 64 + 3L * 32 + 8L * 128 + 1024;
  private final SessionQueryContext query;
  private final io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork finalOwner;
  private List<Map<String, Object>> rows;
  private boolean closed;

  SessionLineageMetadataBatch(SessionQueryContext query, Supplier<List<Map<String, Object>>> load) {
    this(query, load, false);
  }

  private SessionLineageMetadataBatch(
      SessionQueryContext query, Supplier<List<Map<String, Object>>> load, boolean finalMode) {
    this.query = query;
    this.finalOwner =
        finalMode
            ? io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                .currentOwner()
            : null;
    try {
      if (finalMode) finalCheck();
      if (query != null) query.retainScratch(this, RETAINED_BYTES);
      rows = load.get();
      if (finalMode) finalCheck();
      if (rows.size() > 1) throw JdbcSessionRecords.corrupt();
      if (finalMode) return;
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
          if (max == 0 ? !(value instanceof Integer) : !bounded(value, max))
            throw JdbcSessionRecords.corrupt();
        }
      }
    } catch (RuntimeException | Error failure) {
      poison(failure);
      close();
      throw failure;
    }
  }

  private void poison(Throwable failure) {
    var work =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (finalOwner != null && finalOwner != work) {
      if (failure instanceof Error error) finalOwner.poisonLineageSource(error);
      else finalOwner.poisonProof((RuntimeException) failure);
    }
    if (work != null) {
      if (failure instanceof Error error) work.poisonLineageSource(error);
      else work.poisonProof((RuntimeException) failure);
    }
  }

  private void finalCheck() {
    SessionMetadataScalars.operation(query, 12);
    var actual =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (closed
        || actual != finalOwner
        || (actual != null
            && io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                    .current(query)
                != actual)) throw new IllegalStateException("Foreign final metadata");
    if (query != null) query.checkHealthy();
  }

  static SessionMetadataAccounting.FinalTable measureFinal(
      SessionQueryContext query, Supplier<List<Map<String, Object>>> load) {
    try (var batch = new SessionLineageMetadataBatch(query, load, true)) {
      try {
        batch.finalCheck();
        long bytes = 0;
        for (var row : batch.rows) {
          SessionMetadataScalars.operation(query, 4);
          if (row.size() != COLUMNS.size()) throw JdbcSessionRecords.corrupt();
          bytes =
              Math.addExact(
                  bytes,
                  io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1
                      .ROW_HEADER_BYTES);
          for (int i = 0; i < COLUMNS.size(); i++) {
            SessionMetadataScalars.operation(query, 4);
            Object value = row.get(COLUMNS.get(i));
            int max =
                switch (i) {
                  case 0, 3, 5 -> 64;
                  case 1 -> 36;
                  case 7 -> 16;
                  default -> 0;
                };
            if (max == 0 ? !(value instanceof Integer) : !batch.bounded(value, max))
              throw JdbcSessionRecords.corrupt();
            long scalar = batch.scalar(value);
            SessionMetadataScalars.operation(query, 3);
            bytes =
                Math.addExact(
                    bytes,
                    Math.addExact(
                        io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1
                            .FIELD_HEADER_BYTES,
                        scalar));
          }
        }
        SessionMetadataScalars.operation(query, 9);
        batch.finalCheck();
        return new SessionMetadataAccounting.FinalTable(batch.rows.size(), bytes, 0, null, 0, null);
      } catch (RuntimeException | Error failure) {
        batch.poison(failure);
        throw failure;
      }
    }
  }

  private boolean bounded(Object value, int points) {
    if (!(value instanceof String text)) return false;
    // 先付getter、局部存储、两次长度比较与分支/返回或fallback交接的7操作。
    // UTF16长度不超过码点上限已充分；仅较宽字符串仍预付后扫描，不缓存内容。
    if (query != null) query.operation(7);
    int units = text.length();
    if (units > 2 * points) return false;
    if (units <= points) return true;
    if (query != null) query.operation(units);
    return text.codePointCount(0, units) <= points;
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
    return scalar(value);
  }

  private long scalar(Object value) {
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
    rows = null;
    if (query != null) query.releaseScratch(this);
  }
}
