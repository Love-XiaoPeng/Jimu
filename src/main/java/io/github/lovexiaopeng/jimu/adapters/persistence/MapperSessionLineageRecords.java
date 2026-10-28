package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 同DS/原UoW内真实MP读写和受理事实转换；不递归查询来源图。 */
final class MapperSessionLineageRecords implements SessionLineageRecords {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private final JdbcLedger ledger;
  private final SessionLineageContracts contracts;

  MapperSessionLineageRecords(JdbcLedger ledger) {
    this.ledger = ledger;
    contracts = ledger.sessions.lineage;
  }

  public io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
      verified(String tenant, String caller) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    return owner == null ? null : owner.graph(tenant, caller);
  }

  public void verified(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null) owner.graph(tenant, caller, proof);
  }

  private final Object scratchOwner = new Object();

  public <T> T withProof(java.util.function.Supplier<T> work) {
    return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        ledger.queryContext(), "lineage.graph", work);
  }

  public void scratch(long bytes) {
    if (bytes == 0) ledger.queryContext().releaseScratch(scratchOwner);
    else ledger.queryContext().retainScratch(scratchOwner, bytes);
  }

  public Fact fact(String tenant, String id) {
    var rows =
        ledger
            .mappers
            .session()
            .getMapper(SessionLineageMapper.class)
            .fact(ledger.queryContext(), tenant, id);
    if (rows.size() != 1) throw JdbcSessionRecords.corrupt();
    var row = rows.getFirst();
    if (n(row, "history_version") != 5
        || !SessionLineagePolicyV5.CAPABILITIES.equals(s(row, "capabilities"))
        || !"PRESENT".equals(s(row, "scope_content"))) throw JdbcSessionRecords.corrupt();
    String request = s(row, "request_snapshot"), authority = s(row, "authority_snapshot");
    if (request == null
        || authority == null
        || !hash(request).equals(s(row, "request_digest"))
        || !hash(authority).equals(s(row, "authority_digest"))
        || bytes(authority) != n(row, "authority_bytes")) throw JdbcSessionRecords.corrupt();
    checkpoint(request.length() + authority.length());
    var json = ledger.sessions.v5.json(request, 16384);
    var scope = ledger.sessions.v5.json(authority, 4096);
    ledger.sessionScopes.v5.authority(authority);
    var sources = new ArrayList<String>();
    for (var item : scope.path("targets")) sources.add(item.path("session_id").textValue());
    var declared = new ArrayList<String>();
    for (var item : json.path("history_session_ids")) declared.add(item.textValue());
    var caps = new ArrayList<String>();
    for (var item : json.path("request").path("capabilityIds")) caps.add(item.textValue());
    if (!declared.equals(sources)
        || !String.join(",", caps).equals(SessionLineagePolicyV5.CAPABILITIES)
        || !scope.path("caller_session_id").asText().equals(id)
        || !scope.path("tenant_id").asText().equals(tenant)) throw JdbcSessionRecords.corrupt();
    return new Fact(
        tenant,
        id,
        UTC.format((LocalDateTime) row.get("created_at")),
        request,
        s(row, "request_digest"),
        s(row, "authority_digest"),
        s(row, "binding_digest"),
        sources,
        json.has("parent_session_id") ? json.path("parent_session_id").textValue() : null);
  }

  public SessionLineage.Stored load(String tenant, String id) {
    var args = new HashMap<String, Object>();
    args.put("query", ledger.queryContext());
    args.put(
        "ew",
        new LambdaQueryWrapper<SessionLineageEntity>()
            .eq(SessionLineageEntity::getTenantId, tenant)
            .eq(SessionLineageEntity::getTaskId, id));
    List<SessionLineageEntity> found =
        ledger
            .mappers
            .session()
            .selectList(SessionLineageMapper.class.getName() + ".selectList", args);
    if (found.size() != 1) throw JdbcSessionRecords.corrupt();
    var row = found.getFirst();
    if (row.getRecordVersion() == null
        || row.getGenesisBytes() == null
        || row.getGraphBytes() == null) throw JdbcSessionRecords.corrupt();
    return new SessionLineage.Stored(
        row.getGenesisSnapshot(),
        row.getGenesisDigest(),
        row.getGenesisBytes(),
        row.getGraphSnapshot(),
        row.getGraphDigest(),
        row.getGraphBytes(),
        row.getContentState(),
        row.getRecordVersion());
  }

  public void insert(String tenant, String id, SessionLineage.Stored stored) {
    var entity = new SessionLineageEntity();
    entity.setTenantId(tenant);
    entity.setTaskId(id);
    entity.setRecordVersion(Math.toIntExact(stored.version()));
    entity.setGenesisSnapshot(stored.genesis());
    entity.setGenesisDigest(stored.genesisDigest());
    entity.setGenesisBytes(stored.genesisBytes());
    entity.setGraphSnapshot(stored.graph());
    entity.setGraphDigest(stored.graphDigest());
    entity.setGraphBytes(stored.graphBytes());
    entity.setContentState(stored.state());
    var args = new HashMap<String, Object>();
    var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
    for (String field : meta.getGetterNames()) args.put(field, meta.getValue(field));
    args.put("query", ledger.queryContext());
    if (ledger.mappers.session().insert(SessionLineageMapper.class.getName() + ".insert", args)
        != 1) throw JdbcSessionRecords.corrupt();
  }

  public void body(String tenant, String id, long bytes) {
    ledger.sessions.body(ledger.locked(tenant, id), bytes);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public String genesis(SessionLineage.Genesis value) {
    return contracts.genesis(value);
  }

  public SessionLineage.Genesis genesis(String raw) {
    if (raw == null) throw JdbcSessionRecords.corrupt();
    checkpoint(raw.length());
    return contracts.genesis(raw);
  }

  public String graph(SessionLineage.Graph value) {
    return contracts.graph(value);
  }

  public SessionLineage.Graph graph(String raw) {
    if (raw == null) throw JdbcSessionRecords.corrupt();
    checkpoint(raw.length());
    return contracts.graph(raw);
  }

  public String output(SessionLineage.Result value) {
    return contracts.output(value);
  }

  public void checkpoint(long operations) {
    ledger.queryContext().remainingMillis();
    ledger.queryContext().operation(operations);
  }
}
