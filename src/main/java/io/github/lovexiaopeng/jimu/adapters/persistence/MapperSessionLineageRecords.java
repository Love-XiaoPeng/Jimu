package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper;
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
  private final Object rawOwner = new Object();
  private java.util.IdentityHashMap<
          String, io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView>
      rawViews;

  private io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView raw(String value) {
    if (rawViews == null)
      return new io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView(
          value, ledger.queryContext());
    var found = rawViews.get(value);
    if (found != null) return found;
    ledger.queryContext().retainScratch(rawOwner, (rawViews.size() + 1L) * 256);
    found =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView(
            value, ledger.queryContext());
    rawViews.put(value, found);
    return found;
  }

  public <T> T withProof(java.util.function.Supplier<T> work) {
    if (rawViews != null) return work.get();
    rawViews = new java.util.IdentityHashMap<>();
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null)
      owner.invalidator(
          rawOwner,
          writes -> {
            rawViews.clear();
            ledger.queryContext().releaseScratch(rawOwner);
          });
    try {
      return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
          ledger.queryContext(), "lineage.graph", work);
    } finally {
      if (owner != null) owner.removeInvalidator(rawOwner);
      rawViews.clear();
      rawViews = null;
      ledger.queryContext().releaseScratch(rawOwner);
    }
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
        || bytes(authority) != n(row, "authority_bytes")) throw JdbcSessionRecords.corrupt();
    var json =
        ledger
            .queryContext()
            .primitive(
                "parse.request",
                request.length(),
                () -> ledger.sessions.v5.json(raw(request), 16384));
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    var scope =
        owner == null
            ? null
            : owner.validatedAuthority(tenant, id, authority, s(row, "authority_digest"));
    if (scope == null) {
      if (!hash(authority).equals(s(row, "authority_digest"))) throw JdbcSessionRecords.corrupt();
      checkpoint(authority.length());
      var value = ledger.sessionScopes.v5.authorityValue(authority);
      var targets =
          new ArrayList<
              io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords
                  .AuthorityTarget>();
      for (var item : value.path("targets"))
        targets.add(
            new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget(
                item.path("session_id").asText(),
                item.path("pins_sha256").asText(),
                item.path("binding_sha256").asText(),
                item.path("upper_seq").asLong(),
                item.path("created_at").asText(),
                item.path("retain_until").asText(),
                Payloads.json(item)));
      scope =
          new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority(
              value.path("tenant_id").asText(),
              value.path("caller_session_id").asText(),
              value.path("owner_actor_id").asText(),
              value.path("caller_tools_sha256").asText(),
              value.path("caller_binding_sha256").asText(),
              value.path("created_at").asText(),
              value.path("retain_until").asText(),
              targets);
    }
    var sources =
        scope.targets().stream()
            .map(
                io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget
                    ::id)
            .toList();
    var declared = new ArrayList<String>();
    for (var item : json.path("history_session_ids")) declared.add(item.textValue());
    var caps = new ArrayList<String>();
    for (var item : json.path("request").path("capabilityIds")) caps.add(item.textValue());
    if (!declared.equals(sources)
        || !String.join(",", caps).equals(SessionLineagePolicyV5.CAPABILITIES)
        || !scope.caller().equals(id)
        || !scope.tenant().equals(tenant)) throw JdbcSessionRecords.corrupt();
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

  public boolean requestVerified(Fact fact) {
    return true;
  }

  public void created(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner != null) owner.stageGraph(tenant, caller, proof);
  }

  public boolean createdReadback(
      String tenant,
      String caller,
      SessionLineage.Stored generated,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            ledger.queryContext());
    if (owner == null) return false;
    owner.checkedCreated(tenant, caller, generated, proof);
    try {
      var read = load(tenant, caller);
      var genesis = raw(read.genesis());
      var graph = raw(read.graph());
      if (!owner.checkedCreated(tenant, caller, generated)
          || read.version() != generated.version()
          || !read.state().equals(generated.state())
          || read.genesisBytes() != genesis.bytes()
          || read.graphBytes() != graph.bytes()
          || read.genesisBytes() != generated.genesisBytes()
          || read.graphBytes() != generated.graphBytes()
          || !genesis.digest().equals(read.genesisDigest())
          || !graph.digest().equals(read.graphDigest())
          || !genesis.digest().equals(generated.genesisDigest())
          || !graph.digest().equals(generated.graphDigest())) throw JdbcSessionRecords.corrupt();
      return true;
    } finally {
      owner.releaseCreated();
    }
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
    // 新SELECT的原串从不按id/digest命中旧RawView；即使内容相同也独立编码和hash。
    if (rawViews != null) {
      rawViews.remove(row.getGenesisSnapshot());
      rawViews.remove(row.getGraphSnapshot());
    }
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
    // 完整键来自同一受理owner的既得锁/新建行；正文条件UPDATE仍核真实history。
    ledger.sessions.body(
        java.util.Map.of(
            "tenant_id", tenant, "id", id, "capabilities", SessionLineagePolicyV5.CAPABILITIES),
        bytes);
  }

  public String hash(String raw) {
    return raw(raw).digest();
  }

  public int bytes(String raw) {
    return raw(raw).bytes();
  }

  public String genesis(SessionLineage.Genesis value) {
    var encoded = contracts.genesis(value, ledger.queryContext());
    if (rawViews != null) rawViews.put(encoded.raw(), encoded);
    return encoded.raw();
  }

  public SessionLineage.Genesis genesis(String raw) {
    if (raw == null) throw JdbcSessionRecords.corrupt();
    return contracts.genesis(raw(raw), ledger.queryContext());
  }

  public boolean matchGraph(SessionLineage.Stored stored, SessionLineage.Graph expected) {
    contracts.matchGraph(raw(stored.graph()), expected, ledger.queryContext());
    return true;
  }

  public String graph(SessionLineage.Graph value) {
    var encoded = contracts.graph(value, ledger.queryContext());
    if (rawViews != null) rawViews.put(encoded.raw(), encoded);
    return encoded.raw();
  }

  public SessionLineage.Graph graph(String raw) {
    if (raw == null) throw JdbcSessionRecords.corrupt();
    checkpoint(raw.length());
    return contracts.graph(raw);
  }

  public String output(SessionLineage.Result value) {
    return contracts.output(value, ledger.queryContext()).raw();
  }

  public void checkpoint(long operations) {
    ledger.queryContext().remainingMillis();
    ledger.queryContext().operation(operations);
  }
}
