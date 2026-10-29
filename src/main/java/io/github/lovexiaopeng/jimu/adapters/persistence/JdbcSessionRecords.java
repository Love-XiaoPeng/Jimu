package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.PriorSessionPolicyV4;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionSearchReadPolicyV2;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionTracePolicyV3;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
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
  final SessionContracts v5 = new SessionContracts(5);
  final io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts lineage =
      new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts();
  JdbcSessionScopes scopes;
  io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords lineageRecords;
  io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService lineageService;
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
    return name.equals(ToolCapability.SESSION_EVENT_READ)
        ? contract(task).descriptor()
        : name.equals(ToolCapability.SESSION_SEARCH)
            ? scopes.contracts(task).descriptor()
            : name.equals(ToolCapability.SESSION_EVENT_SEARCH)
                ? searchContract(task).descriptor()
                : name.equals(ToolCapability.SESSION_TRACE)
                    ? lineage.descriptor()
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
      this(deadline, trace, SessionSearchReadPolicyV2.SCRATCH_BYTES);
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
      if (++reads
          > (prior ? PriorSessionPolicyV4.PROOF_READS : SessionSearchReadPolicyV2.PROOF_READS))
        throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
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
          if (bytes
                  > (prior
                      ? PriorSessionPolicyV4.PROOF_BYTES
                      : SessionSearchReadPolicyV2.PROOF_BYTES)
              || (prior
                          ? batchBytes * PriorSessionPolicyV4.SCRATCH_BATCH_COPIES
                          : bytes
                              * (trace
                                  ? SessionSearchReadPolicyV2.TRACE_COPIES
                                  : SessionSearchReadPolicyV2.SEARCH_COPIES))
                      + SessionSearchReadPolicyV2.SCRATCH_HEADROOM_BYTES
                  > scratchLimit)
            throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
        }
      };
    }

    void operation(long count) {
      operations += count;
      if (trace && operations > 1048576)
        throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
      deadline.run();
    }
  }

  static boolean searchTask(Map<String, Object> task) {
    return traceTask(task)
        || "session_event_read,session_event_search".equals(s(task, "capabilities"));
  }

  int version(Map<String, Object> task) {
    return JdbcSessionScopes.lineage(task)
        ? 5
        : JdbcSessionScopes.task(task) ? 4 : traceTask(task) ? 3 : searchTask(task) ? 2 : 1;
  }

  SessionContracts contract(Map<String, Object> task) {
    return JdbcSessionScopes.lineage(task)
        ? v5
        : JdbcSessionScopes.task(task)
            ? v4
            : traceTask(task) ? v3 : searchTask(task) ? v2 : contracts;
  }

  /** 只共用当前Work内同一不可变本地策略；数据库副本仍独立encode/hash，不构成行证明。 */
  String policyHash(Map<String, Object> task, String raw) {
    if (query == null || !query.lineage()) return Payloads.hash(raw);
    try {
      query.operation(1);
      if (samePolicyIdentity(raw, contract(task).binding())) {
        var owner =
            io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
                query);
        if (owner != null) return owner.policyHash(raw, () -> policyHashRaw(raw));
      }
      return policyHashRaw(raw);
    } catch (RuntimeException failure) {
      throw query.recordFailure(failure);
    }
  }

  /** 只认本地v5不可变binding；foreign行无论内容相同与否均独立编码/散列。 */
  String scopeHash(String raw) {
    var current =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (current != null && current.lineage()) {
      try {
        if (query == null
            || io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                    .current(query)
                != current) throw new IllegalStateException("Different scope query owner");
      } catch (RuntimeException failure) {
        throw current.poisonProof(failure);
      }
    }
    if (query == null || !query.lineage()) return Payloads.hash(raw);
    try {
      query.operation(1);
      if (samePolicyIdentity(raw, v5.binding())) {
        var owner =
            io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
                query);
        if (owner != null) return owner.policyHash(raw, () -> policyHashRaw(raw));
      }
      return policyHashRaw(raw);
    } catch (RuntimeException failure) {
      throw query.recordFailure(failure);
    }
  }

  /** scope原位置的真实UTF8编码；独立临时数组先预留、断引用后退款，不充当hash缓存。 */
  static int scopeUtf8Bytes(
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query, String raw) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (owner != null && owner.lineage()) {
      try {
        if (query == null
            || io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                    .current(query)
                != owner) throw new IllegalStateException("Different scope query owner");
      } catch (RuntimeException failure) {
        throw owner.poisonProof(failure);
      }
    }
    if (query == null || !query.lineage())
      return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    Object temporary = null;
    byte[] encoded = null;
    try {
      query.checkHealthy();
      // null保持旧getBytes的NPE；真实owner将它登记为首错。
      query.operation(4);
      long capacity = 4096L + 4L * raw.length();
      query.retainScratch(query, 64); // 临时owner分配前的固定引用槽。
      temporary = new Object();
      query.retainScratch(temporary, capacity);
      encoded =
          query.primitive(
              "scope.encode",
              raw.length(),
              () -> raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      query.operation(1);
      return encoded.length;
    } catch (RuntimeException failure) {
      throw owner != null && owner.lineage()
          ? owner.poisonProof(failure)
          : query.recordFailure(failure);
    } finally {
      encoded = null;
      if (temporary != null) query.releaseScratch(temporary);
      query.releaseScratch(query);
    }
  }

  private static boolean samePolicyIdentity(Object left, Object right) {
    return left == right;
  }

  private String policyHashRaw(String raw) {
    Object temporary = null;
    byte[] encoded = null;
    try {
      query.retainScratch(query, 64); // reserve the owner reference before constructing it
      temporary = new Object();
      query.retainScratch(temporary, 4096L + 4L * raw.length());
      encoded =
          query.primitive(
              "history.encode",
              raw.length(),
              () -> raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return hashEncoded(encoded);
    } finally {
      encoded = null;
      if (temporary != null) query.releaseScratch(temporary);
      query.releaseScratch(query);
    }
  }

  // The primitive supplier and its byte[] alias expire before the caller clears and refunds it.
  private String hashEncoded(byte[] encoded) {
    return query.primitive("history.hash", encoded.length, () -> Payloads.hash(encoded));
  }

  private List<String> sensitive;
  static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

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

  record Configuration(List<String> sensitive) {}

  Configuration configuration() {
    return new Configuration(sensitive);
  }

  void configure(Configuration configuration) {
    sensitive = configuration.sensitive();
  }

  void sensitive(List<String> values) {
    sensitive = List.copyOf(values);
  }

  List<String> sensitive() {
    if (sensitive == null) throw new TaskFailure(503, SessionErrorCode.SESSION_EVENT_UNSAFE);
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

  static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryProofService
      historyProofs =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionHistoryProofServiceImpl();

  Map<String, Object> history(Map<String, Object> task, boolean readable) {
    return history(task, readable, false);
  }

  Map<String, Object> acceptedEventHistory(Map<String, Object> task, boolean readable) {
    return history(task, readable, version(task) == 5);
  }

  private Map<String, Object> history(
      Map<String, Object> task, boolean readable, boolean acceptedEvent) {
    var owner = historyOwner();
    Object temporary = new Object();
    if (owner != null) query.retainScratch(temporary, 4096);
    try {
      var proof = new MapperSessionHistoryProofs(this, task, null, acceptedEvent);
      historyProofs.history(proof, readable);
      if (readable && owner != null)
        owner.checkedHistory(
            MapperSessionScopeRecords.caller(task),
            version(task),
            contract(task).binding(),
            proof.checkedRow(),
            (java.time.LocalDateTime) proof.historyRow().get("retain_until"));
      return proof.historyRow();
    } catch (RuntimeException failure) {
      if (owner != null) owner.poisonProof(failure);
      throw failure;
    } finally {
      if (owner != null) query.releaseScratch(temporary);
    }
  }

  private io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork historyOwner() {
    return query != null && query.lineage()
        ? io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query)
        : null;
  }

  private boolean checkedHistory(Map<String, Object> task) {
    var owner = historyOwner();
    if (owner == null) return false;
    Object temporary = new Object();
    query.retainScratch(temporary, 4096);
    try {
      return owner.checkedHistory(
          MapperSessionScopeRecords.caller(task), version(task), contract(task).binding());
    } finally {
      query.releaseScratch(temporary);
    }
  }

  void historyProof(Map<String, Object> task) {
    if (!checkedHistory(task)) history(task, false);
  }

  boolean historyRetained(Map<String, Object> task) {
    if (checkedHistory(task)) return true;
    var h = history(task, false);
    return n(h, "retained") == 1 && "PRESENT".equals(s(h, "content_state"));
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

  org.mybatis.spring.SqlSessionTemplate mapperSession() {
    if (mapperSession == null)
      mapperSession =
          io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
              .create(java.util.Objects.requireNonNull(db.getDataSource()));
    return mapperSession;
  }

  List<Map<String, Object>> historyRows(Map<String, Object> task) {
    // Prepay dispatch, guards, mapper/key lookups and handoff, including the fallback path.
    if (query != null) query.operation(12);
    if (query != null && query.lineage() && searchBudget == null)
      return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryRows.load(
          mapperSession(), query, s(task, "tenant_id"), s(task, "id"));
    return mapperRows("SessionHistoryRetentionMapper.history", task);
  }

  List<Map<String, Object>> mapperRows(String operation, Map<String, Object> task) {
    if (operation.equals("SessionHistoryRetentionMapper.eventHistory")
        && query != null
        && query.lineage()
        && searchBudget == null)
      return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionEventHistoryRows.load(
          mapperSession(), query, s(task, "tenant_id"), s(task, "id"));
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

  org.apache.ibatis.cursor.Cursor<Map<String, Object>> mapperCursor(
      String operation, Map<String, Object> task) {
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("tenant", s(task, "tenant_id"));
    args.put("taskId", s(task, "id"));
    try (var ignored = query == null ? null : query.meter(searchBudget)) {
      return mapperSession()
          .selectCursor(
              "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper." + operation, args);
    }
  }

  long metadata(Map<String, Object> task, boolean closing) {
    return metadata(task, closing, 0);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionMetadataAccountingService
      metadataAccounting =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionMetadataAccountingServiceImpl();

  long metadata(Map<String, Object> task, boolean closing, long planned) {
    return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        query,
        "metadata.preflight",
        () ->
            metadataAccounting.metadata(
                new MapperSessionMetadataAccounting(this, task), closing, planned));
  }

  void account(Map<String, Object> task, boolean closing) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            query);
    if (owner != null && query.lineage() && JdbcSessionScopes.lineage(task)) {
      owner.account(
          s(task, "tenant_id") + ":" + s(task, "id"),
          closing,
          finalClosing -> finalAccount(task, finalClosing));
      return;
    }
    io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        query,
        "metadata.account",
        () -> {
          metadataAccounting.account(new MapperSessionMetadataAccounting(this, task), closing);
          return null;
        });
  }

  private void finalAccount(Map<String, Object> task, boolean closing) {
    io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        query,
        "metadata.final",
        () -> {
          metadataAccounting.account(
              new MapperSessionMetadataAccounting(this, task, true), closing);
          return null;
        });
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

  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryViewService historyViews =
      new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryViewServiceImpl();

  io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.MeterScope recentMeter() {
    return query == null ? null : query.meter(searchBudget);
  }

  List<Map<String, Object>> recent(Map<String, Object> task, long upper) {
    return ((MapperSessionRecentEvents.Result)
            historyViews.recent(new MapperSessionRecentEvents(this, task), upper))
        .values();
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

  void updatePreparedStep(
      String tenant,
      String task,
      String step,
      long upper,
      String contextDigest,
      String bindingDigest) {
    if (mapperSession()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                    .ScopeQueryStepReadMapper.class)
            .bindPrepared(query, tenant, task, step, upper, contextDigest, bindingDigest)
        != 1) throw corrupt();
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallBindingService
      callBindings =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionCallBindingServiceImpl();

  void bindCalls(Map<String, Object> task, String step) {
    io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        query,
        "M2.bindCalls",
        () -> {
          callBindings.bind(new MapperSessionCallBindings(this, task, step));
          return null;
        });
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
    return historyViews.metadata(new MapperSessionHistoryViews(this, task));
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
            ? new SearchBudget(deadline, false, PriorSessionPolicyV4.LIVE_SCRATCH_BYTES, true)
            : new SearchBudget(deadline, true, SessionTracePolicyV3.SCRATCH_BYTES);
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
    if (SessionContracts.bytes(Payloads.json(summary)) > SessionTracePolicyV3.SUMMARY_JSON_BYTES)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
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
    if (mapperSession().insert(mapper.getName() + ".insert", args) != 1) throw corrupt();
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
    searchBudget = new SearchBudget(deadline, false, PriorSessionPolicyV4.LIVE_SCRATCH_BYTES, true);
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
