package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Host-only immutable prior-session authority. Called under budget and ordered task locks. */
final class JdbcSessionScopes {
  private final JdbcTemplate db;
  private final JdbcSessionRecords sessions;
  private final Predicate<Map<String, Object>> safe;
  java.util.function.Consumer<Map<String, Object>> invalidated;
  final PriorSessionContracts contracts = new PriorSessionContracts();
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  JdbcSessionScopes(
      JdbcTemplate db, JdbcSessionRecords sessions, Predicate<Map<String, Object>> safe) {
    this.db = db;
    this.sessions = sessions;
    this.safe = safe;
  }

  static boolean task(Map<String, Object> task) {
    return "session_event_read,session_search".equals(s(task, "capabilities"));
  }

  static TaskFailure unavailable() {
    return new TaskFailure(404, "SESSION_SCOPE_UNAVAILABLE");
  }

  void lock(String tenant, String caller) {
    var found =
        db.queryForList("SELECT capabilities FROM task WHERE tenant_id=? AND id=?", tenant, caller);
    if (found.size() != 1 || !task(found.getFirst())) return;
    var ids = new ArrayList<String>();
    ids.add(caller);
    var targets =
        db.queryForList(
            "SELECT source_task_id FROM task_session_scope_target FORCE INDEX(PRIMARY) WHERE tenant_id=? AND caller_task_id=? ORDER BY ordinal LIMIT 9",
            tenant,
            caller);
    if (targets.size() > 8) throw JdbcSessionRecords.corrupt();
    for (var target : targets)
      if (target.get("source_task_id") != null) ids.add(s(target, "source_task_id"));
    lock(tenant, ids);
  }

  void lock(String tenant, List<String> ids) {
    for (String id : ids.stream().distinct().sorted().toList())
      db.queryForList("SELECT id FROM task WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
  }

  private Map<String, Object> row(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.size() != 1) throw unavailable();
    return rows.getFirst();
  }

  Map<String, Object> scope(Map<String, Object> task) {
    return row(
        "SELECT *,retain_until>CURRENT_TIMESTAMP(6) AS retained FROM task_session_scope WHERE tenant_id=? AND caller_task_id=? FOR UPDATE",
        s(task, "tenant_id"),
        s(task, "id"));
  }

  JsonNode snapshot(Map<String, Object> task, Map<String, Object> scope) {
    if (n(scope, "record_version") != 1
        || n(scope, "authority_version") != 1
        || !s(task, "owner_actor_id").equals(s(scope, "owner_actor_id"))
        || !s(task, "tools_digest").equals(s(scope, "caller_tools_digest"))
        || !Payloads.hash(sessions.v4.binding()).equals(s(scope, "caller_binding_digest")))
      throw JdbcSessionRecords.corrupt();
    if (!"ACTIVE".equals(s(scope, "grant_state"))
        || scope.get("invalidated_at") != null
        || !"PRESENT".equals(s(scope, "content_state"))
        || n(scope, "retained") != 1) throw unavailable();
    String raw = s(scope, "authority_snapshot");
    contracts.authority(raw);
    if (SessionContracts.bytes(raw) != n(scope, "authority_bytes")
        || !Payloads.hash(raw).equals(s(scope, "authority_digest")))
      throw JdbcSessionRecords.corrupt();
    var authority = sessions.contracts.json(raw, 4096);
    if (!s(task, "tenant_id").equals(authority.path("tenant_id").asText())
        || !s(task, "id").equals(authority.path("caller_session_id").asText())
        || !s(task, "owner_actor_id").equals(authority.path("owner_actor_id").asText())
        || !s(task, "tools_digest").equals(authority.path("caller_tools_sha256").asText())
        || !s(scope, "caller_binding_digest")
            .equals(authority.path("caller_binding_sha256").asText())
        || !UTC.format((LocalDateTime) scope.get("created_at"))
            .equals(authority.path("created_at").asText())
        || !UTC.format((LocalDateTime) scope.get("retain_until"))
            .equals(authority.path("retain_until").asText())
        || authority.path("targets").size() != n(scope, "candidate_count"))
      throw JdbcSessionRecords.corrupt();
    return authority;
  }

  record Scope(Map<String, Object> row, JsonNode authority, List<Map<String, Object>> sources) {}

  Scope require(Map<String, Object> task) {
    try {
      return requireCurrent(task);
    } catch (TaskFailure failure) {
      if (failure.code().equals("SESSION_SCOPE_UNAVAILABLE")) invalidated.accept(task);
      throw failure;
    }
  }

  private Scope requireCurrent(Map<String, Object> task) {
    var scope = scope(task);
    var authority = snapshot(task, scope);
    var h = sessions.history(task, true);
    if (!h.get("retain_until").equals(scope.get("retain_until")))
      throw JdbcSessionRecords.corrupt();
    var targets =
        db.queryForList(
            "SELECT * FROM task_session_scope_target FORCE INDEX(PRIMARY) WHERE tenant_id=? AND caller_task_id=? ORDER BY ordinal LIMIT 9",
            s(task, "tenant_id"),
            s(task, "id"));
    if (targets.size() != n(scope, "candidate_count") || targets.size() > 8)
      throw JdbcSessionRecords.corrupt();
    var sources = new ArrayList<Map<String, Object>>();
    String previous = "";
    for (int index = 0; index < targets.size(); index++) {
      var target = targets.get(index);
      var expected = authority.path("targets").get(index);
      String source = expected.path("session_id").asText(), raw = s(target, "target_snapshot");
      if (n(target, "record_version") != 1
          || n(target, "ordinal") != index
          || !"PRESENT".equals(s(target, "content_state"))
          || source.compareTo(previous) <= 0
          || source.equals(s(task, "id"))
          || !source.equals(s(target, "source_task_id"))
          || raw == null
          || !raw.equals(Payloads.json(expected))
          || !Payloads.hash(raw).equals(s(target, "target_digest"))
          || SessionContracts.bytes(raw) != n(target, "target_bytes"))
        throw JdbcSessionRecords.corrupt();
      previous = source;
      try {
        var st = source(task, source, false);
        var sh = sessions.history(st, true);
        if (!s(st, "tools_digest").equals(expected.path("pins_sha256").asText())
            || !s(sh, "binding_digest").equals(expected.path("binding_sha256").asText())
            || n(sh, "next_seq") - 1 != expected.path("upper_seq").asLong()
            || !UTC.format((LocalDateTime) sh.get("retain_until"))
                .equals(expected.path("retain_until").asText())
            || !UTC.format((LocalDateTime) st.get("created_at"))
                .equals(expected.path("created_at").asText())) throw unavailable();
        sources.add(st);
      } catch (TaskFailure failure) {
        if (failure.status() == 403 || failure.status() == 404 || failure.status() == 410)
          throw unavailable();
        throw failure;
      }
    }
    return new Scope(scope, authority, List.copyOf(sources));
  }

  private Map<String, Object> source(Map<String, Object> caller, String id, boolean admission) {
    var st =
        row(
            "SELECT tenant_id,id,owner_actor_id,capabilities,tools_digest,installation_epoch,state,created_at,request_digest FROM task WHERE tenant_id=? AND id=?",
            s(caller, "tenant_id"),
            id);
    if (!task(st)
        || id.equals(s(caller, "id"))
        || !s(caller, "owner_actor_id").equals(s(st, "owner_actor_id"))
        || !s(caller, "tools_digest").equals(s(st, "tools_digest"))
        || n(caller, "installation_epoch") != n(st, "installation_epoch")
        || !safe.test(st)) throw unavailable();
    var h = sessions.history(st, true);
    var own = scope(st);
    var snapshot = snapshot(st, own);
    if (n(own, "candidate_count") != 0
        || !snapshot.path("targets").isEmpty()
        || !db.queryForList(
                "SELECT ordinal FROM task_session_scope_target FORCE INDEX(PRIMARY) WHERE tenant_id=? AND caller_task_id=? LIMIT 1",
                s(st, "tenant_id"),
                id)
            .isEmpty()
        || n(h, "next_seq") < 1
        || n(h, "next_seq") > 128
        || !h.get("retain_until").equals(own.get("retain_until"))) throw unavailable();
    if (admission
        && db.queryForObject(
                "SELECT retain_until>=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) FROM task_session_history WHERE tenant_id=? AND task_id=?",
                Integer.class,
                s(st, "tenant_id"),
                id)
            != 1) throw unavailable();
    return st;
  }

  void create(Map<String, Object> caller, List<String> ids) {
    String tenant = s(caller, "tenant_id"), id = s(caller, "id");
    LocalDateTime created = (LocalDateTime) caller.get("created_at"), retain = created.plusDays(7);
    var targets = new ArrayList<Map<String, Object>>();
    for (String source : ids) {
      try {
        var st = source(caller, source, true);
        var h = sessions.history(st, true);
        var expiry = (LocalDateTime) h.get("retain_until");
        if (expiry.isBefore(retain)) retain = expiry;
        var target = new LinkedHashMap<String, Object>();
        target.put("session_id", source);
        target.put("history_version", 4);
        target.put("binding_sha256", s(h, "binding_digest"));
        target.put("pins_sha256", s(st, "tools_digest"));
        target.put("upper_seq", n(h, "next_seq") - 1);
        target.put("created_at", UTC.format((LocalDateTime) st.get("created_at")));
        target.put("retain_until", UTC.format(expiry));
        targets.add(target);
      } catch (TaskFailure failure) {
        if (failure.status() == 403 || failure.status() == 404 || failure.status() == 410)
          throw unavailable();
        throw failure;
      }
    }
    var authority = new LinkedHashMap<String, Object>();
    authority.put("version", 1);
    authority.put("caller_session_id", id);
    authority.put("tenant_id", tenant);
    authority.put("owner_actor_id", s(caller, "owner_actor_id"));
    authority.put("caller_binding_sha256", Payloads.hash(sessions.v4.binding()));
    authority.put("caller_tools_sha256", s(caller, "tools_digest"));
    authority.put("authority_policy", "explicit-terminal-independent-v4");
    authority.put("created_at", UTC.format(created));
    authority.put("retain_until", UTC.format(retain));
    authority.put("targets", targets);
    String raw = Payloads.json(authority);
    contracts.authority(raw);
    db.update(
        "INSERT INTO task_session_scope(tenant_id,caller_task_id,owner_actor_id,caller_binding_digest,caller_tools_digest,authority_snapshot,authority_digest,authority_bytes,candidate_count,created_at,retain_until,next_validation_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        tenant,
        id,
        s(caller, "owner_actor_id"),
        Payloads.hash(sessions.v4.binding()),
        s(caller, "tools_digest"),
        raw,
        Payloads.hash(raw),
        SessionContracts.bytes(raw),
        targets.size(),
        created,
        retain,
        created);
    long bytes = SessionContracts.bytes(raw);
    for (int ordinal = 0; ordinal < targets.size(); ordinal++) {
      var target = targets.get(ordinal);
      String targetRaw = Payloads.json(target);
      db.update(
          "INSERT INTO task_session_scope_target(tenant_id,caller_task_id,ordinal,source_task_id,target_snapshot,target_digest,target_bytes) VALUES(?,?,?,?,?,?,?)",
          tenant,
          id,
          ordinal,
          target.get("session_id"),
          targetRaw,
          Payloads.hash(targetRaw),
          SessionContracts.bytes(targetRaw));
      bytes +=
          SessionContracts.bytes(targetRaw)
              + SessionContracts.bytes(target.get("session_id").toString());
    }
    db.update(
        "UPDATE task_session_history SET retain_until=? WHERE tenant_id=? AND task_id=?",
        retain,
        tenant,
        id);
    sessions.body(caller, bytes);
  }
}
