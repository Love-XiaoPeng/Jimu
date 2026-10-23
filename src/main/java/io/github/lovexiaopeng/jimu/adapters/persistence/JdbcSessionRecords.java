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
  final io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts search =
      new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts();
  private final JdbcTemplate db;
  private final ThreadLocal<SearchBudget> searchBudget = new ThreadLocal<>();

  private static final class SearchBudget {
    int reads;
    long bytes;
    final Runnable deadline;

    SearchBudget(Runnable deadline) {
      this.deadline = deadline;
    }

    void charge(List<Map<String, Object>> rows) {
      deadline.run();
      if (++reads > 1024) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      for (var row : rows)
        for (var entry : row.entrySet())
          bytes += 8L + SessionContracts.bytes(entry.getKey()) + scalarBytes(entry.getValue());
      // Conservatively charge UTF-16 strings/row fields plus the active code point arrays.
      if (bytes > 8388608 || bytes * 2 + 524288 > 8388608)
        throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    }
  }

  static boolean searchTask(Map<String, Object> task) {
    return "session_event_read,session_event_search".equals(s(task, "capabilities"));
  }

  int version(Map<String, Object> task) {
    return searchTask(task) ? 2 : 1;
  }

  SessionContracts contract(Map<String, Object> task) {
    return searchTask(task) ? v2 : contracts;
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
          "normalized_query_snapshot");
  private static final List<String> TABLES =
      List.of(
          "task",
          "task_session_history",
          "session_public_event",
          "invocation_session_read",
          "invocation_session_search",
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
    return SessionContracts.NAME.equals(s(task, "capabilities")) || searchTask(task);
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
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
      String key = table.equals("task") ? "id" : "task_id";
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
      if (s(i, "tool").equals("session_event_search")) {
        if (!searchTask(task)) throw corrupt();
        var input = search.input(s(i, "input_snapshot"));
        String normalized = search.normalized(input, s(task, "id"), n(st, "session_upper_seq"));
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
          "INSERT INTO invocation_session_read(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,target_seq,before_count,after_count) VALUES(?,?,?,?,?,?,?,?,?,?)",
          s(task, "tenant_id"),
          s(i, "id"),
          s(task, "id"),
          step,
          s(h, "binding_digest"),
          s(i, "input_digest"),
          n(st, "session_upper_seq"),
          input.seq(),
          input.before(),
          input.after());
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
              || searchTask(task) && call.tool().equals("session_event_search")))
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
    String raw = Payloads.json(value);
    contract(task).owner(raw);
    return raw;
  }

  record Read(String output, long from, long to, String targetDigest) {}

  Read read(Map<String, Object> task, SessionEventPort.Input input, long upper, String boundary) {
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
    for (int index = 0; index < records.size(); index++) {
      var r = records.get(index);
      if (n(r, "seq") != from + index) throw corrupt();
      var value = verify(task, r);
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
    return new Read(raw, from, to, targetDigest);
  }

  JsonNode verify(Map<String, Object> task, Map<String, Object> r) {
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
    if (call.tool().equals("session_event_search") && searchTask(task)) {
      var input = search.input(call.input());
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
      if (searchTask(task) && call.name().equals("session_event_search")) {
        var node =
            (com.fasterxml.jackson.databind.node.ObjectNode) contracts.strictJson(arguments, 2048);
        var text = contracts.publicText(node.path("query").textValue(), sensitive());
        redacted |= text.redacted();
        node.put("query", text.content());
        arguments = Payloads.json(node);
        if (SessionContracts.bytes(arguments) > 2048)
          throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      }
      projected.add(new DurableAgent.ToolCall(call.id(), call.type(), call.name(), arguments));
    }
    return new PublicCalls(List.copyOf(projected), redacted);
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
    search.normalized(input, s(task, "id"), upper);
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
                "SELECT * FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq BETWEEN 0 AND ? ORDER BY seq LIMIT 128",
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
      String output = matcher.output(s(task, "id"), upper, boundary);
      search.output(output);
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
            "invocation_session_search")) {
      String key = table.equals("task") ? "id" : "task_id";
      var entries = new ArrayList<Map<String, Object>>();
      for (var row :
          rows("SELECT * FROM " + table + " WHERE tenant_id=? AND " + key + "=?", tenant, id)) {
        var item = new LinkedHashMap<String, Object>();
        item.put(
            "id",
            row.get(
                table.equals("session_public_event")
                    ? "seq"
                    : table.equals("invocation_session_search") ? "invocation_id" : "id"));
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
    retained.put("version", 1);
    retained.put("original_body_bytes", n(h, "body_bytes"));
    retained.put("binding_digest", s(h, "binding_digest"));
    String receipt = Payloads.json(retained);
    db.update(
        "UPDATE session_public_event SET event_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE invocation_session_search SET normalized_query_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND task_id=?",
        tenant,
        id);
    db.update(
        "UPDATE invocation_session_read SET content_state='PURGED' WHERE tenant_id=? AND task_id=?",
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
