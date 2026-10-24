package io.github.lovexiaopeng.jimu.kernel.session.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;

/** One explicitly owned synchronous query; only its cumulative counters cross async body input. */
public final class SessionQueryContext {
  private static final java.time.format.DateTimeFormatter UTC =
      java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private long deadlineNanos;
  private final boolean metered;
  private long reads, bytes, operations;
  private ResultMeter meter;

  public interface ResultMeter {
    ColumnMeter begin();
  }

  public interface ColumnMeter {
    void column(String label, Object value);
  }

  public interface MeterScope extends AutoCloseable {
    @Override
    void close();
  }

  /** Adds a version-specific envelope to this explicitly owned synchronous query. */
  public MeterScope meter(ResultMeter next) {
    ResultMeter previous = meter;
    meter = next;
    return () -> meter = previous;
  }

  public SessionQueryContext(long deadlineNanos, QueryResources prior) {
    this.deadlineNanos = deadlineNanos;
    metered = prior != null;
    if (prior != null) {
      reads = prior.reads();
      bytes = prior.bytes();
      operations = prior.operations();
    }
  }

  public long deadlineNanos() {
    return deadlineNanos;
  }

  /** A lease or nested operation may shorten this envelope; no caller can restart its clock. */
  public void restrictDeadline(long end) {
    deadlineNanos = Math.min(deadlineNanos, end);
  }

  public int remainingMillis() {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    return (int) Math.max(1, (remaining + 999999L) / 1000000L);
  }

  public QueryResources resources() {
    return new QueryResources(reads, bytes, operations);
  }

  public void operation(long count) {
    if (!metered) return;
    long previous = operations;
    operations = Math.addExact(operations, count);
    if (operations > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    if (previous / 1024 != operations / 1024) remainingMillis();
  }

  /** Called once per actual result, including an empty one. Cached reads cannot call this. */
  public Result result() {
    remainingMillis();
    if (metered && ++reads > 4096) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    return new Result();
  }

  public final class Result {
    private long batch;
    private final ColumnMeter additional = meter == null ? null : meter.begin();

    private Result() {}

    public void row() {
      operation(1);
    }

    public void column(String label, Object value) {
      if (metered) {
        operation(1);
        long size = 8L + utf8(label) + scalarBytes(value);
        bytes = Math.addExact(bytes, size);
        batch = Math.addExact(batch, size);
        if (bytes > 16777216 || batch * 3 + 1048576 > 16777216)
          throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      }
      if (additional != null) additional.column(label, value);
    }
  }

  private static int utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  public static long scalarBytes(Object value) {
    if (value == null) return 1;
    if (value instanceof byte[] data) return data.length;
    if (value instanceof java.time.LocalDateTime time) return utf8(UTC.format(time));
    return utf8(value.toString());
  }
}
