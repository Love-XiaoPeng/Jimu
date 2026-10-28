package io.github.lovexiaopeng.jimu.kernel.session.port;

/** Ordered metadata rows and fixed-point counters in the caller's existing unit of work. */
public interface SessionMetadataAccounting {
  enum Table {
    TASK,
    TASK_SESSION_HISTORY,
    SESSION_PUBLIC_EVENT,
    INVOCATION_SESSION_READ,
    INVOCATION_SESSION_SEARCH,
    INVOCATION_SESSION_TRACE,
    TASK_SESSION_SCOPE,
    TASK_SESSION_SCOPE_TARGET,
    STEP_SESSION_SCOPE,
    INVOCATION_SESSION_SCOPE_QUERY,
    TASK_SESSION_LINEAGE,
    INVOCATION_SESSION_LINEAGE_TRACE,
    TASK_ATTEMPT,
    STEP,
    INVOCATION,
    MODEL_DISPATCH,
    RESERVATION,
    EVENT
  }

  interface Row {
    Iterable<?> values();

    default Object value(String name) {
      throw new UnsupportedOperationException(name);
    }
  }

  /** 物化结果的引用寿命；消费完成或异常都必须关闭，游标关闭不代表batch释放。 */
  interface Batch extends Iterable<Row>, AutoCloseable {
    default long scalarBytes(Object value) {
      return io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.scalarBytes(value);
    }

    @Override
    void close();
  }

  default Batch batch(Table table) {
    return new Batch() {
      private Iterable<? extends Row> values = rows(table);

      public java.util.Iterator<Row> iterator() {
        if (values == null) throw new IllegalStateException("Closed metadata batch");
        var iterator = values.iterator();
        return new java.util.Iterator<>() {
          public boolean hasNext() {
            return iterator.hasNext();
          }

          public Row next() {
            return iterator.next();
          }
        };
      }

      public void close() {
        values = null;
      }
    };
  }

  record Balance(
      long metadata,
      long body,
      long reserved,
      long held,
      long amount,
      Long actual,
      String reservationState,
      String content) {}

  default boolean finalPresent() {
    return false;
  }

  default Balance readBack() {
    throw new UnsupportedOperationException("Final account readback");
  }

  boolean session();

  Iterable<? extends Row> rows(Table table);

  long metadataBytes();

  void account(long bytes);
}
