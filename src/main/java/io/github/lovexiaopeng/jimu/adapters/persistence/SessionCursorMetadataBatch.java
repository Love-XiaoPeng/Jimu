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
  private boolean[] present;
  private final Object slotsOwner = new Object();
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
      if (query != null) query.retainScratch(slotsOwner, 256L + 16L * projection.columns.size());
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
      if (failure != closeFailure) failure.addSuppressed(closeFailure);
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
      present = null;
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
    SessionMetadataScalars.operation(query, 2L + 2L * projection.columns.size());
    slots = new Object[projection.columns.size()];
    present = new boolean[projection.columns.size()];
    int slot = 0;
    for (var column : projection.columns) {
      SessionMetadataScalars.operation(query, 1);
      var value = current.get(column.name());
      SessionMetadataScalars.operation(query, 3); // 两次数组写入及索引前移。
      int index = slot++;
      slots[index] = value;
      if (value == null) {
        SessionMetadataScalars.operation(query, 2L + column.name().length());
        present[index] = current.containsKey(column.name());
      } else present[index] = true;
      SessionMetadataScalars.operation(query, 3); // presence读取、分支及最多一次计数推进。
      if (present[index]) recognized++;
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
    SessionMetadataScalars.operation(query, 4); // Map大小读取、比较、分支/返回交接。
    if (recognized != current.size()) throw JdbcSessionRecords.corrupt();
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
                while (index < projection.columns.size()) {
                  SessionMetadataScalars.operation(query, 2); // 边界与presence读取。
                  if (present[index]) return true;
                  index++;
                }
                SessionMetadataScalars.operation(query, 1);
                return false;
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
    present = null;
    source = null;
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
