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
import java.util.ArrayList;
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

  Map<String, Object> history(Map<String, Object> task, boolean readable) {
    var found = mapperRows("SessionHistoryRetentionMapper.history", task);
    if (found.isEmpty()) throw new TaskFailure(409, "SESSION_HISTORY_UNAVAILABLE");
    var h = found.getFirst();
    if (n(h, "record_version") != 1
        || n(h, "history_version") != version(task)
        || !contract(task).binding().equals(s(h, "binding_snapshot"))
        || !Payloads.hash(contract(task).binding()).equals(s(h, "binding_digest")))
      throw new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    if (readable && (!"PRESENT".equals(s(h, "content_state")) || n(h, "retained") != 1))
      throw new TaskFailure(410, "SESSION_HISTORY_EXPIRED");
    return h;
  }

  void create(Map<String, Object> task) {
    String tenant = s(task, "tenant_id"), id = s(task, "id"), binding = contract(task).binding();
    contracts.publicText(s(task, "goal"), sensitive());
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) SELECT tenant_id,id,?,?,?,TIMESTAMPADD(SECOND,604800,created_at) FROM task WHERE tenant_id=? AND id=?",
        version(task),
        binding,
        Payloads.hash(binding),
        tenant,
        id);
    body(
        task,
        SessionContracts.bytes(s(task, "goal"))
            + SessionContracts.bytes(s(task, "request_snapshot")));
  }

  void body(Map<String, Object> task, long additional) {
    if (!task(task)) return;
    if (db.update(
            "UPDATE task_session_history SET body_bytes=body_bytes+? WHERE tenant_id=? AND task_id=? AND content_state='PRESENT' AND body_bytes+? BETWEEN 0 AND 7340032",
            additional,
            s(task, "tenant_id"),
            s(task, "id"),
            additional)
        != 1) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
  }

  private org.mybatis.spring.SqlSessionTemplate mapperSession() {
    if (mapperSession == null)
      mapperSession =
          io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
              .create(java.util.Objects.requireNonNull(db.getDataSource()));
    return mapperSession;
  }

  private List<Map<String, Object>> mapperRows(String operation, Map<String, Object> task) {
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
    var h = history(task, !type.equals("turn.ended"));
    var prior =
        rows(
            "SELECT seq FROM session_public_event WHERE tenant_id=? AND task_id=? AND fact_key=?",
            s(task, "tenant_id"),
            s(task, "id"),
            fact);
    if (!prior.isEmpty()) return n(prior.getFirst(), "seq");
    long seq = n(h, "next_seq");
    if (seq >= (type.equals("turn.ended") ? 128 : 126))
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    LocalDateTime time = db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    var value = new LinkedHashMap<String, Object>();
    value.put("version", version(task));
    value.put("session_id", s(task, "id"));
    value.put("seq", seq);
    value.put("type", type);
    value.put("time", UTC.format(time));
    value.put("source", source);
    value.put("content_policy", "public-v1");
    value.put("redacted", redacted);
    value.put("data", data);
    if (JdbcSessionScopes.task(task))
      value.put(
          "source_event_refs",
          type.equals("session.read")
              ? scopeReadRefs(task, source.get("invocation_id").toString())
              : List.of());
    if (traceTask(task))
      value.put(
          "source_event_seqs",
          type.equals("session.read")
              ? readSources(task, source.get("invocation_id").toString()).stream()
                  .map(v -> v.path("seq").asLong())
                  .toList()
              : List.of());
    String raw = Payloads.json(value);
    contract(task).event(raw);
    body(task, SessionContracts.bytes(raw));
    db.update(
        "INSERT INTO session_public_event(tenant_id,task_id,event_version,seq,fact_key,type,occurred_at,event_snapshot,event_digest,event_bytes,source_digest,source_step_id,source_invocation_id,committed_attempt_id,committed_fence,reliable_event_seq) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        s(task, "tenant_id"),
        s(task, "id"),
        version(task),
        seq,
        fact,
        type,
        time,
        raw,
        Payloads.hash(raw),
        SessionContracts.bytes(raw),
        sourceDigest,
        source.get("step_id"),
        source.get("invocation_id"),
        lease == null ? null : lease.attemptId(),
        lease == null ? null : lease.fence(),
        reliable);
    db.update(
        "UPDATE task_session_history SET next_seq=next_seq+1 WHERE tenant_id=? AND task_id=?",
        s(task, "tenant_id"),
        s(task, "id"));
    return seq;
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

  void prepare(Map<String, Object> task, String request, String step) {
    var wire = Payloads.read(request, DurableAgent.Checkpoint.class);
    if (SessionContracts.bytes(request) > 131072
        || SessionContracts.bytes(wire.requestBody()) > 65536)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    if (wire.messages().isEmpty()
        || !wire.messages().getLast().role().equals("user")
        || !wire.messages().getLast().content().equals(context(task)))
      throw new TaskFailure(409, "SESSION_BOUNDARY_CONFLICT");
    var h = history(task, true);
    db.update(
        "UPDATE step SET session_upper_seq=?,session_context_digest=?,session_binding_digest=? WHERE tenant_id=? AND id=?",
        n(h, "next_seq") - 1,
        Payloads.hash(wire.messages().getLast().content()),
        s(h, "binding_digest"),
        s(task, "tenant_id"),
        step);
    if (JdbcSessionScopes.task(task)) {
      String context = wire.messages().getLast().content();
      var scope = scopes.require(task);
      db.update(
          "INSERT INTO step_session_scope(tenant_id,step_id,caller_task_id,authority_digest,context_snapshot,context_digest,context_bytes) VALUES(?,?,?,?,?,?,?)",
          s(task, "tenant_id"),
          step,
          s(task, "id"),
          s(scope.row(), "authority_digest"),
          context,
          Payloads.hash(context),
          SessionContracts.bytes(context));
      body(task, SessionContracts.bytes(context));
    }
    body(task, SessionContracts.bytes(request));
  }

  void bindCalls(Map<String, Object> task, String step) {
    var h = history(task, true);
    var st =
        one(
            "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=? AND (NOT ? OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
            s(task, "tenant_id"),
            step,
            s(task, "id"),
            searchBudget != null && searchBudget.prior);
    if (st.get("session_upper_seq") == null
        || !s(h, "binding_digest").equals(s(st, "session_binding_digest"))) throw corrupt();
    for (var i :
        rows(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND (NOT ? OR (OCTET_LENGTH(input_snapshot)<=4096 AND COALESCE(OCTET_LENGTH(output_snapshot),0)<=32768)) ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"),
            step,
            searchBudget != null && searchBudget.prior)) {
      if (JdbcSessionScopes.task(task)) {
        bindScopeCall(task, st, i);
        continue;
      }
      if (s(i, "tool").equals("session_event_trace")) {
        if (!traceTask(task)) throw corrupt();
        var input = trace.input(s(i, "input_snapshot"));
        db.update(
            "INSERT INTO invocation_session_trace(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,target_seq,relation_policy) VALUES(?,?,?,?,?,?,?,?,?)",
            s(task, "tenant_id"),
            s(i, "id"),
            s(task, "id"),
            step,
            s(h, "binding_digest"),
            s(i, "input_digest"),
            n(st, "session_upper_seq"),
            input.seq(),
            io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts.POLICY);
        continue;
      }
      if (s(i, "tool").equals("session_event_search")) {
        if (!searchTask(task)) throw corrupt();
        var input = searchContract(task).input(s(i, "input_snapshot"));
        String normalized =
            searchContract(task).normalized(input, s(task, "id"), n(st, "session_upper_seq"));
        body(task, SessionContracts.bytes(normalized));
        db.update(
            "INSERT INTO invocation_session_search(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,normalized_query_snapshot,normalized_query_digest,normalized_query_bytes) VALUES(?,?,?,?,?,?,?,?,?,?)",
            s(task, "tenant_id"),
            s(i, "id"),
            s(task, "id"),
            step,
            s(h, "binding_digest"),
            s(i, "input_digest"),
            n(st, "session_upper_seq"),
            normalized,
            Payloads.hash(normalized),
            SessionContracts.bytes(normalized));
        continue;
      }
      var input = contracts.input(s(i, "input_snapshot"));
      db.update(
          "INSERT INTO invocation_session_read(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,target_seq,before_count,after_count,relation_version) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          s(task, "tenant_id"),
          s(i, "id"),
          s(task, "id"),
          step,
          s(h, "binding_digest"),
          s(i, "input_digest"),
          n(st, "session_upper_seq"),
          input.seq(),
          input.before(),
          input.after(),
          traceTask(task) ? 1 : 0);
    }
  }

  void validateStep(Map<String, Object> task, Map<String, Object> st) {
    if (n(st, "record_version") != 1
        || !s(task, "id").equals(s(st, "task_id"))
        || st.get("session_upper_seq") == null)
      throw new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    String request = s(st, "request_snapshot");
    if (!Payloads.hash(request).equals(s(st, "request_digest"))) throw corrupt();
    var wire = Payloads.read(request, DurableAgent.Checkpoint.class);
    if (wire.version() != 2 || wire.messages().isEmpty()) throw corrupt();
    String context = wire.messages().getLast().content();
    contract(task).context(context);
    var value = contracts.json(context, 4096);
    if (!wire.messages().getLast().role().equals("user")
        || !s(task, "id").equals(value.path("session_id").asText())
        || !Payloads.hash(context).equals(s(st, "session_context_digest"))
        || n(st, "session_upper_seq") != value.path("upper_seq").asLong()
        || !s(history(task, true), "binding_digest").equals(s(st, "session_binding_digest")))
      throw corrupt();
  }

  void response(
      Map<String, Object> task,
      String response,
      List<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call> calls) {
    // Include future slot bindings/reservations/receipts before M2 accepts a response. A capacity
    // rejection is caught by M2's known-usage settlement path, rather than rolling it back later.
    metadata(task, false, 2048L + calls.size() * 4096L);
    if (SessionContracts.bytes(response) > 16384 || calls.size() > 4)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    var parsed = Payloads.read(response, DurableAgent.Response.class);
    if (parsed.version() != 2 || parsed.calls().size() != calls.size()) throw corrupt();
    for (int index = 0; index < calls.size(); index++) {
      var call = calls.get(index);
      var wire = parsed.calls().get(index);
      if (!new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                  index + ":" + wire.id(), wire.id(), wire.name(), wire.arguments())
              .equals(call)
          || !"function".equals(wire.type())
          || !(SessionContracts.NAME.equals(call.tool())
              || JdbcSessionScopes.task(task) && call.tool().equals("session_search")
              || searchTask(task) && call.tool().equals("session_event_search")
              || traceTask(task) && call.tool().equals("session_event_trace")))
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
      contracts.callId(call.providerId(), sensitive());
      validateInput(task, call);
    }
    // Validate the complete projected event before admitting any response or tool slots.
    var text = contracts.publicText(parsed.content(), sensitive());
    var preview = new LinkedHashMap<String, Object>();
    preview.put("version", version(task));
    preview.put("session_id", s(task, "id"));
    preview.put("seq", 127);
    preview.put("type", "model.response");
    preview.put("time", "2026-10-23T00:00:00.000000Z");
    preview.put("source", source("model", "00000000-0000-4000-8000-000000000000", null));
    preview.put("content_policy", "public-v1");
    var projected = projectedCalls(task, parsed.calls());
    preview.put("redacted", text.redacted() || projected.redacted());
    preview.put("data", Map.of("content", text.content(), "calls", projected.calls()));
    if (traceTask(task)) preview.put("source_event_seqs", List.of());
    if (JdbcSessionScopes.task(task)) preview.put("source_event_refs", List.of());
    contract(task).event(Payloads.json(preview));
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
    history(task, true);
    if (input.sessionId() != null && !input.sessionId().equals(s(task, "id"))
        || input.seq() > upper) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    long from = Math.max(0, input.seq() - input.before()),
        to = Math.min(upper, input.seq() + input.after());
    var records =
        rows(
            "SELECT * FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq BETWEEN ? AND ? ORDER BY seq LIMIT 9",
            s(task, "tenant_id"),
            s(task, "id"),
            from,
            to);
    if (records.size() != to - from + 1) throw corrupt();
    var before = new ArrayList<Map<String, Object>>();
    var after = new ArrayList<Map<String, Object>>();
    JsonNode target = null;
    String targetDigest = null;
    var sources = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < records.size(); index++) {
      var r = records.get(index);
      if (n(r, "seq") != from + index) throw corrupt();
      var value = verify(task, r);
      sources.add(
          JdbcSessionScopes.task(task)
              ? scopeRef(s(task, "id"), n(r, "seq"), s(r, "event_digest"))
              : Map.of("seq", n(r, "seq"), "event_sha256", s(r, "event_digest")));
      if (n(r, "seq") == input.seq()) {
        target = value;
        targetDigest = s(r, "event_digest");
      } else {
        (n(r, "seq") < input.seq() ? before : after).add(readSummary(value));
      }
    }
    if (target == null) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var value = new LinkedHashMap<String, Object>();
    value.put("version", 1);
    value.put("session_id", s(task, "id"));
    value.put("history_version", version(task));
    value.put("content_policy", "public-v1");
    value.put("boundary", boundary);
    value.put("upper_seq", upper);
    value.put(
        "window",
        Map.of(
            "from_seq",
            from,
            "to_seq",
            to,
            "before_requested",
            input.before(),
            "after_requested",
            input.after()));
    value.put("target", target);
    value.put("before", before);
    value.put("after", after);
    String raw = Payloads.json(value);
    contract(task).output(raw);
    return new Read(
        raw,
        from,
        to,
        targetDigest,
        Payloads.json(sources),
        records.stream().mapToInt(r -> Math.toIntExact(n(r, "event_bytes"))).sum());
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

  List<JsonNode> readSources(Map<String, Object> task, String invocation) {
    var binding =
        one(
            "SELECT * FROM invocation_session_read WHERE tenant_id=? AND task_id=? AND invocation_id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            invocation);
    String raw = s(binding, "read_sources_snapshot");
    if (n(binding, "relation_version") != 1
        || !"PRESENT".equals(s(binding, "content_state"))
        || raw == null
        || SessionContracts.bytes(raw) > 1024
        || SessionContracts.bytes(raw) != n(binding, "read_sources_bytes")
        || !Payloads.hash(raw).equals(s(binding, "read_sources_digest"))) throw corrupt();
    var sources = contracts.json(raw, 1024);
    if (!sources.isArray() || sources.size() > 9) throw corrupt();
    var result = new ArrayList<JsonNode>();
    long previous = -1;
    for (var source : sources) {
      if (!source.isObject()
          || source.size() != 2
          || !source.path("seq").isIntegralNumber()
          || source.path("seq").asLong() <= previous
          || source.path("seq").asLong() > 127
          || !source.path("event_sha256").asText().matches("[0-9a-f]{64}")) throw corrupt();
      previous = source.path("seq").asLong();
      result.add(source);
    }
    return List.copyOf(result);
  }

  // Flat proof: check the original slot and immediate endpoints, never verify nested read outputs.
  void verifyReadRelation(
      Map<String, Object> task,
      Map<String, Object> r,
      JsonNode event,
      Map<String, Object> i,
      Map<String, Object> b) {
    var sources = readSources(task, s(i, "id"));
    if (!Payloads.json(sources.stream().map(v -> v.path("seq").asLong()).toList())
        .equals(Payloads.json(event.path("source_event_seqs")))) throw corrupt();
    var st =
        one(
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=? AND (NOT ? OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
            s(task, "tenant_id"),
            s(task, "id"),
            s(i, "step_id"),
            searchBudget != null && searchBudget.prior);
    validateStep(task, st);
    if (!s(history(task, true), "binding_digest").equals(s(b, "binding_digest"))
        || n(st, "session_upper_seq") != n(b, "upper_seq")
        || !Payloads.hash(s(i, "input_snapshot")).equals(s(i, "input_digest"))
        || !s(i, "input_digest").equals(s(b, "input_digest"))
        || !"session_event_read".equals(s(i, "tool"))) throw corrupt();
    var input = contract(task).input(s(i, "input_snapshot"));
    if (input.seq() != n(b, "target_seq")
        || input.before() != n(b, "before_count")
        || input.after() != n(b, "after_count")
        || input.sessionId() != null && !input.sessionId().equals(s(task, "id"))) throw corrupt();
    if (!Payloads.hash(s(st, "response_snapshot")).equals(s(st, "response_digest")))
      throw corrupt();
    var response = Payloads.read(s(st, "response_snapshot"), DurableAgent.Response.class);
    boolean matched = false;
    for (int index = 0; index < response.calls().size(); index++) {
      var call = response.calls().get(index);
      if ((index + ":" + call.id()).equals(s(i, "slot"))
          && call.id().equals(s(i, "provider_id"))
          && call.name().equals(s(i, "tool"))
          && call.arguments().equals(s(i, "input_snapshot"))) matched = true;
    }
    var pins =
        Payloads.read(
            s(task, "tools_snapshot"),
            io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot.class);
    var pin = pins.pins().get("session_event_read");
    if (!matched
        || response.version() != 2
        || pin == null
        || !pin.version().equals(s(i, "plugin_version"))
        || !pin.digest().equals(s(i, "plugin_digest"))
        || pin.installationEpoch() != n(i, "installation_epoch")) throw corrupt();
    if ("FAILED".equals(s(b, "outcome"))) {
      if (!sources.isEmpty() || i.get("output_snapshot") != null || n(b, "output_bytes") != 0)
        throw corrupt();
      return;
    }
    if (!"SUCCEEDED".equals(s(b, "outcome")) || sources.isEmpty()) throw corrupt();
    String raw = s(i, "output_snapshot");
    contract(task).output(raw);
    var output = contracts.json(raw, 32768);
    long from = Math.max(0, input.seq() - input.before()),
        to = Math.min(n(b, "upper_seq"), input.seq() + input.after());
    if (sources.size() != to - from + 1
        || n(b, "window_start") != from
        || n(b, "window_end") != to
        || output.path("upper_seq").asLong() != n(b, "upper_seq")
        || !"pre-step".equals(output.path("boundary").asText())
        || !s(task, "id").equals(output.path("session_id").asText())
        || output.path("target").path("seq").asLong() != input.seq()
        || output.path("window").path("from_seq").asLong() != from
        || output.path("window").path("to_seq").asLong() != to
        || output.path("before").size() != input.seq() - from
        || output.path("after").size() != to - input.seq()) throw corrupt();
    int index = 0;
    for (var source : sources) {
      long seq = source.path("seq").asLong();
      if (seq != from + index++ || seq > n(b, "upper_seq") || seq >= n(r, "seq")) throw corrupt();
      var endpoint =
          one(
              "SELECT event_digest FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq=? AND content_state='PRESENT'",
              s(task, "tenant_id"),
              s(task, "id"),
              seq);
      if (!s(endpoint, "event_digest").equals(source.path("event_sha256").asText()))
        throw corrupt();
      if (seq == input.seq()
          && (!s(endpoint, "event_digest").equals(s(b, "target_digest"))
              || !Payloads.hash(Payloads.json(output.path("target")))
                  .equals(s(b, "target_digest")))) throw corrupt();
    }
  }

  record Trace(
      String output,
      String scanDigest,
      String relationsDigest,
      String targetDigest,
      int rows,
      int eventBytes,
      int edges) {}

  Trace trace(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    var h = history(task, true);
    if (!traceTask(task)) throw new TaskFailure(409, "SESSION_TRACE_UNAVAILABLE");
    if (input.sessionId() != null && !input.sessionId().equals(s(task, "id"))
        || input.seq() > upper) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    if (upper < 0 || upper >= 128 || n(h, "next_seq") > 128 || upper >= n(h, "next_seq"))
      throw corrupt();
    if (rows(
            "SELECT seq FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq=?",
            s(task, "tenant_id"),
            s(task, "id"),
            input.seq())
        .isEmpty()) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var budget = new SearchBudget(deadline, true);
    searchBudget = budget;
    try {
      var records =
          rows(
              "SELECT * FROM session_public_event FORCE INDEX (PRIMARY) WHERE tenant_id=? AND task_id=? AND seq BETWEEN 0 AND ? ORDER BY seq LIMIT 128",
              s(task, "tenant_id"),
              s(task, "id"),
              upper);
      if (records.size() != upper + 1) throw corrupt();
      var scanned = new ArrayList<Map<String, Object>>();
      var relations = new ArrayList<Map<String, Object>>();
      var derived = new ArrayList<Long>();
      JsonNode target = null;
      String targetDigest = null;
      int eventBytes = 0, edges = 0, receipts = 0;
      long operations = 0;
      for (int index = 0; index < records.size(); index++) {
        deadline.run();
        var r = records.get(index);
        if (n(r, "seq") != index) throw corrupt();
        eventBytes += SessionContracts.bytes(s(r, "event_snapshot"));
        if (eventBytes > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
        var value = verify(task, r);
        scanned.add(Map.of("seq", index, "event_sha256", s(r, "event_digest")));
        if (index == input.seq()) {
          target = value;
          targetDigest = s(r, "event_digest");
        }
        if ("session.read".equals(s(r, "type"))) {
          var b =
              one(
                  "SELECT read_sources_digest,outcome FROM invocation_session_read WHERE tenant_id=? AND task_id=? AND invocation_id=?",
                  s(task, "tenant_id"),
                  s(task, "id"),
                  s(r, "source_invocation_id"));
          relations.add(Map.of("seq", index, "read_sources_sha256", s(b, "read_sources_digest")));
          if ("SUCCEEDED".equals(s(b, "outcome")) && ++receipts > 20)
            throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          for (var source : value.path("source_event_seqs")) {
            operations++;
            if (++edges > 180) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
            long seq = source.asLong();
            if (seq < 0 || seq >= index || seq >= records.size()) throw corrupt();
            if (seq == input.seq()) derived.add((long) index);
          }
        }
        operations += 32L + s(r, "event_snapshot").length();
        budget.operation(
            32L + s(r, "event_snapshot").length() + value.path("source_event_seqs").size());
        if (operations > 1048576) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      }
      if (target == null) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
      if (derived.size() > 20) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      var summary = new LinkedHashMap<String, Object>();
      for (String key : List.of("seq", "type", "time", "source", "redacted"))
        summary.put(key, target.get(key));
      String text = target.path("data").path("content").asText(""),
          prefix = SessionContracts.prefix(text);
      summary.put("text", prefix);
      summary.put("summary_truncated", !text.equals(prefix));
      summary.put("event_sha256", targetDigest);
      if (SessionContracts.bytes(Payloads.json(summary)) > 2048)
        throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      var output = new LinkedHashMap<String, Object>();
      output.put("version", 1);
      output.put("session_id", s(task, "id"));
      output.put("history_version", 3);
      output.put("content_policy", "public-v1");
      output.put("boundary", boundary);
      output.put("upper_seq", upper);
      output.put(
          "relation_policy",
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts.POLICY);
      output.put("replacement_policy", "unsupported");
      output.put("target", summary);
      output.put("source_event_seqs", target.path("source_event_seqs"));
      output.put("derived_event_seqs", derived);
      String raw = Payloads.json(output);
      trace.output(raw);
      deadline.run();
      return new Trace(
          raw,
          Payloads.hash(Payloads.json(scanned)),
          Payloads.hash(Payloads.json(relations)),
          targetDigest,
          records.size(),
          eventBytes,
          edges);
    } finally {
      searchBudget = null;
    }
  }

  Map<String, Object> receipt(Map<String, Object> b) {
    var value = new LinkedHashMap<String, Object>();
    value.put("seq", n(b, "target_seq"));
    value.put("before", n(b, "before_count"));
    value.put("after", n(b, "after_count"));
    value.put("upper_seq", n(b, "upper_seq"));
    value.put("outcome", s(b, "outcome"));
    value.put("code", s(b, "failure_code"));
    value.put("output_sha256", s(b, "output_digest"));
    value.put("output_bytes", n(b, "output_bytes"));
    return value;
  }

  void validateInput(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call call) {
    if (JdbcSessionScopes.task(task)) {
      if (call.tool().equals("session_search")) {
        var input = scopes.contracts.input(call.input());
        var scope = scopes.require(task);
        for (String id : input.sessionIds())
          if (scope.sources().stream().noneMatch(t -> id.equals(s(t, "id"))))
            throw JdbcSessionScopes.unavailable();
      } else if (call.tool().equals("session_event_read")) {
        var input = v4.input(call.input());
        if (input.sessionId() != null
            && !input.sessionId().equals(s(task, "id"))
            && scopes.require(task).sources().stream()
                .noneMatch(t -> input.sessionId().equals(s(t, "id"))))
          throw JdbcSessionScopes.unavailable();
      } else throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    } else if (call.tool().equals("session_event_trace") && traceTask(task)) {
      var input = trace.input(call.input());
      if (input.sessionId() != null && !input.sessionId().equals(s(task, "id")))
        throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    } else if (call.tool().equals("session_event_search") && searchTask(task)) {
      var input = searchContract(task).input(call.input());
      if (searchTask(task) && input.sessionId() != null && !input.sessionId().equals(s(task, "id")))
        throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    } else if (call.tool().equals(SessionContracts.NAME)) {
      var input = contracts.input(call.input());
      if (searchTask(task) && input.sessionId() != null && !input.sessionId().equals(s(task, "id")))
        throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    } else throw new TaskFailure(400, "TOOL_INPUT_INVALID");
  }

  record PublicCalls(List<DurableAgent.ToolCall> calls, boolean redacted) {}

  PublicCalls projectedCalls(Map<String, Object> task, List<DurableAgent.ToolCall> calls) {
    var projected = new ArrayList<DurableAgent.ToolCall>();
    boolean redacted = false;
    for (var call : calls) {
      String arguments = call.arguments();
      if (searchTask(task) && call.name().equals("session_event_search")
          || JdbcSessionScopes.task(task) && call.name().equals("session_search")) {
        var node =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                contracts.strictJson(arguments, JdbcSessionScopes.task(task) ? 4096 : 2048);
        var text = contracts.publicText(node.path("query").textValue(), sensitive());
        redacted |= text.redacted();
        node.put("query", text.content());
        arguments = Payloads.json(node);
        if (SessionContracts.bytes(arguments) > (JdbcSessionScopes.task(task) ? 4096 : 2048))
          throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      }
      projected.add(new DurableAgent.ToolCall(call.id(), call.type(), call.name(), arguments));
    }
    return new PublicCalls(List.copyOf(projected), redacted);
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
    if (name.equals("session_search")) {
      var input = scopes.contracts.input(raw);
      var value = (com.fasterxml.jackson.databind.node.ObjectNode) contracts.strictJson(raw, 4096);
      value.put("query", input.query());
      // Freeze only canonical validated inputs; target list/cuts are bound separately.
      var sorted = new java.util.TreeMap<String, JsonNode>();
      value.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
      return Payloads.json(sorted);
    }
    var input = v4.input(raw);
    String target = input.sessionId() == null ? s(task, "id") : input.sessionId();
    long upper = n(step, "session_upper_seq");
    var scope = scopes.require(task);
    if (!target.equals(s(task, "id"))) {
      var found =
          java.util.stream.StreamSupport.stream(
                  scope.authority().path("targets").spliterator(), false)
              .filter(t -> target.equals(t.path("session_id").asText()))
              .findFirst()
              .orElseThrow(JdbcSessionScopes::unavailable);
      upper = found.path("upper_seq").asLong();
    }
    return Payloads.json(
        Map.of(
            "target_session_id",
            target,
            "seq",
            input.seq(),
            "before",
            input.before(),
            "after",
            input.after(),
            "upper_seq",
            upper));
  }

  private void bindScopeCall(
      Map<String, Object> task, Map<String, Object> step, Map<String, Object> invocation) {
    var scope = scopes.require(task);
    String raw =
        normalizedScope(task, step, s(invocation, "tool"), s(invocation, "input_snapshot"));
    var cut =
        one(
            "SELECT * FROM step_session_scope WHERE tenant_id=? AND step_id=? AND caller_task_id=?",
            s(task, "tenant_id"),
            s(step, "id"),
            s(task, "id"));
    if (!s(scope.row(), "authority_digest").equals(s(cut, "authority_digest"))
        || !s(step, "session_context_digest").equals(s(cut, "context_digest"))) throw corrupt();
    db.update(
        "INSERT INTO invocation_session_scope_query(tenant_id,invocation_id,caller_task_id,step_id,operation,binding_digest,input_digest,authority_digest,context_digest,normalized_snapshot,normalized_digest,normalized_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        s(task, "tenant_id"),
        s(invocation, "id"),
        s(task, "id"),
        s(step, "id"),
        s(invocation, "tool").equals("session_search") ? "SEARCH" : "READ",
        s(step, "session_binding_digest"),
        s(invocation, "input_digest"),
        s(scope.row(), "authority_digest"),
        s(cut, "context_digest"),
        raw,
        Payloads.hash(raw),
        SessionContracts.bytes(raw));
    body(task, SessionContracts.bytes(raw));
  }

  List<JsonNode> scopeReadRefs(Map<String, Object> task, String invocation) {
    var b =
        one(
            "SELECT * FROM invocation_session_scope_query WHERE tenant_id=? AND invocation_id=? AND caller_task_id=?",
            s(task, "tenant_id"),
            invocation,
            s(task, "id"));
    if (!"READ".equals(s(b, "operation"))
        || !List.of("SUCCEEDED", "FAILED").contains(s(b, "outcome"))) throw corrupt();
    String raw = s(b, "read_refs_snapshot");
    scopes.contracts.refs(raw);
    if (SessionContracts.bytes(raw) != n(b, "read_refs_bytes")
        || !Payloads.hash(raw).equals(s(b, "read_refs_digest"))) throw corrupt();
    var result = new ArrayList<JsonNode>();
    contracts.json(raw, 2048).forEach(result::add);
    return List.copyOf(result);
  }

  Map<String, Object> scopeReceipt(Map<String, Object> b) {
    var input = contracts.json(s(b, "normalized_snapshot"), 4096);
    var result = new LinkedHashMap<String, Object>();
    for (String key : List.of("seq", "before", "after", "upper_seq", "target_session_id"))
      result.put(key, input.get(key));
    result.put("outcome", s(b, "outcome"));
    result.put("code", s(b, "failure_code"));
    result.put("output_sha256", s(b, "output_digest"));
    result.put("output_bytes", n(b, "output_bytes"));
    return result;
  }

  void validateNormalizedScope(String expected, Map<String, Object> binding) {
    String stored = s(binding, "normalized_snapshot");
    // 保留原槽快照及其字节证明；Map.of 的跨 JVM 字段顺序不属于输入语义。
    if (!contracts.json(expected, 4096).equals(contracts.json(stored, 4096))
        || !Payloads.hash(stored).equals(s(binding, "normalized_digest"))
        || SessionContracts.bytes(stored) != n(binding, "normalized_bytes")) throw corrupt();
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

  Search priorSearch(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort.Input input,
      Runnable deadline) {
    var scope = scopes.require(task);
    for (String id : input.sessionIds())
      if (scope.sources().stream().noneMatch(source -> id.equals(s(source, "id"))))
        throw JdbcSessionScopes.unavailable();
    var matcher =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionMatcher(
            input, deadline, query == null ? count -> {} : query::operation);
    var scanned = new ArrayList<Map<String, Object>>();
    int eventBytes = 0;
    searchBudget = new SearchBudget(deadline, false, 16777216, true);
    try {
      for (int sourceIndex = 0; sourceIndex < scope.sources().size(); sourceIndex++) {
        var source = scope.sources().get(sourceIndex);
        var target = scope.authority().path("targets").get(sourceIndex);
        long upper = target.path("upper_seq").asLong();
        var records =
            rows(
                "SELECT * FROM session_public_event FORCE INDEX(PRIMARY) WHERE tenant_id=? AND task_id=? AND seq BETWEEN 0 AND ? AND event_bytes BETWEEN 1 AND 16384 AND OCTET_LENGTH(event_snapshot)=event_bytes ORDER BY seq LIMIT 128",
                s(task, "tenant_id"),
                s(source, "id"),
                upper);
        if (records.size() != upper + 1) throw corrupt();
        for (int index = 0; index < records.size(); index++) {
          deadline.run();
          var record = records.get(index);
          if (n(record, "seq") != index || scanned.size() >= 1024) throw corrupt();
          int bytes = SessionContracts.bytes(s(record, "event_snapshot"));
          if (bytes > 16384 || bytes != n(record, "event_bytes")) throw corrupt();
          eventBytes += bytes;
          if (eventBytes > 4194304) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          var value = verify(source, record);
          scanned.add(scopeRef(s(source, "id"), index, s(record, "event_digest")));
          matcher.accept(value, s(record, "event_digest"), target);
        }
      }
      String output = matcher.output(s(task, "id"), s(scope.row(), "authority_digest"));
      scopes.contracts.output(output);
      return new Search(output, Payloads.hash(Payloads.json(scanned)), scanned.size(), eventBytes);
    } finally {
      searchBudget = null;
    }
  }

  Read scopeRead(Map<String, Object> caller, String normalized, Runnable deadline) {
    var scope = scopes.require(caller);
    var value = contracts.json(normalized, 4096);
    String id = value.path("target_session_id").asText();
    var target =
        id.equals(s(caller, "id"))
            ? caller
            : scope.sources().stream()
                .filter(source -> id.equals(s(source, "id")))
                .findFirst()
                .orElseThrow(JdbcSessionScopes::unavailable);
    searchBudget = new SearchBudget(deadline, false, 16777216, true);
    try {
      return readRows(
          target,
          new SessionEventPort.Input(
              value.path("seq").asLong(),
              id,
              value.path("before").asInt(),
              value.path("after").asInt()),
          value.path("upper_seq").asLong(),
          id.equals(s(caller, "id")) ? "pre-step" : "scoped-admission");
    } finally {
      searchBudget = null;
    }
  }

  record Search(String output, String scanDigest, int rows, int eventBytes) {}

  Search search(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    var h = history(task, true);
    if (!searchTask(task)) throw new TaskFailure(409, "SESSION_SEARCH_UNAVAILABLE");
    searchContract(task).normalized(input, s(task, "id"), upper);
    var matcher =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchMatcher(
            input, deadline);
    var scanned = new ArrayList<Map<String, Object>>();
    int eventBytes = 0;
    var budget = new SearchBudget(deadline);
    searchBudget = budget;
    try {
      if ((input.seqFrom() == null ? 0 : input.seqFrom())
          <= Math.min(upper, input.seqTo() == null ? upper : input.seqTo())) {
        if (upper < 0 || upper >= 128 || upper >= n(h, "next_seq")) throw corrupt();
        var records =
            rows(
                "SELECT * FROM session_public_event FORCE INDEX (PRIMARY) WHERE tenant_id=? AND task_id=? AND seq BETWEEN 0 AND ? ORDER BY seq LIMIT 128",
                s(task, "tenant_id"),
                s(task, "id"),
                upper);
        if (records.size() != upper + 1) throw corrupt();
        for (int index = 0; index < records.size(); index++) {
          deadline.run();
          var record = records.get(index);
          if (n(record, "seq") != index) throw corrupt();
          var value = verify(task, record);
          eventBytes += SessionContracts.bytes(s(record, "event_snapshot"));
          if (eventBytes > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          var scan = new LinkedHashMap<String, Object>();
          scan.put("seq", index);
          scan.put("event_sha256", s(record, "event_digest"));
          scanned.add(scan);
          matcher.accept(value, s(record, "event_digest"), upper);
        }
      }
      String output = matcher.output(s(task, "id"), upper, boundary, version(task));
      searchContract(task).output(output);
      return new Search(output, Payloads.hash(Payloads.json(scanned)), scanned.size(), eventBytes);
    } finally {
      searchBudget = null;
    }
  }
}
