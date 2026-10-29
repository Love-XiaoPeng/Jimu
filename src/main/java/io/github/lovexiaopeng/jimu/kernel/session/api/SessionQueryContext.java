package io.github.lovexiaopeng.jimu.kernel.session.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** One explicitly owned synchronous query; only its cumulative counters cross async body input. */
public final class SessionQueryContext {
  private static final java.time.format.DateTimeFormatter UTC =
      java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private long deadlineNanos;
  private final boolean metered;
  private long reads, bytes, operations;
  private long readLimit = 4096, byteLimit = 16777216, operationLimit = 2097152;
  private long scratchLimit = 16777216, scratchReserve = 1048576;
  private ResultMeter meter;
  private final java.util.Map<Object, Long> retainedScratch = new java.util.HashMap<>();
  private long scratchPeak;
  private TaskFailure firstFailure;
  private String activeStatement, activeColumn;

  public record OperationRejection(
      String statement, String column, long before, long requested, boolean columnSupplied) {}

  private OperationRejection operationRejection;

  public OperationRejection operationRejection() {
    return operationRejection;
  }

  private TaskFailure refuse(int status, String code) {
    if (firstFailure == null) firstFailure = new TaskFailure(status, code);
    return firstFailure;
  }

  private void checkFailure() {
    if (firstFailure != null) throw firstFailure;
  }

  /** 首次拒绝不可因catch或释放scratch恢复；清理仍可通过releaseScratch执行。 */
  public void checkHealthy() {
    checkFailure();
    checkLimits();
    scratch(0);
  }

  private String group = "B";
  private final java.util.Map<String, long[]> groups = new java.util.TreeMap<>();
  private final java.util.Map<String, Long> statements = new java.util.TreeMap<>();
  private final java.util.Map<String, Long> requestedWork = new java.util.TreeMap<>();
  private final java.util.Map<String, Long> completedWork = new java.util.TreeMap<>();

  /** 原单位累计；先申请后执行，拒绝处不能登记为已完成。 */
  public <T> T primitive(String kind, long count, java.util.function.Supplier<T> work) {
    requestedWork.merge(kind, count, Math::addExact);
    operation(count);
    T result = work.get();
    completedWork.merge(kind, count, Math::addExact);
    return result;
  }

  public java.util.Map<String, Long> requestedWork() {
    return java.util.Map.copyOf(requestedWork);
  }

  public java.util.Map<String, Long> completedWork() {
    return java.util.Map.copyOf(completedWork);
  }

  public long scratchLive() {
    long live = scratchReserve;
    for (long size : retainedScratch.values()) live = Math.addExact(live, size);
    return live;
  }

  public MeterScope group(String next) {
    String prior = group;
    group = next;
    return () -> group = prior;
  }

  public String groups() {
    var values = new java.util.TreeMap<String, String>();
    groups.forEach((key, v) -> values.put(key, v[0] + "/" + v[1] + "/" + v[2]));
    return values.toString();
  }

  public java.util.Map<String, Long> statements() {
    return java.util.Map.copyOf(statements);
  }

  private long[] groupCounters() {
    return groups.computeIfAbsent(group, k -> new long[3]);
  }

  public boolean lineage() {
    return readLimit == SessionLineagePolicyV5.PROOF_READS;
  }

  public void retainScratch(Object owner, long bytes) {
    if (bytes < 0) throw new IllegalArgumentException("Negative session scratch");
    long prior = retainedScratch.getOrDefault(owner, 0L);
    scratch(Math.subtractExact(bytes, prior));
    retainedScratch.put(owner, bytes);
  }

  public void releaseScratch(Object owner) {
    retainedScratch.remove(owner);
  }

  public long scratchPeak() {
    return scratchPeak;
  }

  private void scratch(long transientBytes) {
    checkFailure();
    long live = scratchReserve;
    for (long bytes : retainedScratch.values()) live = Math.addExact(live, bytes);
    live = Math.addExact(live, transientBytes);
    scratchPeak = Math.max(scratchPeak, live);
    if (metered && live > scratchLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
  }

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
    checkFailure();
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) throw refuse(504, "SESSION_QUERY_TIMEOUT");
    return (int) Math.max(1, (remaining + 999999L) / 1000000L);
  }

  public QueryResources resources() {
    return new QueryResources(reads, bytes, operations);
  }

  /** 只缩小已有累计包络，认证前后的计数和绝对截止均不重置。 */
  public void restrictLineage() {
    readLimit = Math.min(readLimit, SessionLineagePolicyV5.PROOF_READS);
    byteLimit = Math.min(byteLimit, SessionLineagePolicyV5.PROOF_BYTES);
    operationLimit = Math.min(operationLimit, SessionLineagePolicyV5.OPERATIONS);
    scratchLimit = Math.min(scratchLimit, SessionLineagePolicyV5.PROOF_BYTES);
    scratchReserve = Math.min(scratchReserve, 65536);
    checkLimits();
    scratch(0);
  }

  private void checkLimits() {
    remainingMillis();
    if (metered && (reads > readLimit || bytes > byteLimit || operations > operationLimit))
      throw refuse(429, "SESSION_HISTORY_LIMIT");
  }

  public void operation(long count) {
    checkFailure();
    if (!metered) return;
    long previous = operations;
    operations = Math.addExact(operations, count);
    groupCounters()[2] = Math.addExact(groupCounters()[2], count);
    if (operations > operationLimit) {
      operationRejection =
          new OperationRejection(
              activeStatement, activeColumn, previous, count, activeColumn != null);
      throw refuse(429, "SESSION_HISTORY_LIMIT");
    }
    if (previous / 1024 != operations / 1024) remainingMillis();
  }

  /** Called once per actual result, including an empty one. Cached reads cannot call this. */
  public Result result() {
    return result("JDBC");
  }

  public Result result(String statement) {
    if (metered) {
      groupCounters()[0]++;
      statements.merge(group + ":" + statement, 1L, Math::addExact);
    }
    remainingMillis();
    if (metered && ++reads > readLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
    return new Result(statement);
  }

  public final class Result implements AutoCloseable {
    private final String statement;
    private long batch;
    private final ColumnMeter additional = meter == null ? null : meter.begin();

    private Result(String statement) {
      this.statement = statement;
    }

    public void close() {
      releaseScratch(this);
    }

    public void row() {
      operation(1);
    }

    public void column(String label, Object value) {
      String priorStatement = activeStatement, priorColumn = activeColumn;
      activeStatement = statement;
      activeColumn = label;
      try {
        columnMeasured(label, value);
      } finally {
        activeStatement = priorStatement;
        activeColumn = priorColumn;
      }
    }

    private void columnMeasured(String label, Object value) {
      if (metered) {
        operation(1);
        operation(label.length());
        if (value instanceof String text) operation(text.length());
        else if (value instanceof Integer || value instanceof Long) operation(scalarBytes(value));
        long size = 8L + utf8(label) + scalarBytes(value);
        bytes = Math.addExact(bytes, size);
        groupCounters()[1] = Math.addExact(groupCounters()[1], size);
        batch = Math.addExact(batch, size);
        retainScratch(this, Math.multiplyExact(batch, 3));
        if (bytes > byteLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
      }
      if (additional != null) additional.column(label, value);
    }
  }

  private static int utf8(String value) {
    int bytes = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 128) bytes++;
      else if (c < 2048) bytes += 2;
      else if (Character.isHighSurrogate(c)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else bytes += Character.isSurrogate(c) ? 1 : 3;
    }
    return bytes;
  }

  public static long scalarBytes(Object value) {
    if (value == null) return 1;
    if (value instanceof byte[] data) return data.length;
    if (value instanceof String text) return utf8(text);
    if (value instanceof Integer || value instanceof Long) {
      long number = ((Number) value).longValue();
      // 负数域避免Long.MIN_VALUE取正溢出；无toString/整串byte[]临时副本。
      long bytes = number < 0 ? 1 : 0;
      if (number > 0) number = -number;
      do {
        bytes++;
        number /= 10;
      } while (number != 0);
      return bytes;
    }
    if (value instanceof java.time.LocalDateTime time) return utf8(UTC.format(time));
    return utf8(value.toString());
  }
}
