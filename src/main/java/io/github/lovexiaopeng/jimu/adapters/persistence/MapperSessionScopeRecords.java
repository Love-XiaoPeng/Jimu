package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeSourceReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeSourceReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.History;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Target;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 行和JSON只在外层转换，查询计量与截止使用同一显式query和事务DS。 */
final class MapperSessionScopeRecords implements SessionScopeRecords {
  @Override
  public long utf8Bytes(String raw) {
    return JdbcSessionRecords.scopeUtf8Bytes(ledger.queryContext(), raw);
  }

  private final JdbcLedger ledger;
  final SessionScopeMapper sql;
  private final SessionHistoryRetentionMapper safety;

  MapperSessionScopeRecords(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionScopeMapper.class);
    safety = ledger.mappers.session().getMapper(SessionHistoryRetentionMapper.class);
  }

  static Caller caller(Map<String, Object> row) {
    return new Caller(
        s(row, "tenant_id"),
        s(row, "id"),
        s(row, "owner_actor_id"),
        s(row, "capabilities"),
        s(row, "tools_digest"),
        n(row, "installation_epoch"),
        s(row, "state"),
        (LocalDateTime) row.get("created_at"),
        s(row, "request_digest"));
  }

  static Map<String, Object> row(Caller c) {
    var r = new LinkedHashMap<String, Object>();
    r.put("tenant_id", c.tenant());
    r.put("id", c.id());
    r.put("owner_actor_id", c.owner());
    r.put("capabilities", c.capabilities());
    r.put("tools_digest", c.tools());
    r.put("installation_epoch", c.epoch());
    r.put("state", c.state());
    r.put("created_at", c.created());
    r.put("request_digest", c.requestDigest());
    return r;
  }

  static Scope scope(Map<String, Object> r) {
    return new Scope(
        n(r, "record_version"),
        n(r, "authority_version"),
        s(r, "owner_actor_id"),
        s(r, "caller_binding_digest"),
        s(r, "caller_tools_digest"),
        s(r, "authority_snapshot"),
        s(r, "authority_digest"),
        n(r, "authority_bytes"),
        n(r, "candidate_count"),
        s(r, "grant_state"),
        (LocalDateTime) r.get("created_at"),
        (LocalDateTime) r.get("retain_until"),
        (LocalDateTime) r.get("invalidated_at"),
        (LocalDateTime) r.get("revoked_at"),
        (LocalDateTime) r.get("next_validation_at"),
        s(r, "content_state"),
        n(r, "retained") == 1);
  }

  static Map<String, Object> row(Caller c, Scope s) {
    var r = new LinkedHashMap<String, Object>();
    r.put("tenant_id", c.tenant());
    r.put("caller_task_id", c.id());
    r.put("record_version", s.recordVersion());
    r.put("authority_version", s.authorityVersion());
    r.put("owner_actor_id", s.owner());
    r.put("caller_binding_digest", s.binding());
    r.put("caller_tools_digest", s.tools());
    r.put("authority_snapshot", s.raw());
    r.put("authority_digest", s.digest());
    r.put("authority_bytes", s.bytes());
    r.put("candidate_count", s.count());
    r.put("grant_state", s.grant());
    r.put("created_at", s.created());
    r.put("retain_until", s.retain());
    r.put("invalidated_at", s.invalidated());
    r.put("revoked_at", s.revoked());
    r.put("next_validation_at", s.nextValidation());
    r.put("content_state", s.content());
    r.put("retained", s.retained() ? 1 : 0);
    return r;
  }

  public Live currentState(Caller c) {
    var rows = sql.currentState(ledger.queryContext(), c.tenant(), c.id());
    if (rows.size() != 1) throw JdbcSessionScopes.unavailable();
    var r = rows.getFirst();
    return new Live(
        caller(r),
        s(r, "authority_digest"),
        s(r, "caller_binding_digest"),
        s(r, "caller_tools_digest"),
        s(r, "scope_owner"),
        n(r, "record_version"),
        n(r, "authority_version"),
        n(r, "candidate_count"),
        n(r, "next_seq") - 1,
        s(r, "binding_digest"),
        (LocalDateTime) r.get("history_retain"),
        (LocalDateTime) r.get("scope_retain"),
        (LocalDateTime) r.get("database_time"),
        n(r, "present") == 1,
        n(r, "active") == 1,
        n(r, "quiet") == 1);
  }

  public List<String> callerCapabilities(String tenant, String caller) {
    return sql.callerCapabilities(ledger.queryContext(), tenant, caller).stream()
        .map(r -> s(r, "capabilities"))
        .toList();
  }

  public List<String> lockTargets(String tenant, String caller) {
    return sql.lockTargets(ledger.queryContext(), tenant, caller).stream()
        .map(r -> s(r, "source_task_id"))
        .toList();
  }

  public void lockTask(String tenant, String id) {
    var rows = sql.lockTask(ledger.queryContext(), tenant, id);
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null && rows.size() == 1) owner.locked(tenant, id);
  }

  public Scope scope(Caller c) {
    var r = sql.scope(ledger.queryContext(), c.tenant(), c.id());
    if (r.size() != 1) throw JdbcSessionScopes.unavailable();
    return scope(r.getFirst());
  }

  public List<Target> targets(Caller c) {
    return sql.targets(ledger.queryContext(), c.tenant(), c.id()).stream()
        .map(
            r ->
                new Target(
                    n(r, "record_version"),
                    n(r, "ordinal"),
                    s(r, "source_task_id"),
                    s(r, "target_snapshot"),
                    s(r, "target_digest"),
                    n(r, "target_bytes"),
                    s(r, "content_state")))
        .toList();
  }

  public Caller source(Caller c, String id) {
    var args = new HashMap<String, Object>();
    args.put("query", ledger.queryContext());
    args.put(
        "ew",
        new LambdaQueryWrapper<ScopeSourceReadEntity>()
            .eq(ScopeSourceReadEntity::getTenantId, c.tenant())
            .eq(ScopeSourceReadEntity::getRowId, id));
    List<Map<String, Object>> found =
        ledger
            .mappers
            .session()
            .selectList(ScopeSourceReadMapper.class.getName() + ".selectMaps", args);
    if (found.size() != 1) throw JdbcSessionScopes.unavailable();
    var r = new HashMap<>(found.getFirst());
    r.put("id", r.get("rowId"));
    return caller(r);
  }

  public boolean hasTargets(Caller c) {
    return !sql.hasTargets(ledger.queryContext(), c.tenant(), c.id()).isEmpty();
  }

  public boolean admissionRetention(Caller c) {
    var rows = sql.admissionRetention(ledger.queryContext(), c.tenant(), c.id());
    if (rows.size() != 1)
      throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1, rows.size());
    return n(rows.getFirst(), "retained") == 1;
  }

  public boolean writersQuiescent(Caller c) {
    return ledger.writersQuiescent(row(c));
  }

  public boolean activeInvocations(Caller c) {
    return !safety.activeInvocations(ledger.queryContext(), c.tenant(), c.id()).isEmpty();
  }

  public boolean unsafeReservations(Caller c) {
    return !safety.unsafeReservations(ledger.queryContext(), c.tenant(), c.id()).isEmpty();
  }

  public boolean dispatchedModels(Caller c) {
    return !safety.dispatchedModels(ledger.queryContext(), c.tenant(), c.id()).isEmpty();
  }

  public History history(Caller c) {
    var r = ledger.sessions.history(row(c), true);
    return new History(
        n(r, "next_seq"), s(r, "binding_digest"), (LocalDateTime) r.get("retain_until"));
  }

  public String expectedBinding(Caller c) {
    return ledger.sessions.contract(row(c)).binding();
  }

  public void validateAuthority(Caller c, String raw) {
    ledger.sessionScopes.contracts(row(c)).authority(raw);
  }

  public String expectedBinding() {
    return ledger.sessions.v4.binding();
  }

  public void validateAuthority(String raw) {
    ledger.sessionScopes.contracts.authority(raw);
  }

  public Authority decodeAuthority(String raw) {
    return decoded(ledger.sessions.contracts.json(raw, 4096));
  }

  private Authority decoded(com.fasterxml.jackson.databind.JsonNode a) {
    var targets = new ArrayList<AuthorityTarget>();
    for (var t : a.path("targets"))
      targets.add(
          new AuthorityTarget(
              t.path("session_id").asText(),
              t.path("pins_sha256").asText(),
              t.path("binding_sha256").asText(),
              t.path("upper_seq").asLong(),
              t.path("created_at").asText(),
              t.path("retain_until").asText(),
              Payloads.json(t)));
    return new Authority(
        a.path("tenant_id").asText(),
        a.path("caller_session_id").asText(),
        a.path("owner_actor_id").asText(),
        a.path("caller_tools_sha256").asText(),
        a.path("caller_binding_sha256").asText(),
        a.path("created_at").asText(),
        a.path("retain_until").asText(),
        List.copyOf(targets));
  }

  public SourceFact stagedSource(Caller caller, String id) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    return owner == null ? null : owner.stagedSource(caller, id);
  }

  public void stageSource(Caller caller, SourceFact fact) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null) owner.stageSource(caller, fact);
  }

  public Authority authority(Caller caller, String raw) {
    var query = ledger.queryContext();
    if (query != null) query.operation(raw.length());
    var value = ledger.sessionScopes.contracts(row(caller)).authorityValue(raw);
    if (query != null) query.operation(value.size() + value.path("targets").size());
    return decoded(value);
  }

  public void validatedAuthority(Caller caller, Scope scope, Authority authority) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null) owner.validatedAuthority(caller, scope, authority);
  }

  public void invalidateAfterTransaction(Caller c) {
    ledger.sessionScopes.invalidated.accept(row(c));
  }
}
