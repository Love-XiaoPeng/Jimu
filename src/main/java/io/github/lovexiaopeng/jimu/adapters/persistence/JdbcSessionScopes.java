package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 旧runtime调用形状的适配边界；当前资格／whole-scope与锁规则已由Service负责。 */
final class JdbcSessionScopes {
  private final JdbcTemplate db;
  private final JdbcSessionRecords sessions;
  final MapperSessionScopeRecords records;
  final SessionScopeEligibilityService eligibility;
  java.util.function.Consumer<Map<String, Object>> invalidated;
  final PriorSessionContracts contracts = new PriorSessionContracts();
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  JdbcSessionScopes(JdbcLedger ledger, JdbcTemplate db, JdbcSessionRecords sessions) {
    this.db = db;
    this.sessions = sessions;
    records = new MapperSessionScopeRecords(ledger);
    eligibility = new SessionScopeEligibilityServiceImpl(records, Payloads::hash);
  }

  static boolean task(Map<String, Object> task) {
    return "session_event_read,session_search".equals(s(task, "capabilities"));
  }

  static TaskFailure unavailable() {
    return new TaskFailure(404, "SESSION_SCOPE_UNAVAILABLE");
  }

  void lock(String tenant, String caller) {
    eligibility.lock(tenant, caller);
  }

  void lock(String tenant, List<String> ids) {
    eligibility.lock(tenant, ids);
  }

  Map<String, Object> scope(Map<String, Object> task) {
    var c = MapperSessionScopeRecords.caller(task);
    return MapperSessionScopeRecords.row(c, records.scope(c));
  }

  JsonNode snapshot(Map<String, Object> task, Map<String, Object> scope) {
    eligibility.snapshot(
        MapperSessionScopeRecords.caller(task), MapperSessionScopeRecords.scope(scope));
    return sessions.contracts.json(s(scope, "authority_snapshot"), 4096);
  }

  record Scope(Map<String, Object> row, JsonNode authority, List<Map<String, Object>> sources) {}

  Scope require(Map<String, Object> task) {
    var c = MapperSessionScopeRecords.caller(task);
    var found = eligibility.require(c);
    return new Scope(
        MapperSessionScopeRecords.row(c, found.scope()),
        sessions.contracts.json(found.scope().raw(), 4096),
        found.sources().stream().map(MapperSessionScopeRecords::row).toList());
  }

  private Map<String, Object> source(Map<String, Object> caller, String id, boolean admission) {
    return MapperSessionScopeRecords.row(
        eligibility.source(MapperSessionScopeRecords.caller(caller), id, admission));
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
