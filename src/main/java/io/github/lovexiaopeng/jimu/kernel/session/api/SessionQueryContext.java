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
  private RuntimeException firstFailure;
  private String activeStatement, activeColumn;

  public record OperationRejection(
      String statement, String column, long before, long requested, boolean columnSupplied) {}

  private OperationRejection operationRejection;

  public OperationRejection operationRejection() {
    return operationRejection;
  }

  /** 同一owner的证明、预算与截止共用首错；登记不阻止必须执行的清理。 */
  public RuntimeException recordFailure(RuntimeException failure) {
    if (firstFailure == null) firstFailure = java.util.Objects.requireNonNull(failure);
    return firstFailure;
  }

  public RuntimeException recordedFailure() {
    return firstFailure;
  }

  private RuntimeException refuse(int status, String code) {
    return recordFailure(new TaskFailure(status, code));
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
    checkFailure();
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
    checkFailure();
    if (metered) {
      groupCounters()[0]++;
      statements.merge(group + ":" + statement, 1L, Math::addExact);
    }
    remainingMillis();
    if (metered && ++reads > readLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
    return new Result(statement);
  }

  public boolean additionalMeter() {
    return meter != null;
  }

  /** 只由已计量的具名长度Result产生；无公开构造器/数值hint入口，一次消费后失效。 */
  public final class SqlUtf8Length implements AutoCloseable {
    private final SessionQueryContext query = SessionQueryContext.this;
    private final long bytes;
    private final String rawStatement;
    private boolean valid = true;

    private SqlUtf8Length(long bytes, String rawStatement) {
      this.bytes = bytes;
      this.rawStatement = rawStatement;
    }

    public long bytes() {
      operation(1);
      if (!valid) throw recordFailure(new IllegalStateException("Consumed SQL length"));
      return bytes;
    }

    @Override
    public void close() {
      valid = false;
    }
  }

  private boolean sqlUtf8Lengths = true;

  public boolean sqlUtf8Lengths() {
    return sqlUtf8Lengths;
  }

  /** 只允许关闭快捷路径；回退仍执行原完整字段计量，不退款或重置预算。 */
  public void disableSqlUtf8Lengths() {
    sqlUtf8Lengths = false;
  }

  private static final StackWalker SQL_ISSUER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static final String SQL_ISSUER_CLASS =
      "io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults";

  /** 运行时仅真实包装器可签发；反射调用与同名异loader均不能增加read或制造能力。 */
  public Result sqlUtf8LengthResult(String statement) {
    checkFailure();
    // 预留caller/Class/name/loader临时引用与walker帧；本局部退出后先断引用再退款。
    operation(8);
    retainScratch(SQL_ISSUER, 512);
    Class<?> issuer = null;
    String issuerName = null;
    try {
      issuer = SQL_ISSUER.getCallerClass();
      issuerName = issuer.getName();
      operation(1L + SQL_ISSUER_CLASS.length());
      if (issuer.getClassLoader() != SessionQueryContext.class.getClassLoader()
          || !SQL_ISSUER_CLASS.equals(issuerName))
        throw recordFailure(new IllegalStateException("Untrusted SQL length issuer"));
    } finally {
      issuer = null;
      issuerName = null;
      releaseScratch(SQL_ISSUER);
    }
    operation(3L + statement.length());
    String rawStatement;
    if (statement.equals(
        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.eventHistoryBindingSize"))
      rawStatement =
          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.eventHistory";
    else {
      operation(2L + statement.length());
      if (!statement.equals(
          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.historyBindingSize"))
        throw recordFailure(new IllegalStateException("Untrusted SQL length result"));
      rawStatement =
          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.history";
    }
    if (!sqlUtf8Lengths || meter != null)
      throw recordFailure(new IllegalStateException("Untrusted SQL length result"));
    // 与普通result相同：包括空集，每个真实ResultSet只累计一次。
    checkFailure();
    if (metered) {
      groupCounters()[0]++;
      statements.merge(group + ":" + statement, 1L, Math::addExact);
    }
    remainingMillis();
    if (metered && ++reads > readLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
    return new SqlLengthResult(statement, rawStatement);
  }

  /** 仅内部子类可扩展；私有构造器保持普通调用方无法构造/派生Result。 */
  public class Result implements AutoCloseable {
    private final String statement;
    private long batch;

    public SqlUtf8Length takeSqlUtf8Length() {
      throw recordFailure(new IllegalStateException("Ordinary result cannot issue SQL length"));
    }

    /** 无调用方数值hint；只消费当前query的不可构造能力，旧附加meter走原独立遍历。 */
    public void columnSqlUtf8(String label, Object value, SqlUtf8Length capability) {
      String priorStatement = activeStatement, priorColumn = activeColumn;
      activeStatement = statement;
      activeColumn = label;
      try {
        operation(4L + label.length() + statement.length());
        if (capability == null
            || capability.query != SessionQueryContext.this
            || !capability.valid
            || !label.equals("binding_snapshot")
            || !statement.equals(capability.rawStatement))
          throw recordFailure(new IllegalStateException("Invalid SQL length capability"));
        long size = capability.bytes;
        capability.close();
        if (additional != null) {
          columnMeasured(label, value);
          return;
        }
        operation(1);
        if (value == null) {
          operation(1);
          if (size != 1) throw recordFailure(new IllegalStateException("Invalid SQL NULL size"));
        } else {
          operation(1);
          if (!(value instanceof String text))
            throw recordFailure(new IllegalStateException("Invalid SQL text"));
          int chars = primitive("result.sqlLength", 1, text::length);
          operation(3);
          if (size < chars || size > 3L * chars)
            throw recordFailure(new IllegalStateException("Invalid SQL UTF8 size"));
        }
        if (metered) {
          operation(1L + label.length());
          long total = 8L + utf8(label) + size;
          bytes = Math.addExact(bytes, total);
          groupCounters()[1] = Math.addExact(groupCounters()[1], total);
          batch = Math.addExact(batch, total);
          retainScratch(this, Math.multiplyExact(batch, 3));
          if (bytes > byteLimit) throw refuse(429, "SESSION_HISTORY_LIMIT");
        }
      } catch (RuntimeException failure) {
        throw recordFailure(failure);
      } finally {
        activeStatement = priorStatement;
        activeColumn = priorColumn;
      }
    }

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

  /** 只有此单列结果收集长度；普通Result无新增逐行/逐列条件或遍历。 */
  private final class SqlLengthResult extends Result {
    private final String rawStatement;
    private int rows, columns;
    private Object size;
    private boolean taken;

    private SqlLengthResult(String statement, String rawStatement) {
      super(statement);
      this.rawStatement = rawStatement;
    }

    @Override
    public void row() {
      super.row();
      operation(1);
      rows++;
    }

    @Override
    public void column(String label, Object value) {
      super.column(label, value);
      operation(3L + label.length());
      if (!label.equals("binding_snapshot_utf8_bytes"))
        throw recordFailure(new IllegalStateException("Invalid SQL length label"));
      columns++;
      size = value;
    }

    @Override
    public SqlUtf8Length takeSqlUtf8Length() {
      operation(5);
      if (taken || rows != 1 || columns != 1 || !(size instanceof Integer || size instanceof Long))
        throw recordFailure(new IllegalStateException("Invalid SQL length shape"));
      long bytes = ((Number) size).longValue();
      operation(3);
      if (bytes < 0) throw recordFailure(new IllegalStateException("Negative SQL length"));
      size = null;
      taken = true;
      return new SqlUtf8Length(bytes, rawStatement);
    }

    @Override
    public void close() {
      size = null;
      super.close();
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
