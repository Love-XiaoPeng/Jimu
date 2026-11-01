package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 原nodeInput真实Connector的编码交接；手工行只证明受影响SQL，不宣称业务授权通过。 */
final class SessionNodeInputSqlScenario {
  private static final String STATEMENT =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.nodeInput";

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    System.out.println("PASS " + name);
    System.out.flush();
  }

  private static void borrow(SessionQueryContext q, String task) {
    var owner = SessionQueryTransaction.current(q);
    owner.admission();
    owner.locked("tenant-a", task);
    owner.locked("tenant-a", "new");
    var caller =
        new SessionScopeRecords.Caller(
            "tenant-a",
            "new",
            "alice",
            SessionLineagePolicyV5.CAPABILITIES,
            "tools",
            1,
            "SUCCEEDED",
            null,
            "digest");
    var source =
        new SessionScopeRecords.Caller(
            "tenant-a",
            task,
            "alice",
            SessionLineagePolicyV5.CAPABILITIES,
            "tools",
            1,
            "SUCCEEDED",
            null,
            "digest");
    var scope =
        new SessionScopeRecords.Scope(
            1, 1, "alice", "binding", "tools", "{}", "digest", 2, 0, "ACTIVE", null, null, null,
            null, null, "PRESENT", true);
    var authority =
        new SessionScopeRecords.Authority(
            "tenant-a", task, "alice", "tools", "binding", "", "", List.of());
    owner.stageSource(
        caller,
        new SessionScopeRecords.SourceFact(
            source,
            new SessionScopeRecords.History(1, "binding", null),
            scope,
            authority,
            List.of()));
    owner.beginLineageInputs();
    check(owner.borrowLineageNode(q, "tenant-a", task) != null, "current locked source borrow");
  }

  static void verify(JdbcTemplate db, String task) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    historyMapping(db, task, tx, mappers);
    budgetProjection(db, tx, mappers);
    String[] labels = {"request_snapshot", "genesis_snapshot", "graph_snapshot"};
    String[] raw = {"x".repeat(2100) + "字😀", "y".repeat(500) + "字😀", "z".repeat(1700) + "字😀"};
    db.update(
        "UPDATE task SET request_snapshot=?,request_digest=? WHERE id=?",
        raw[0],
        Payloads.hash(raw[0]),
        task);
    db.update(
        "UPDATE task_session_lineage SET genesis_snapshot=?,genesis_digest=?,genesis_bytes=?,graph_snapshot=?,graph_digest=?,graph_bytes=? WHERE task_id=?",
        raw[1],
        Payloads.hash(raw[1]),
        raw[1].getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        raw[2],
        Payloads.hash(raw[2]),
        raw[2].getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        task);
    var before = SessionDependencyScenario.snapshot(db);
    long prior = 0;
    for (boolean folded : List.of(false, true, true)) {
      var q = query();
      var fresh = new LineageRawView[3];
      tx.required(
          q,
          () -> {
            borrow(q, task);
            QueryResults.NodeInputRaw consume =
                (result, label, value) -> {
                  int index = List.of(labels).indexOf(label);
                  if (fresh[index] != null) throw new AssertionError("duplicate raw");
                  fresh[index] = new LineageRawView(value, result, label);
                };
            var args =
                folded
                    ? Map.of("query", q, "tenant", "tenant-a", "id", task, "nodeInputRaw", consume)
                    : Map.of("query", q, "tenant", "tenant-a", "id", task);
            if (folded)
              QueryResults.verifyNodeInputCommand(
                  mappers.getConfiguration().getMappedStatement(STATEMENT), q, args);
            Map<String, Object> row = mappers.selectOne(STATEMENT, args);
            for (int i = 0; i < 3; i++) {
              if (!folded) fresh[i] = new LineageRawView((String) row.get(labels[i]), q);
              check(
                  fresh[i].raw().equals(raw[i])
                      && fresh[i].digest().equals(Payloads.hash(raw[i]))
                      && fresh[i].bytes()
                          == raw[i].getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                  "independent new raw length/digest " + labels[i] + " folded=" + folded);
            }
            SessionQueryTransaction.current(q).releaseLineageSource();
            SessionQueryTransaction.current(q).endLineageInputs();
            return null;
          });
      check(before.equals(SessionDependencyScenario.snapshot(db)), "node input22 unchanged");
      check(q.scratchLive() == 65536, "node input all owners released");
      if (!folded) prior = q.resources().operations();
      System.out.println(
          "NODE_INPUT folded="
              + folded
              + " prior="
              + prior
              + " resources="
              + q.resources()
              + " completed="
              + q.completedWork());
    }
    var faultSource = new SessionFinalMapperFailures.FaultSource(source);
    faultSource.table = "task";
    var faultDb = new JdbcTemplate(faultSource);
    var faultMappers = LedgerMapperSessions.create(faultSource);
    var faultTx =
        new SessionQueryTransaction(faultSource, new DataSourceTransactionManager(faultSource));
    for (String fault : List.of("write", "extra", "foreign", "error", "close", "close-write")) {
      var q = query();
      var foreign = query();
      faultSource.fault = fault;
      faultSource.changed = false;
      faultSource.closed = false;
      faultSource.getters = 0;
      faultSource.primary =
          fault.equals("error")
              ? new AssertionError("node getter Error")
              : new java.sql.SQLException("node close");
      faultSource.secondary =
          fault.equals("error") ? new java.sql.SQLException("node suppressed close") : null;
      faultSource.write =
          () ->
              faultMappers
                  .getMapper(
                      io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                          .SessionContextMapper.class)
                  .account(q, 1, "tenant-a", task);
      Throwable caught = null;
      try {
        faultTx.required(
            q,
            () -> {
              borrow(q, task);
              QueryResults.NodeInputRaw consume =
                  (result, label, value) -> new LineageRawView(value, result, label);
              try (var extra =
                  q.meter(
                      () ->
                          (label, value) -> {
                            if (fault.equals("extra")) faultSource.write.run();
                          })) {
                faultMappers.selectOne(
                    STATEMENT,
                    Map.of(
                        "query",
                        fault.equals("foreign") ? foreign : q,
                        "tenant",
                        "tenant-a",
                        "id",
                        task,
                        "nodeInputRaw",
                        consume));
              }
              return null;
            });
      } catch (RuntimeException | Error failure) {
        caught = failure;
      }
      check(caught != null && q.recordedFailure() != null, "node first error poisons " + fault);
      check(before.equals(SessionDependencyScenario.snapshot(faultDb)), "node22 rollback " + fault);
      check(
          q.scratchLive() == 65536 && foreign.scratchLive() == 65536,
          "node owners released " + fault);
      if (fault.equals("write"))
        check(faultSource.getters == 1, "node invalidation stops next getter");
      if (fault.equals("error"))
        check(
            SessionFinalMapperFailures.contains(caught, faultSource.primary)
                && SessionFinalMapperFailures.contains(faultSource.primary, faultSource.secondary),
            "node Error/suppressed close retained");
      if (!fault.equals("foreign")) check(faultSource.closed, "node real cursor closed " + fault);
    }
  }

  private static void budgetProjection(
      JdbcTemplate db, SessionQueryTransaction tx, org.mybatis.spring.SqlSessionTemplate mappers) {
    var before = SessionDependencyScenario.snapshot(db);
    var q = query();
    tx.required(
        q,
        () -> {
          var sql =
              mappers.getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper
                      .class);
          long start = q.resources().operations();
          var full = sql.selectBudgetForUpdate(q, "tenant-a").getFirst();
          long fullCost = q.resources().operations() - start;
          start = q.resources().operations();
          var locked = sql.lockBudgetForUpdate(q, "tenant-a").getFirst();
          long lockCost = q.resources().operations() - start;
          start = q.resources().operations();
          var model = sql.selectModelBudgetForUpdate(q, "tenant-a").getFirst();
          long modelCost = q.resources().operations() - start;
          check(
              locked.size() == 1 && locked.get("tenant_id").equals(full.get("tenant_id")),
              "budget lock retains row identity");
          check(
              model.size() == 7
                  && model.entrySet().stream()
                      .allMatch(e -> e.getValue().equals(full.get(e.getKey()))),
              "model budget retains every consumed limit/usage");
          System.out.println(
              "BUDGET_PROJECTION full=" + fullCost + " lock=" + lockCost + " model=" + modelCost);
          return null;
        });
    check(
        before.equals(SessionDependencyScenario.snapshot(db)) && q.scratchLive() == 65536,
        "budget projections22 unchanged and owner released");
  }

  private static void historyMapping(
      JdbcTemplate db,
      String task,
      SessionQueryTransaction tx,
      org.mybatis.spring.SqlSessionTemplate mappers) {
    String raw =
        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.history";
    var config = mappers.getConfiguration();
    var command = config.getMappedStatement(raw);
    String automatic = raw + "AutomaticFixture";
    config.addMappedStatement(
        new org.apache.ibatis.mapping.MappedStatement.Builder(
                config,
                automatic,
                command.getSqlSource(),
                org.apache.ibatis.mapping.SqlCommandType.SELECT)
            .resultMaps(
                List.of(
                    new org.apache.ibatis.mapping.ResultMap.Builder(
                            config, automatic, Map.class, List.of())
                        .build()))
            .flushCacheRequired(true)
            .useCache(false)
            .build());
    var before = SessionDependencyScenario.snapshot(db);
    var q = query();
    tx.required(
        q,
        () -> {
          SessionQueryTransaction.current(q).locked("tenant-a", task);
          var args = Map.of("query", q, "tenant", "tenant-a", "taskId", task);
          Map<String, Object> original = mappers.selectOne(automatic, args);
          Map<String, Object> explicit = mappers.selectOne(raw, args);
          check(
              original.equals(explicit),
              "explicit history preserves all original scalar types/values/NULL presence");
          var sized =
              io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryRows.load(
                  mappers, q, "tenant-a", task);
          check(
              original.equals(sized.getFirst()),
              "same size/raw dual read preserves exact history map");
          return null;
        });
    check(
        before.equals(SessionDependencyScenario.snapshot(db)) && q.scratchLive() == 65536,
        "history mapping22 unchanged and owner released");
    System.out.println("HISTORY_MAPPING resources=" + q.resources());
  }
}
