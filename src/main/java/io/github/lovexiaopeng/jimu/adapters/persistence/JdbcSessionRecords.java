package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native public history records. All mutations run under the owning ledger's budget/task locks. */
final class JdbcSessionRecords {
  final SessionContracts contracts = new SessionContracts();
  final SessionContracts v2 = new SessionContracts(2);
  final SessionContracts v3 = new SessionContracts(3);
  final SessionContracts v4 = new SessionContracts(4);
  JdbcSessionScopes scopes;
  final io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts searchV3 =
      new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts(3);
  final io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts trace =
      new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts();

  static boolean traceTask(Map<String, Object> task) {
    return "session_event_read,session_event_search,session_event_trace"
        .equals(s(task, "capabilities"));
  }

  io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts searchContract(
      Map<String, Object> task) {
    return traceTask(task) ? searchV3 : search;
  }

  io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor descriptor(
      Map<String, Object> task, String name) {
    return name.equals("session_event_read")
        ? contract(task).descriptor()
        : name.equals("session_search")
            ? scopes.contracts.descriptor()
            : name.equals("session_event_search")
                ? searchContract(task).descriptor()
                : trace.descriptor();
  }

  final io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts search =
      new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts();
  private final JdbcTemplate db;
  private SearchBudget searchBudget;
  private final io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query;

  private static final class SearchBudget
      implements io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.ResultMeter {
    int reads;
    long bytes, operations;
    final Runnable deadline;
    final boolean trace;
    final boolean prior;
    final long scratchLimit;

    SearchBudget(Runnable deadline) {
      this(deadline, false);
    }

    SearchBudget(Runnable deadline, boolean trace) {
      this(deadline, trace, 8388608);
    }

    SearchBudget(Runnable deadline, boolean trace, long scratchLimit) {
      this(deadline, trace, scratchLimit, false);
    }

    SearchBudget(Runnable deadline, boolean trace, long scratchLimit, boolean prior) {
      this.deadline = deadline;
      this.trace = trace;
      this.prior = prior;
      this.scratchLimit = scratchLimit;
    }

    @Override
    public io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.ColumnMeter begin() {
      deadline.run();
      if (++reads > (prior ? 4096 : 1024)) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      return new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.ColumnMeter() {
        private long batchBytes;

        @Override
        public void column(String label, Object value) {
          long size =
              8L
                  + SessionContracts.bytes(label)
                  + io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.scalarBytes(
                      value);
          bytes += size;
          batchBytes += size;
          if (trace) operation(size);
          // Reject at the provider boundary, before collecting the next result field.
          if (bytes > (prior ? 16777216 : 8388608)
              || (prior ? batchBytes * 3 : bytes * (trace ? 3 : 2)) + 524288 > scratchLimit)
            throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
        }
      };
    }

    void operation(long count) {
      operations += count;
      if (trace && operations > 1048576) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      deadline.run();
    }
  }

  static boolean searchTask(Map<String, Object> task) {
    return traceTask(task)
        || "session_event_read,session_event_search".equals(s(task, "capabilities"));
  }

  int version(Map<String, Object> task) {
    return JdbcSessionScopes.task(task) ? 4 : traceTask(task) ? 3 : searchTask(task) ? 2 : 1;
  }

  SessionContracts contract(Map<String, Object> task) {
    return JdbcSessionScopes.task(task)
        ? v4
        : traceTask(task) ? v3 : searchTask(task) ? v2 : contracts;
  }

  private List<String> sensitive;
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private static final List<String> TABLES =
      List.of(
          "task",
          "task_session_history",
          "session_public_event",
          "invocation_session_read",
          "invocation_session_search",
          "invocation_session_trace",
          "task_session_scope",
          "task_session_scope_target",
          "step_session_scope",
          "invocation_session_scope_query",
          "task_attempt",
          "step",
          "invocation",
          "model_dispatch",
          "reservation",
          "event");

  JdbcSessionRecords(JdbcTemplate db) {
    this(db, null);
  }

  JdbcSessionRecords(
      JdbcTemplate db, io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    this(db, query, null);
  }

  private org.mybatis.spring.SqlSessionTemplate mapperSession;
  java.util.function.Function<Map<String, Object>, String> context;

  JdbcSessionRecords(
      JdbcTemplate db,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      org.mybatis.spring.SqlSessionTemplate mapperSession) {
    this.db = db;
    this.query = query;
    this.mapperSession = mapperSession;
  }

  void copyConfiguration(JdbcSessionRecords original) {
    this.sensitive = original.sensitive;
  }

  void sensitive(List<String> values) {
    sensitive = List.copyOf(values);
  }

  List<String> sensitive() {
    if (sensitive == null) throw new TaskFailure(503, "SESSION_EVENT_UNSAFE");
    return sensitive;
  }

  static String s(Map<String, Object> row, String name) {
    return (String) row.get(name);
  }

  static long n(Map<String, Object> row, String name) {
    return ((Number) row.get(name)).longValue();
  }

  static boolean task(Map<String, Object> task) {
    return JdbcSessionScopes.task(task)
        || SessionContracts.NAME.equals(s(task, "capabilities"))
        || searchTask(task);
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return db.queryForList(sql, args);
    }
  }

  private Map<String, Object> one(String sql, Object... args) {
    var result = rows(sql, args);
    if (result.size() != 1) throw corrupt();
    return result.getFirst();
  }

  static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryProofService
      historyProofs =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionHistoryProofServiceImpl();

  Map<String, Object> history(Map<String, Object> task, boolean readable) {
    var proof = new MapperSessionHistoryProofs(this, task, null);
    historyProofs.history(proof, readable);
    return proof.historyRow();
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService
      historyStorage =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionHistoryStorageServiceImpl();

  void create(Map<String, Object> task) {
    historyStorage.create(new MapperSessionHistoryStorage(this, task));
  }

  void body(Map<String, Object> task, long additional) {
    historyStorage.body(new MapperSessionHistoryStorage(this, task), additional);
  }

  io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryStorageMapper
      historyStorageMapper() {
    return mapperSession()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                .SessionHistoryStorageMapper.class);
  }

  private org.mybatis.spring.SqlSessionTemplate mapperSession() {
    if (mapperSession == null)
      mapperSession =
          io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
              .create(java.util.Objects.requireNonNull(db.getDataSource()));
    return mapperSession;
  }

  List<Map<String, Object>> mapperRows(String operation, Map<String, Object> task) {
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("tenant", s(task, "tenant_id"));
    args.put("taskId", s(task, "id"));
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession()
          .selectList(
              "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper." + operation, args);
    }
  }

  long metadata(Map<String, Object> task, boolean closing) {
    return metadata(task, closing, 0);
  }

  long metadata(Map<String, Object> task, boolean closing, long planned) {
    long total = 0;
    long cleanupHeadroom = 1024;
    for (String table : TABLES) {
      String operation =
          "SessionHistoryRetentionMapper.metadata"
              + java.util.Arrays.stream(table.split("_"))
                  .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
                  .collect(java.util.stream.Collectors.joining());
      for (var row : mapperRows(operation, task)) {
        cleanupHeadroom +=
            switch (table) {
              case "task" -> 768;
              case "step", "invocation", "invocation_session_search" -> 512;
              case "model_dispatch", "session_public_event" -> 256;
              default -> 0;
            };
        total += 32;
        for (Object value : row.values()) total += 8 + scalarBytes(value);
      }
    }
    if (total > (closing ? 65536 : 61440)) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    // Hash/length cleanup receipts add metadata when bodies are removed. Reserve that capacity
    // before accepting more facts, without charging hypothetical bytes as actual stored bytes.
    if (!closing && total + cleanupHeadroom + planned > 61440)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    return total;
  }

  private static int scalarBytes(Object value) {
    if (value == null) return 1;
    if (value instanceof LocalDateTime time) return SessionContracts.bytes(UTC.format(time));
    return SessionContracts.bytes(value.toString());
  }

  void account(Map<String, Object> task, boolean closing) {
    if (!task(task)) return;
    // Counters themselves have variable-width decimal scalars; converge before recording them.
    for (int i = 0; i < 4; i++) {
      long bytes = metadata(task, closing);
      long old =
          n(mapperRows("SessionContextMapper.metadataBytes", task).getFirst(), "metadata_bytes");
      if (old == bytes) return;
      mapperSession()
          .getMapper(
              io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionContextMapper
                  .class)
          .account(query, bytes, s(task, "tenant_id"), s(task, "id"));
    }
    throw corrupt();
  }

  Map<String, Object> source(String kind, String step, String invocation) {
    var source = new LinkedHashMap<String, Object>();
    source.put("kind", kind);
    source.put("step_id", step);
    source.put("invocation_id", invocation);
    return source;
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionEventAppendService
      eventAppends =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionEventAppendServiceImpl();

  io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEventAppendMapper
      eventAppendMapper() {
    return mapperSession()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEventAppendMapper
                .class);
  }

  io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext appendQuery() {
    return query;
  }

  long append(
      Map<String, Object> task,
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String sourceDigest,
      Lease lease,
      long reliable) {
    return eventAppends.append(
        new MapperSessionEventAppend(
            this, task, fact, type, source, data, redacted, sourceDigest, lease, reliable));
  }

  List<Map<String, Object>> recent(Map<String, Object> task, long upper) {
    var wrapper =
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                    .ScopeQueryNativeEntity>()
            .select(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq,
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getType)
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTenantId,
                s(task, "tenant_id"))
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTaskId,
                s(task, "id"))
            .between(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq,
                Math.max(0, upper - 7),
                upper)
            .orderByAsc(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq)
            .last("LIMIT 8");
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("ew", wrapper);
    List<Map<String, Object>> result;
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      result =
          mapperSession()
              .selectList(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper.selectMaps",
                  args);
    }
    if (result.size() != Math.min(8, upper + 1)) throw corrupt();
    return result;
  }

  String context(Map<String, Object> task) {
    return context.apply(task);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service
          .SessionCheckpointPreparationService
      checkpointPreparation =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionCheckpointPreparationServiceImpl(Payloads::hash);

  void prepare(Map<String, Object> task, String request, String step) {
    checkpointPreparation.prepare(
        new MapperSessionCheckpointPreparation(this, task, step), request);
  }

  void updatePreparedStep(Object wrapper) {
    var args = new java.util.HashMap<String, Object>();
    args.put("et", null);
    args.put("ew", wrapper);
    args.put("query", query);
    mapperSession()
        .update(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper.update",
            args);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallBindingService
      callBindings =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionCallBindingServiceImpl();

  void bindCalls(Map<String, Object> task, String step) {
    callBindings.bind(new MapperSessionCallBindings(this, task, step));
  }

  void validateStep(Map<String, Object> task, Map<String, Object> st) {
    historyProofs.validateStep(new MapperSessionHistoryProofs(this, task, st));
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionResponseService
      responsePreflight =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionResponseServiceImpl();

  void response(
      Map<String, Object> task,
      String response,
      List<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call> calls) {
    responsePreflight.validate(new StoredSessionResponsePreflight(this, task), response, calls);
  }

  String metadataView(Map<String, Object> task) {
    var h = history(task, false);
    long upper = n(h, "next_seq") - 1;
    boolean readable = "PRESENT".equals(s(h, "content_state")) && n(h, "retained") == 1;
    var value = new LinkedHashMap<String, Object>();
    value.put("version", version(task));
    value.put("session_id", s(task, "id"));
    value.put("content_state", s(h, "content_state"));
    value.put("upper_seq", upper);
    value.put("recent", readable ? recent(task, upper) : List.of());
    value.put("user_message_seq", 0);
    value.put("as_of_sse_seq", n(task, "next_event_seq"));
    value.put("retain_until", UTC.format((LocalDateTime) h.get("retain_until")));
    value.put("readable", readable);
    if (JdbcSessionScopes.task(task)) {
      var scope = scopes.require(task);
      value.put("scope_sha256", s(scope.row(), "authority_digest"));
      value.put("scope_state", s(scope.row(), "grant_state"));
    }
    String raw = Payloads.json(value);
    contract(task).owner(raw);
    return raw;
  }

  record Read(
      String output, long from, long to, String targetDigest, String sources, int eventBytes) {}

  Read read(
      Map<String, Object> task,
      SessionEventPort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    if (!traceTask(task) && !JdbcSessionScopes.task(task))
      return readRows(task, input, upper, boundary);
    searchBudget =
        JdbcSessionScopes.task(task)
            ? new SearchBudget(deadline, false, 16777216, true)
            : new SearchBudget(deadline, true, 1048576);
    try {
      return readRows(task, input, upper, boundary);
    } finally {
      searchBudget = null;
    }
  }

  private Read readRows(
      Map<String, Object> task, SessionEventPort.Input input, long upper, String boundary) {
    var result =
        scopeExecution.window(
            new MapperSessionScopeExecution(this, task, () -> {})
                .window(task, input, upper, boundary));
    return new Read(
        result.output(),
        result.from(),
        result.to(),
        result.targetDigest(),
        result.sources(),
        result.eventBytes());
  }

  static Map<String, Object> readSummary(JsonNode value) {
    var summary = new LinkedHashMap<String, Object>();
    for (String key : List.of("seq", "type", "time", "source", "redacted"))
      summary.put(key, value.get(key));
    String text = value.path("data").path("content").asText("");
    String prefix = SessionContracts.prefix(text);
    summary.put("text", prefix);
    summary.put("summary_truncated", !prefix.equals(text));
    if (SessionContracts.bytes(Payloads.json(summary)) > 1536)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    return summary;
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionEventProofService
      eventProofs =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionEventProofServiceImpl();

  JsonNode verify(Map<String, Object> task, Map<String, Object> row) {
    var proof = new MapperSessionEventProofs(this, task, row);
    eventProofs.verify(proof);
    return proof.value();
  }

  boolean priorProof() {
    return searchBudget != null && searchBudget.prior;
  }

  void proofOperation() {
    if (query != null) query.operation(1);
  }

  Map<String, Object> proofRow(Class<?> mapper, Object wrapper) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("ew", wrapper);
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      List<Map<String, Object>> rows =
          mapperSession().selectList(mapper.getName() + ".selectMaps", args);
      if (rows.size() != 1) throw corrupt();
      return rows.getFirst();
    }
  }

  List<Map<String, Object>> bindingRows(Class<?> mapper, Object wrapper) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("ew", wrapper);
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession().selectList(mapper.getName() + ".selectMaps", args);
    }
  }

  void insertBinding(Class<?> mapper, Object entity) {
    var args = new java.util.HashMap<String, Object>();
    var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
    for (String field : meta.getGetterNames()) args.put(field, meta.getValue(field));
    args.put("query", query);
    mapperSession().insert(mapper.getName() + ".insert", args);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionReadRelationProofService
      readRelationProofs =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionReadRelationProofServiceImpl();

  List<JsonNode> readSources(Map<String, Object> task, String invocation) {
    var source = new MapperSessionReadRelationProofs(this, task, invocation);
    readRelationProofs.sources(source);
    return source.sourceNodes();
  }

  // Flat proof: original slot and immediate endpoint digests; never replay nested reads.
  void verifyReadRelation(
      Map<String, Object> task,
      Map<String, Object> row,
      JsonNode event,
      Map<String, Object> invocation,
      Map<String, Object> binding) {
    readRelationProofs.verify(
        new MapperSessionReadRelationProofs(this, task, row, event, invocation, binding));
  }

  record Trace(
      String output,
      String scanDigest,
      String relationsDigest,
      String targetDigest,
      int rows,
      int eventBytes,
      int edges) {}

  io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution.Budget
      nativeTraceBudget(Runnable deadline) {
    var budget = new SearchBudget(deadline, true);
    searchBudget = budget;
    return new io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution
        .Budget() {
      public void operation(long amount) {
        budget.operation(amount);
      }

      public void close() {
        searchBudget = null;
      }
    };
  }

  Trace trace(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    var result =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl
                .SessionNativeTraceExecutionServiceImpl()
            .trace(
                new MapperSessionNativeTraceExecution(
                    this, task, input, upper, boundary, deadline));
    return new Trace(
        result.output(),
        result.scanDigest(),
        result.relationsDigest(),
        result.targetDigest(),
        result.rows(),
        result.eventBytes(),
        result.edges());
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionReceiptService receipts =
      new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionReceiptServiceImpl();

  Map<String, Object> receipt(Map<String, Object> b) {
    var values = new StoredSessionReceipts(this, b);
    receipts.nativeReceipt(values);
    return values.result();
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionInputService inputs =
      new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionInputServiceImpl();

  void validateInput(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call call) {
    inputs.validate(new StoredSessionInputEligibility(this, task, call));
  }

  record PublicCalls(List<DurableAgent.ToolCall> calls, boolean redacted) {}

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallProjectionService
      callProjections =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionCallProjectionServiceImpl();

  PublicCalls projectedCalls(Map<String, Object> task, List<DurableAgent.ToolCall> calls) {
    var values = new StoredSessionCallProjections(this, task, calls);
    callProjections.project(values);
    return values.result();
  }

  private static Map<String, Object> scopeRef(String id, long seq, String digest) {
    var value = new LinkedHashMap<String, Object>();
    value.put("session_id", id);
    value.put("seq", seq);
    value.put("event_sha256", digest);
    return value;
  }

  String normalizedScope(
      Map<String, Object> task, Map<String, Object> step, String name, String raw) {
    return scopeNormalization.normalize(
        new StoredSessionScopeNormalization(this, task, step, name, raw));
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeNormalizationService
      scopeNormalization =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionScopeNormalizationServiceImpl();

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeReadRefsService
      scopeReadRefsService =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionScopeReadRefsServiceImpl();

  List<JsonNode> scopeReadRefs(Map<String, Object> task, String invocation) {
    var refs = new MapperSessionScopeReadRefs(this, task, invocation);
    scopeReadRefsService.verify(refs);
    return refs.nodes();
  }

  Map<String, Object> scopeReceipt(Map<String, Object> b) {
    var values = new StoredSessionReceipts(this, b);
    receipts.scopeReceipt(values);
    return values.result();
  }

  void validateNormalizedScope(String expected, Map<String, Object> binding) {
    String stored = s(binding, "normalized_snapshot");
    scopeNormalization.verify(
        expected,
        new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Stored(
            stored, () -> s(binding, "normalized_digest"), () -> n(binding, "normalized_bytes")),
        new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Proof() {
          public boolean sameJson(String left, String right) {
            return contracts.json(left, 4096).equals(contracts.json(right, 4096));
          }

          public String hash(String raw) {
            return Payloads.hash(raw);
          }

          public int bytes(String raw) {
            return SessionContracts.bytes(raw);
          }
        });
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeReadProofService
      scopeReadProofs =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionScopeReadProofServiceImpl();

  String verifyScopeRead(Map<String, Object> task, Map<String, Object> event, JsonNode value) {
    return scopeReadProofs.verify(new MapperSessionScopeReadProofs(this, task, event, value));
  }

  List<Map<String, Object>> scopeProofEndpoints(String tenant, String session, long from, long to) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession()
          .getMapper(
              io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                  .SessionScopeReadProofMapper.class)
          .endpoints(query, tenant, session, from, to);
    }
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeExecutionService
      scopeExecution =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionScopeExecutionServiceImpl();

  java.util.function.LongConsumer queryOperations() {
    return query == null ? count -> {} : query::operation;
  }

  io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Budget priorBudget(
      Runnable deadline) {
    searchBudget = new SearchBudget(deadline, false, 16777216, true);
    return () -> searchBudget = null;
  }

  List<Map<String, Object>> priorScan(String tenant, String session, long upper) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession()
          .getMapper(
              io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionPriorScanMapper
                  .class)
          .scan(query, tenant, session, upper);
    }
  }

  List<Map<String, Object>> windowRows(Map<String, Object> task, long from, long to) {
    return bindingRows(
        io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper
            .class,
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                    .ScopeQueryNativeEntity>()
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTenantId,
                s(task, "tenant_id"))
            .eq(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getTaskId,
                s(task, "id"))
            .between(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq,
                from,
                to)
            .orderByAsc(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                        .ScopeQueryNativeEntity
                    ::getSeq)
            .last("LIMIT 9"));
  }

  Search priorSearch(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort.Input input,
      Runnable deadline) {
    var result =
        scopeExecution.prior(new MapperSessionScopeExecution(this, task, deadline).prior(input));
    return new Search(result.output(), result.scanDigest(), result.rows(), result.eventBytes());
  }

  Read scopeRead(Map<String, Object> caller, String normalized, Runnable deadline) {
    var result =
        scopeExecution.read(
            new MapperSessionScopeExecution(this, caller, deadline).read(normalized));
    return new Read(
        result.output(),
        result.from(),
        result.to(),
        result.targetDigest(),
        result.sources(),
        result.eventBytes());
  }

  record Search(String output, String scanDigest, int rows, int eventBytes) {}

  io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeSearchExecution.Budget
      nativeSearchBudget(Runnable deadline) {
    searchBudget = new SearchBudget(deadline);
    return () -> searchBudget = null;
  }

  List<Map<String, Object>> nativeSearchScan(String tenant, String session, long upper) {
    if (searchBudget != null && query == null)
      throw new IllegalStateException("Session search requires an explicit query context");
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession()
          .getMapper(
              io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                  .SessionNativeSearchScanMapper.class)
          .scan(query, tenant, session, upper);
    }
  }

  Search search(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    var result =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl
                .SessionNativeSearchExecutionServiceImpl()
            .search(
                new MapperSessionNativeSearchExecution(
                    this, task, input, upper, boundary, deadline));
    return new Search(result.output(), result.scanDigest(), result.count(), result.eventBytes());
  }
}
