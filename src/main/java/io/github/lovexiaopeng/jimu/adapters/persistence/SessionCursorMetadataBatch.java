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
  private final SessionQueryContext query;
  private final SessionMetadataProjection projection;
  private Cursor<Map<String, Object>> cursor;
  private Iterator<Map<String, Object>> source;
  private Map<String, Object> current;
  private boolean closed, iterated, delivered, ended;
  private int count, generation;

  SessionCursorMetadataBatch(
      SessionQueryContext query,
      SessionMetadataProjection projection,
      Supplier<Cursor<Map<String, Object>>> load) {
    this.query = query;
    this.projection = projection;
    try {
      if (query != null) query.retainScratch(this, projection.retainedBytes);
      cursor = load.get();
      source = cursor.iterator();
    } catch (RuntimeException | Error failure) {
      fail(failure);
      throw failure;
    }
  }

  private void fail(Throwable failure) {
    poison(failure);
    try {
      close();
    } catch (RuntimeException | Error closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private void poison(Throwable failure) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query);
    if (owner != null)
      owner.poisonProof(
          failure instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException("Metadata cursor failure", failure));
  }

  private void open() {
    if (closed) throw new IllegalStateException("Closed metadata batch");
  }

  private boolean advance() {
    open();
    if (delivered) {
      current = null;
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
    for (var key : current.keySet()) {
      SessionMetadataScalars.operation(query, 1L + 2L * key.length());
      if (!projection.names.contains(key)) throw JdbcSessionRecords.corrupt();
    }
    for (var column : projection.columns) {
      SessionMetadataScalars.operation(query, 1);
      var value = current.get(column.name());
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
            default -> false;
          };
      if (!valid) throw JdbcSessionRecords.corrupt();
    }
  }

  private boolean bounded(String text, int points) {
    if (text.length() > 2 * points) return false;
    SessionMetadataScalars.operation(query, text.length());
    return text.codePointCount(0, text.length()) <= points;
  }

  public Iterator<SessionMetadataAccounting.Row> iterator() {
    open();
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
      open();
      if (version != generation || current == null)
        throw new IllegalStateException("Expired metadata row");
    }

    public Object value(String name) {
      valid();
      SessionMetadataScalars.operation(query, 1L + name.length());
      return current.get(name);
    }

    public Iterable<?> values() {
      valid();
      return () ->
          new Iterator<>() {
            private int index;

            public boolean hasNext() {
              valid();
              while (index < projection.columns.size()) {
                var name = projection.columns.get(index).name();
                SessionMetadataScalars.operation(query, 2L + name.length());
                if (current.containsKey(name)) return true;
                index++;
              }
              SessionMetadataScalars.operation(query, 1);
              return false;
            }

            public Object next() {
              if (!hasNext()) throw new NoSuchElementException();
              return value(projection.columns.get(index++).name());
            }
          };
    }
  }

  public long scalarBytes(Object value) {
    open();
    try {
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
    source = null;
    var prior = cursor;
    cursor = null;
    try {
      if (prior != null) prior.close();
    } catch (java.io.IOException failure) {
      var error = new IllegalStateException("Metadata cursor close failed", failure);
      poison(error);
      throw error;
    } catch (RuntimeException | Error failure) {
      poison(failure);
      throw failure;
    } finally {
      prior = null;
      if (query != null) query.releaseScratch(this);
    }
  }
}
