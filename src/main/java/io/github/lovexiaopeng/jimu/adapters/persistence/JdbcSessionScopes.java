package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 旧runtime调用形状的适配边界；当前资格／whole-scope与锁规则已由Service负责。 */
final class JdbcSessionScopes {
  private final JdbcSessionRecords sessions;
  final MapperSessionScopeRecords records;
  final SessionScopeEligibilityService eligibility;
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeCreationService
      creation;
  java.util.function.Consumer<Map<String, Object>> invalidated;
  java.util.function.Consumer<Map<String, Object>> terminalAccess;
  final PriorSessionContracts contracts = new PriorSessionContracts();
  final PriorSessionContracts v5 = new PriorSessionContracts(5);

  PriorSessionContracts contracts(Map<String, Object> task) {
    return lineage(task) ? v5 : contracts;
  }

  static boolean lineage(Map<String, Object> task) {
    return io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
        .equals(s(task, "capabilities"));
  }

  JdbcSessionScopes(JdbcLedger ledger, JdbcTemplate db, JdbcSessionRecords sessions) {
    this.sessions = sessions;
    records = new MapperSessionScopeRecords(ledger);
    eligibility = new SessionScopeEligibilityServiceImpl(records, sessions::scopeHash);
    creation =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeCreationServiceImpl(
            new MapperSessionScopeCreationRecords(ledger, this, sessions));
  }

  static boolean task(Map<String, Object> task) {
    String capabilities = s(task, "capabilities");
    return io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
            .equals(capabilities)
        || "session_event_read,session_search".equals(capabilities);
  }

  static TaskFailure unavailable() {
    return new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
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
    var owner =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (owner != null && !owner.lineage()) owner = null;
    try {
      if (owner != null) {
        if (sessions.appendQuery() == null)
          throw new IllegalStateException("Missing query in session transaction");
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction.current(
            sessions.appendQuery());
        sessions.appendQuery().checkHealthy();
      }
      return require(task, owner);
    } catch (RuntimeException failure) {
      if (owner == null) throw failure;
      if (failure instanceof TaskFailure denial && owner.scopeStatus(denial)) throw failure;
      throw owner.poisonProof(failure);
    }
  }

  private Scope require(
      Map<String, Object> task,
      io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork owner) {
    var c = MapperSessionScopeRecords.caller(task);
    var found = owner == null ? null : owner.scope(c);
    if (found == null) {
      found = ledgerMeasure(c);
      if (owner != null) {
        var proof = found;
        var work = owner;
        owner.holdScope(proof);
        io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
            graph;
        graph = owner.stagedGraph(c.tenant(), c.id());
        if (graph == null) {
          owner.prepareLineageScope();
          graph =
              owner.withLineageScope(
                  sessions.appendQuery(),
                  c,
                  proof,
                  () -> sessions.lineageService.verify(c.tenant(), c.id()));
        }
        owner.scope(
            c,
            proof,
            () -> {
              long expires =
                  io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics
                      .measure(
                          sessions.appendQuery(),
                          "scope.live",
                          () -> {
                            long end = eligibility.live(c, proof);
                            terminalAccess.accept(task);
                            return end;
                          });
              work.expiry(
                  () -> {
                    if (System.nanoTime() >= expires) {
                      records.invalidateAfterTransaction(c);
                      throw unavailable();
                    }
                  });
            });
        owner.graph(c.tenant(), c.id(), graph);
      }
    }
    var proof = found;
    java.util.function.Supplier<JsonNode> parse =
        () -> {
          if (sessions.appendQuery() != null && lineage(task))
            sessions.appendQuery().operation(proof.scope().raw().length());
          return sessions.contracts.json(proof.scope().raw(), 4096);
        };
    if (sessions.appendQuery() != null && lineage(task))
      sessions.appendQuery().operation(1L + proof.sources().size());
    return new Scope(
        MapperSessionScopeRecords.row(c, found.scope()),
        owner == null ? parse.get() : owner.authority(parse),
        found.sources().stream().map(MapperSessionScopeRecords::row).toList());
  }

  private io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Validated
      ledgerMeasure(
          io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller caller) {
    return io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
        sessions.appendQuery(), "scope.current", () -> eligibility.require(caller));
  }

  Map<String, Object> source(Map<String, Object> caller, String id, boolean admission) {
    return MapperSessionScopeRecords.row(
        eligibility.source(MapperSessionScopeRecords.caller(caller), id, admission));
  }

  void create(Map<String, Object> caller, List<String> ids) {
    creation.create(MapperSessionScopeCreationRecords.wrap(caller), ids);
  }
}
