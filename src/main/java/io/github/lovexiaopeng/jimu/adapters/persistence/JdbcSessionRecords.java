package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
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
import java.util.Objects;
import java.util.Set;
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
  private final ThreadLocal<SearchBudget> searchBudget = new ThreadLocal<>();

  private static final class SearchBudget {
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

    void charge(List<Map<String, Object>> rows) {
      deadline.run();
      if (++reads > (prior ? 4096 : 1024)) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      long batchBytes = 0;
      for (var row : rows)
        for (var entry : row.entrySet()) {
          long size = 8L + SessionContracts.bytes(entry.getKey()) + scalarBytes(entry.getValue());
          bytes += size;
          batchBytes += size;
          if (trace) operation(size);
        }
      // Conservatively charge UTF-16 strings/row fields plus the active code point arrays.
      if (bytes > (prior ? 16777216 : 8388608)
          || (prior ? batchBytes * 3 : bytes * (trace ? 3 : 2)) + 524288 > scratchLimit)
        throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
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
  private final Map<String, List<String>> metadataColumns =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private static final Set<String> BODIES =
      Set.of(
          "request_snapshot",
          "goal",
          "summary",
          "response_snapshot",
          "input_snapshot",
          "output_snapshot",
          "usage_snapshot",
          "event_snapshot",
          "normalized_query_snapshot",
          "read_sources_snapshot",
          "authority_snapshot",
          "target_snapshot",
          "source_task_id",
          "context_snapshot",
          "normalized_snapshot",
          "read_refs_snapshot");
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
    this.db = db;
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
    if (searchBudget.get() != null && searchBudget.get().prior) {
      if (sql.startsWith("SELECT * FROM step WHERE"))
        sql +=
            " AND OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384";
      if (sql.startsWith("SELECT * FROM invocation WHERE"))
        sql +=
            " AND OCTET_LENGTH(input_snapshot)<=4096 AND COALESCE(OCTET_LENGTH(output_snapshot),0)<=32768";
    }
    var result = db.queryForList(sql, args);
    if (searchBudget.get() != null) searchBudget.get().charge(result);
    return result;
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
    var found =
        rows(
            "SELECT *,retain_until>CURRENT_TIMESTAMP(6) AS retained FROM task_session_history WHERE tenant_id=? AND task_id=? FOR UPDATE",
            s(task, "tenant_id"),
            s(task, "id"));
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

  private List<String> columns(String table) {
    return metadataColumns.computeIfAbsent(
        table,
        t ->
            db
                .queryForList(
                    "SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                    String.class,
                    t)
                .stream()
                .filter(c -> !BODIES.contains(c))
                .toList());
  }

  long metadata(Map<String, Object> task, boolean closing) {
    return metadata(task, closing, 0);
  }

  long metadata(Map<String, Object> task, boolean closing, long planned) {
    long total = 0;
    long cleanupHeadroom = 1024;
    for (String table : TABLES) {
      String key =
          table.equals("task") ? "id" : table.contains("scope") ? "caller_task_id" : "task_id";
      for (var row :
          rows(
              "SELECT "
                  + String.join(",", columns(table))
                  + " FROM "
                  + table
                  + " WHERE tenant_id=? AND "
                  + key
                  + "=?",
              s(task, "tenant_id"),
              s(task, "id"))) {
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
          db.queryForObject(
              "SELECT metadata_bytes FROM task_session_history WHERE tenant_id=? AND task_id=?",
              Long.class,
              s(task, "tenant_id"),
              s(task, "id"));
      if (old == bytes) return;
      db.update(
          "UPDATE task_session_history SET metadata_bytes=?,held_bytes=IF(content_state='PURGED',?,held_bytes) WHERE tenant_id=? AND task_id=?",
          bytes,
          bytes,
          s(task, "tenant_id"),
          s(task, "id"));
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
    var result = new ArrayList<Map<String, Object>>();
    for (var r :
        rows(
            "SELECT seq,type FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq BETWEEN ? AND ? ORDER BY seq LIMIT 8",
            s(task, "tenant_id"),
            s(task, "id"),
            Math.max(0, upper - 7),
            upper)) result.add(Map.of("seq", n(r, "seq"), "type", s(r, "type")));
    if (result.size() != Math.min(8, upper + 1)) throw corrupt();
    return result;
  }

  String context(Map<String, Object> task) {
    long upper = n(history(task, true), "next_seq") - 1;
    String raw =
        Payloads.json(
            Map.of(
                "kind",
                "session_event_context",
                "version",
                version(task),
                "session_id",
                s(task, "id"),
                "upper_seq",
                upper,
                "user_message_seq",
                0,
                "recent",
                recent(task, upper)));
    if (JdbcSessionScopes.task(task)) {
      var scope = scopes.require(task);
      var node = (com.fasterxml.jackson.databind.node.ObjectNode) contracts.json(raw, 4096);
      node.put("scope_sha256", s(scope.row(), "authority_digest"));
      node.put("retain_until", scope.authority().path("retain_until").asText());
      var prior = node.putArray("prior_sessions");
      for (var target : scope.authority().path("targets"))
        prior
            .addObject()
            .put("session_id", target.path("session_id").asText())
            .put("upper_seq", target.path("upper_seq").asLong());
      raw = Payloads.json(node);
    }
    contract(task).context(raw);
    return raw;
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
            "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
            s(task, "tenant_id"),
            step,
            s(task, "id"));
    if (st.get("session_upper_seq") == null
        || !s(h, "binding_digest").equals(s(st, "session_binding_digest"))) throw corrupt();
    for (var i :
        rows(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"),
            step)) {
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
    searchBudget.set(
        JdbcSessionScopes.task(task)
            ? new SearchBudget(deadline, false, 16777216, true)
            : new SearchBudget(deadline, true, 1048576));
    try {
      return readRows(task, input, upper, boundary);
    } finally {
      searchBudget.remove();
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
        var summary = new LinkedHashMap<String, Object>();
        for (String key : List.of("seq", "type", "time", "source", "redacted"))
          summary.put(key, value.get(key));
        String text = value.path("data").path("content").asText("");
        String prefix = SessionContracts.prefix(text);
        summary.put("text", prefix);
        summary.put("summary_truncated", !prefix.equals(text));
        if (SessionContracts.bytes(Payloads.json(summary)) > 1536)
          throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
        (n(r, "seq") < input.seq() ? before : after).add(summary);
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

  JsonNode verify(Map<String, Object> task, Map<String, Object> r) {
    try {
      return verifyEvent(task, r);
    } catch (TaskFailure failure) {
      if (traceTask(task) && failure.status() == 400) throw corrupt();
      throw failure;
    }
  }

  private JsonNode verifyEvent(Map<String, Object> task, Map<String, Object> r) {
    if (JdbcSessionScopes.task(task))
      io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionScopeQueryBudget.operation(1);
    if (!s(task, "tenant_id").equals(s(r, "tenant_id")) || !s(task, "id").equals(s(r, "task_id")))
      throw corrupt();
    if (n(r, "record_version") != 1 || n(r, "event_version") != version(task))
      throw new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    if (!"PRESENT".equals(s(r, "content_state")))
      throw new TaskFailure(410, "SESSION_HISTORY_EXPIRED");
    String raw = s(r, "event_snapshot");
    JsonNode value;
    try {
      value = contracts.json(raw, 16384);
    } catch (TaskFailure invalid) {
      throw corrupt();
    }
    if (!value.path("version").isIntegralNumber()
        || value.path("version").asLong() != version(task)
        || !List.of("user.message", "step.started", "model.response", "session.read", "turn.ended")
            .contains(value.path("type").asText()))
      throw new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    contract(task).event(raw);
    if (SessionContracts.bytes(raw) != n(r, "event_bytes")
        || !Payloads.hash(raw).equals(s(r, "event_digest"))) throw corrupt();
    var source = value.path("source");
    if (!s(task, "id").equals(value.path("session_id").asText())
        || value.path("seq").asLong() != n(r, "seq")
        || !s(r, "type").equals(value.path("type").asText())
        || !UTC.format((LocalDateTime) r.get("occurred_at")).equals(value.path("time").asText())
        || !Objects.equals(
            s(r, "source_step_id"),
            source.path("step_id").isNull() ? null : source.path("step_id").asText())
        || !Objects.equals(
            s(r, "source_invocation_id"),
            source.path("invocation_id").isNull() ? null : source.path("invocation_id").asText()))
      throw corrupt();
    String digest;
    switch (s(r, "type")) {
      case "user.message" -> {
        if (n(r, "seq") != 0
            || !s(r, "fact_key").equals("accepted-user")
            || !source.equals(
                Payloads.read(Payloads.json(source("request", null, null)), JsonNode.class)))
          throw corrupt();
        digest = s(task, "request_digest");
      }
      case "step.started", "model.response" -> {
        var st =
            one(
                "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                s(task, "tenant_id"),
                s(r, "source_step_id"),
                s(task, "id"));
        boolean model = s(r, "type").equals("model.response");
        if (!s(r, "fact_key").equals((model ? "response:" : "step:") + s(st, "id"))
            || !source.path("kind").asText().equals(model ? "model" : "step")
            || !source.path("invocation_id").isNull()) throw corrupt();
        String snapshot = s(st, model ? "response_snapshot" : "request_snapshot");
        digest = s(st, model ? "response_digest" : "request_digest");
        if (snapshot == null
            || !Payloads.hash(snapshot).equals(digest)
            || !model && value.path("data").path("ordinal").asLong() != n(st, "ordinal"))
          throw corrupt();
      }
      case "session.read" -> {
        if (JdbcSessionScopes.task(task)) {
          digest = verifyScopeRead(task, r, value);
          break;
        }
        var i =
            one(
                "SELECT * FROM invocation WHERE tenant_id=? AND id=? AND task_id=? AND step_id=?",
                s(task, "tenant_id"),
                s(r, "source_invocation_id"),
                s(task, "id"),
                s(r, "source_step_id"));
        var b =
            one(
                "SELECT * FROM invocation_session_read WHERE tenant_id=? AND invocation_id=? AND task_id=? AND step_id=?",
                s(task, "tenant_id"),
                s(i, "id"),
                s(task, "id"),
                s(i, "step_id"));
        if (!s(r, "fact_key").equals("read:" + s(i, "id"))
            || !source.path("kind").asText().equals("invocation")
            || n(b, "receipt_seq") != n(r, "seq")
            || !s(b, "outcome").equals(s(i, "state"))
            || !Payloads.json(receipt(b)).equals(Payloads.json(value.path("data"))))
          throw corrupt();
        if (s(i, "state").equals("SUCCEEDED")
            && (!Payloads.hash(s(i, "output_snapshot")).equals(s(b, "output_digest"))
                || !s(b, "output_digest").equals(s(i, "output_digest"))
                || SessionContracts.bytes(s(i, "output_snapshot")) != n(b, "output_bytes")))
          throw corrupt();
        if (traceTask(task)) verifyReadRelation(task, r, value, i, b);
        digest = s(i, "input_digest");
      }
      case "turn.ended" -> {
        var terminal =
            one(
                "SELECT event_id,type,status,code FROM event WHERE tenant_id=? AND task_id=? AND fact_id='terminal'",
                s(task, "tenant_id"),
                s(task, "id"));
        digest = Payloads.hash(Payloads.json(terminal));
        if (!s(r, "fact_key").equals("terminal")
            || !source.equals(
                Payloads.read(Payloads.json(source("task", null, null)), JsonNode.class))
            || !Objects.equals(terminal.get("status"), value.path("data").path("state").asText())
            || !Objects.equals(
                terminal.get("code"),
                value.path("data").path("code").isNull()
                    ? null
                    : value.path("data").path("code").asText())) throw corrupt();
      }
      default -> throw new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
    }
    if (!digest.equals(s(r, "source_digest"))) throw corrupt();
    if (traceTask(task)
        && !"session.read".equals(s(r, "type"))
        && !value.path("source_event_seqs").isEmpty()) throw corrupt();
    if (JdbcSessionScopes.task(task)
        && !"session.read".equals(s(r, "type"))
        && !value.path("source_event_refs").isEmpty()) throw corrupt();
    var reliable =
        one(
            "SELECT type FROM event WHERE tenant_id=? AND task_id=? AND seq=?",
            s(task, "tenant_id"),
            s(task, "id"),
            n(r, "reliable_event_seq"));
    if (!"session_history.changed".equals(s(reliable, "type"))) throw corrupt();
    if (r.get("committed_attempt_id") != null)
      one(
          "SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?",
          s(task, "tenant_id"),
          s(task, "id"),
          s(r, "committed_attempt_id"),
          n(r, "committed_fence"));
    return value;
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
  private void verifyReadRelation(
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
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(i, "step_id"));
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
    searchBudget.set(budget);
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
      searchBudget.remove();
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

  private String verifyScopeRead(
      Map<String, Object> task, Map<String, Object> event, JsonNode value) {
    var i =
        one(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND step_id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(event, "source_invocation_id"),
            s(event, "source_step_id"));
    var b =
        one(
            "SELECT * FROM invocation_session_scope_query WHERE tenant_id=? AND invocation_id=? AND caller_task_id=? AND step_id=?",
            s(task, "tenant_id"),
            s(i, "id"),
            s(task, "id"),
            s(i, "step_id"));
    var step =
        one(
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(i, "step_id"));
    validateStep(task, step);
    var scope = scopes.require(task);
    var cut =
        one(
            "SELECT * FROM step_session_scope WHERE tenant_id=? AND caller_task_id=? AND step_id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(i, "step_id"));
    String normalizedInput =
        normalizedScope(task, step, "session_event_read", s(i, "input_snapshot"));
    if (!"READ".equals(s(b, "operation"))
        || !"session_event_read".equals(s(i, "tool"))
        || !Payloads.hash(s(i, "input_snapshot")).equals(s(i, "input_digest"))
        || !normalizedInput.equals(s(b, "normalized_snapshot"))
        || !Payloads.hash(normalizedInput).equals(s(b, "normalized_digest"))
        || SessionContracts.bytes(normalizedInput) != n(b, "normalized_bytes")
        || !s(scope.row(), "authority_digest").equals(s(b, "authority_digest"))
        || !s(b, "authority_digest").equals(s(cut, "authority_digest"))
        || !s(step, "session_context_digest").equals(s(b, "context_digest"))
        || !s(b, "context_digest").equals(s(cut, "context_digest"))
        || !Payloads.hash(s(cut, "context_snapshot")).equals(s(cut, "context_digest"))
        || !s(step, "session_binding_digest").equals(s(b, "binding_digest"))
        || !s(event, "fact_key").equals("read:" + s(i, "id"))
        || !value.path("source").path("kind").asText().equals("invocation")
        || n(b, "native_read_seq") != n(event, "seq")
        || !s(i, "state").equals(s(b, "outcome"))
        || !s(i, "input_digest").equals(s(b, "input_digest"))
        || !Payloads.json(scopeReceipt(b)).equals(Payloads.json(value.path("data")))
        || !Payloads.json(scopeReadRefs(task, s(i, "id")))
            .equals(Payloads.json(value.path("source_event_refs")))) throw corrupt();
    var refs = scopeReadRefs(task, s(i, "id"));
    if ("SUCCEEDED".equals(s(b, "outcome"))) {
      String raw = s(i, "output_snapshot");
      v4.output(raw);
      if (!Payloads.hash(raw).equals(s(b, "output_digest"))
          || !s(b, "output_digest").equals(s(i, "output_digest"))
          || SessionContracts.bytes(raw) != n(b, "output_bytes")) throw corrupt();
      var output = contracts.json(raw, 32768);
      var normalized = contracts.json(s(b, "normalized_snapshot"), 4096);
      if (!output.path("session_id").asText().equals(normalized.path("target_session_id").asText())
          || output.path("upper_seq").asLong() != normalized.path("upper_seq").asLong()
          || refs.size()
              != output.path("window").path("to_seq").asLong()
                  - output.path("window").path("from_seq").asLong()
                  + 1) throw corrupt();
      for (int index = 0; index < refs.size(); index++) {
        var ref = refs.get(index);
        long seq = ref.path("seq").asLong(), targetSeq = normalized.path("seq").asLong();
        var projected =
            seq == targetSeq
                ? output.path("target")
                : seq < targetSeq
                    ? output.path("before").get(index)
                    : output.path("after").get((int) (seq - targetSeq - 1));
        if (!ref.path("session_id").asText().equals(output.path("session_id").asText())
            || ref.path("seq").asLong() != output.path("window").path("from_seq").asLong() + index)
          throw corrupt();
        if (projected == null
            || !Payloads.hash(Payloads.json(projected)).equals(ref.path("event_sha256").asText()))
          throw corrupt();
      }
      var target =
          refs.stream()
              .filter(ref -> ref.path("seq").asLong() == normalized.path("seq").asLong())
              .findFirst()
              .orElseThrow(JdbcSessionRecords::corrupt);
      if (!Payloads.hash(Payloads.json(output.path("target")))
          .equals(target.path("event_sha256").asText())) throw corrupt();
    } else if (!refs.isEmpty() || i.get("output_snapshot") != null || n(b, "output_bytes") != 0)
      throw corrupt();
    return s(i, "input_digest");
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
            input, deadline);
    var scanned = new ArrayList<Map<String, Object>>();
    int eventBytes = 0;
    searchBudget.set(new SearchBudget(deadline, false, 16777216, true));
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
      searchBudget.remove();
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
    searchBudget.set(new SearchBudget(deadline, false, 16777216, true));
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
      searchBudget.remove();
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
    searchBudget.set(budget);
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
      searchBudget.remove();
    }
  }

  void purge(Map<String, Object> task) {
    var h = history(task, false);
    String tenant = s(task, "tenant_id"), id = s(task, "id");
    var retained = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "model_dispatch",
            "session_public_event",
            "invocation_session_search",
            "invocation_session_read",
            "invocation_session_trace",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_scope_query")) {
      String key =
          table.equals("task") ? "id" : table.contains("scope") ? "caller_task_id" : "task_id";
      var entries = new ArrayList<Map<String, Object>>();
      for (var row :
          rows("SELECT * FROM " + table + " WHERE tenant_id=? AND " + key + "=?", tenant, id)) {
        var item = new LinkedHashMap<String, Object>();
        item.put(
            "id",
            row.get(
                table.equals("session_public_event")
                    ? "seq"
                    : List.of(
                                "invocation_session_search",
                                "invocation_session_trace",
                                "invocation_session_read",
                                "invocation_session_scope_query")
                            .contains(table)
                        ? "invocation_id"
                        : table.equals("task_session_scope_target")
                            ? "ordinal"
                            : table.equals("step_session_scope")
                                ? "step_id"
                                : table.equals("task_session_scope") ? "caller_task_id" : "id"));
        for (String field : BODIES)
          if (row.get(field) != null) {
            String raw = s(row, field);
            item.put(
                field, Map.of("sha256", Payloads.hash(raw), "bytes", SessionContracts.bytes(raw)));
          }
        entries.add(item);
      }
      retained.put(table, entries);
    }
    if (JdbcSessionScopes.task(task)) {
      db.update(
          "UPDATE invocation_session_scope_query SET normalized_snapshot=NULL,read_refs_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND caller_task_id=?",
          tenant,
          id);
      db.update(
          "UPDATE step_session_scope SET context_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND caller_task_id=?",
          tenant,
          id);
      db.update(
          "UPDATE task_session_scope_target SET source_task_id=NULL,target_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND caller_task_id=?",
          tenant,
          id);
      db.update(
          "UPDATE task_session_scope SET authority_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND caller_task_id=?",
          tenant,
          id);
    }
    retained.put("version", 1);
    retained.put("original_body_bytes", n(h, "body_bytes"));
    retained.put("binding_digest", s(h, "binding_digest"));
    String receipt = Payloads.json(retained);
    db.update(
        "UPDATE session_public_event SET event_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    if (searchTask(task))
      db.update(
          "UPDATE invocation_session_search SET normalized_query_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND task_id=?",
          tenant,
          id);
    db.update(
        "UPDATE invocation_session_trace SET content_state='PURGED' WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE invocation_session_read SET read_sources_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE invocation SET input_snapshot='',output_snapshot=NULL WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE step SET request_snapshot='',response_snapshot=NULL,request_cleanup_state='SESSION_PURGED',retained_request_digest=request_digest WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE model_dispatch SET usage_snapshot=NULL WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE task SET goal='',request_snapshot='',summary=NULL WHERE tenant_id=? AND id=?",
        tenant,
        id);
    // All immutable policy/model/tool pins remain counted metadata, not retained user content.
    db.update(
        "UPDATE task_session_history SET content_state='PURGED',body_bytes=0,cleanup_receipt=?,held_bytes=metadata_bytes WHERE tenant_id=? AND task_id=? AND content_state='PRESENT'",
        receipt,
        tenant,
        id);
  }

  void release(Map<String, Object> task) {
    String tenant = s(task, "tenant_id"), id = s(task, "id");
    // Include the changed decimal widths of actual/held/metadata in the final receipt charge.
    for (int pass = 0; pass < 5; pass++) {
      long bytes = metadata(task, true);
      db.update(
          "UPDATE task_session_history SET metadata_bytes=?,held_bytes=? WHERE tenant_id=? AND task_id=?",
          bytes,
          bytes,
          tenant,
          id);
      db.update(
          "UPDATE reservation SET state='SETTLED',actual=? WHERE tenant_id=? AND id=? AND category='SESSION_HISTORY_STORAGE'",
          bytes,
          tenant,
          id + ":session-history");
      // Settling the reservation changes state/actual scalar widths too. Verify the
      // complete post-update rows before accepting a fixed point and releasing quota.
      if (metadata(task, true) == bytes) {
        db.update(
            "UPDATE tenant_budget SET history_storage_held=history_storage_held-? WHERE tenant_id=?",
            8388608 - bytes,
            tenant);
        return;
      }
    }
    throw corrupt();
  }
}
