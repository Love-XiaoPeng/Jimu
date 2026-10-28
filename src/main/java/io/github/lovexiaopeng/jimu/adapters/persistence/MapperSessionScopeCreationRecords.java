package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords;
import java.time.LocalDateTime;
import java.util.Map;

/** Retains map identity and member access timing on the admission connection. */
final class MapperSessionScopeCreationRecords implements SessionScopeCreationRecords {
  private final JdbcLedger ledger;
  private final JdbcSessionScopes scopes;
  private final JdbcSessionRecords sessions;

  MapperSessionScopeCreationRecords(
      JdbcLedger ledger, JdbcSessionScopes scopes, JdbcSessionRecords sessions) {
    this.ledger = ledger;
    this.scopes = scopes;
    this.sessions = sessions;
  }

  static Row wrap(Map<String, Object> row) {
    return new View(row);
  }

  private static Map<String, Object> unwrap(Row row) {
    return ((View) row).row;
  }

  private record View(Map<String, Object> row) implements Row {
    public String tenant() {
      return (String) row.get("tenant_id");
    }

    public String id() {
      return (String) row.get("id");
    }

    public LocalDateTime created() {
      return (LocalDateTime) row.get("created_at");
    }

    public LocalDateTime retain() {
      return (LocalDateTime) row.get("retain_until");
    }

    public String binding() {
      return (String) row.get("binding_digest");
    }

    public String tools() {
      return (String) row.get("tools_digest");
    }

    public long nextSeq() {
      return ((Number) row.get("next_seq")).longValue();
    }

    public String owner() {
      return (String) row.get("owner_actor_id");
    }
  }

  public Row source(Row caller, String id) {
    return wrap(scopes.source(unwrap(caller), id, true));
  }

  public Row history(Row source) {
    return wrap(sessions.history(unwrap(source), true));
  }

  public int version(Row c) {
    return sessions.version(unwrap(c));
  }

  public String binding(Row c) {
    return sessions.contract(unwrap(c)).binding();
  }

  public void validateAuthority(Row c, String raw) {
    scopes.contracts(unwrap(c)).authority(raw);
  }

  public String binding() {
    return sessions.v4.binding();
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public void validateAuthority(String raw) {
    scopes.contracts.authority(raw);
  }

  public void body(Row caller, long bytes) {
    sessions.body(unwrap(caller), bytes);
  }

  public void insertAuthority(
      String tenant,
      String id,
      String owner,
      String binding,
      String tools,
      String raw,
      String digest,
      int bytes,
      int count,
      LocalDateTime created,
      LocalDateTime retain,
      LocalDateTime validation) {
    ledger
        .mappers
        .session()
        .getMapper(SessionScopeMapper.class)
        .insertAuthority(
            ledger.queryContext(),
            tenant,
            id,
            owner,
            binding,
            tools,
            raw,
            digest,
            bytes,
            count,
            created,
            retain,
            validation);
  }

  public void insertAuthorityTarget(
      String tenant, String id, int ordinal, Object source, String raw, String digest, int bytes) {
    ledger
        .mappers
        .session()
        .getMapper(SessionScopeMapper.class)
        .insertAuthorityTarget(
            ledger.queryContext(), tenant, id, ordinal, source, raw, digest, bytes);
  }

  public void retainAuthorityHistory(LocalDateTime retain, String tenant, String id) {
    ledger
        .mappers
        .session()
        .getMapper(SessionHistoryRetentionMapper.class)
        .retainAuthorityHistory(ledger.queryContext(), retain, tenant, id);
  }
}
