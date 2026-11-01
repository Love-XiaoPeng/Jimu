package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionMetadataAccountingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 隔离MySQL确定缺陷回归；显式合成权限，不代替A03/授权/恢复/ACK验收。 */
public final class SessionRepairSqlScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static SqlSessionTemplate mappers;
  private static SessionQueryTransaction transactions;
  private static JdbcLedger root;
  private static ModelProfiles models;

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS " + name);
    System.out.flush();
  }

  private static String id(int n) {
    return "10000000-0000-0000-0000-%012d".formatted(n);
  }

  private static String oldId(int n) {
    return "00000000-0000-0000-0000-%012d".formatted(n);
  }

  private static SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }

  private static JdbcLedger view(SessionQueryContext query) {
    try {
      var method =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      method.setAccessible(true);
      var view =
          (JdbcLedger) method.invoke(root, query.deadlineNanos(), new QueryResources(0, 0, 0));
      view.queryContext().restrictLineage();
      return view;
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void statement(String name, String sql) {
    var c = mappers.getConfiguration();
    c.addMappedStatement(
        new MappedStatement.Builder(
                c, name, new RawSqlSource(c, sql, Map.class), SqlCommandType.UPDATE)
            .build());
  }

  private static void dependencies() {
    for (int n : List.of(1, 2, 3))
      db.update(
          "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,5,'{}',REPEAT('a',64),TIMESTAMPADD(SECOND,604800,CURRENT_TIMESTAMP(6)))",
          oldId(n));
    for (int n : List.of(1, 2))
      db.update(
          "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant-a',?,'{}',REPEAT('a',64),2,'{}',REPEAT('b',64),2)",
          oldId(n));
    statement(
        "private.graphDigest",
        "UPDATE task_session_lineage SET graph_digest=REPEAT('e',64) WHERE tenant_id=#{tenant} AND task_id=#{taskId}");
    statement(
        "private.actor",
        "UPDATE `actor_access` SET enabled=FALSE WHERE tenant_id=#{tenant} AND actor_id='alice'");
    statement(
        "private.installation",
        "UPDATE `installation` SET epoch=epoch+1 WHERE tenant_id=#{tenant} AND plugin_id='jimu.session.trace'");
    statement(
        "private.unknownTable", "UPDATE worker_process SET stopped=TRUE WHERE id='" + id(99) + "'");
    for (boolean published : List.of(false, true))
      for (String mode :
          List.of(
              "no-query",
              "wrong-query",
              "graph-digest",
              "unknown",
              "actor",
              "installation",
              "conflict",
              "mp-entity",
              "mp-wrapper",
              "cross-target")) {
        var before = SessionDependencyScenario.snapshot(db);
        var query = query();
        var marker = new IllegalStateException("intentional exact snapshot rollback");
        boolean rejected = false;
        try {
          transactions.required(
              query,
              () -> {
                var owner = SessionQueryTransaction.current(query);
                owner.admission();
                for (int n = 1; n <= 8; n++) {
                  var f = SessionDependencyScenario.source(n);
                  owner.stageSource(SessionDependencyScenario.caller(oldId(9)), f);
                  owner.validatedAuthority(f.caller(), f.scope(), f.authority());
                }
                var invalidated = new java.util.concurrent.atomic.AtomicInteger();
                owner.invalidator(
                    SessionRepairSqlScenario.class, keys -> invalidated.incrementAndGet());
                if (published) {
                  owner.locked("tenant-a", oldId(9));
                  owner.locked("tenant-a", oldId(1));
                  owner.locked("tenant-a", oldId(2));
                  var f = SessionDependencyScenario.source(9);
                  var a =
                      new SessionScopeRecords.Authority(
                          "tenant-a",
                          oldId(9),
                          "alice",
                          "tools",
                          "binding",
                          "",
                          "",
                          List.of(
                              new SessionScopeRecords.AuthorityTarget(
                                  oldId(1), "pins", "binding", 0, "", "", "{}"),
                              new SessionScopeRecords.AuthorityTarget(
                                  oldId(2), "pins", "binding", 0, "", "", "{}")));
                  owner.scope(
                      SessionDependencyScenario.caller(oldId(9)),
                      new SessionScopeRecords.Validated(
                          f.scope(),
                          a,
                          List.of(
                              SessionDependencyScenario.caller(oldId(1)),
                              SessionDependencyScenario.caller(oldId(2)))),
                      () -> {});
                }
                String mapped = "private.graphDigest";
                var params = new HashMap<String, Object>();
                params.put("query", query);
                params.put("tenant", "tenant-a");
                params.put("taskId", oldId(1));
                if (mode.equals("no-query")) params.remove("query");
                if (mode.equals("wrong-query")) params.put("query", query());
                if (mode.equals("unknown")) mapped = "private.unknownTable";
                if (mode.equals("actor")) mapped = "private.actor";
                if (mode.equals("installation")) mapped = "private.installation";
                if (mode.equals("conflict")) {
                  params.put("caller", oldId(9));
                  mapped =
                      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.purge";
                  params.put("id", oldId(1));
                }
                if (mode.equals("cross-target")) {
                  params.put("id", oldId(2));
                  params.put("taskId", oldId(2));
                  mapped =
                      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.purge";
                }
                if (mode.startsWith("mp-")) {
                  var entity = new SessionLineageEntity();
                  entity.setTenantId("tenant-a");
                  entity.setTaskId(oldId(mode.equals("mp-entity") && !published ? 3 : 1));
                  entity.setGenesisSnapshot("{}");
                  entity.setGraphSnapshot("{}");
                  entity.setGenesisDigest("a".repeat(64));
                  entity.setGraphDigest("b".repeat(64));
                  entity.setGenesisBytes(2L);
                  entity.setGraphBytes(2L);
                  if (mode.equals("mp-wrapper")) {
                    params.put("et", null);
                    params.put(
                        "ew",
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                SessionLineageEntity>()
                            .eq(SessionLineageEntity::getTenantId, "tenant-a")
                            .eq(SessionLineageEntity::getTaskId, oldId(2))
                            .set(SessionLineageEntity::getGraphDigest, "e".repeat(64)));
                    mapped =
                        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.update";
                  } else {
                    params.clear();
                    params.put("query", query);
                    var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
                    for (String key : meta.getGetterNames()) params.put(key, meta.getValue(key));
                    mapped =
                        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.insert";
                  }
                }
                try {
                  int changed = mappers.update(mapped, params);
                  check(
                      !published && !mode.endsWith("query") && changed == 1,
                      "actual staging DML " + mode);
                } catch (RuntimeException failure) {
                  if (!published && !mode.endsWith("query")) throw failure;
                  String message = failure.toString();
                  check(
                      message.contains("Session write requires its owning query")
                          || message.contains("dependency"),
                      "guard rejects before SQL " + mode);
                }
                if (published || mode.endsWith("query"))
                  return null; // caught failure must still poison finish
                check(invalidated.get() > 0, "staging invalidator ran " + mode);
                check(
                    owner.stagedSource(
                            SessionDependencyScenario.caller(oldId(9)),
                            oldId(
                                mode.equals("mp-entity") ? 3 : mode.equals("cross-target") ? 2 : 1))
                        == null,
                    "staging fact invalidated " + mode);
                throw marker;
              });
        } catch (RuntimeException expected) {
          if (!published && !mode.endsWith("query") && expected != marker) throw expected;
          rejected = true;
        }
        check(
            rejected && before.equals(SessionDependencyScenario.snapshot(db)),
            "independent 22-table rollback " + published + " " + mode);
      }
  }

  private static Lease seedModel(int n, String state) {
    var model = models.selected("tenant-a");
    String snapshot = Payloads.json(model), digest = ModelProfiles.digest(model);
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools,current_attempt_id,worker_id,fence,lease_until,model_snapshot,model_digest,token_held) VALUES('tenant-a',?,'alice',?,REPEAT('a',64),'{}','repair',?,1,?,TIMESTAMPADD(SECOND,150,CURRENT_TIMESTAMP(6)),2,3,?,?,1,TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)),?,?,100)",
        id(n),
        "repair-" + n,
        SessionLineagePolicyV5.CAPABILITIES,
        state,
        id(n + 100),
        id(99),
        snapshot,
        digest);
    db.update(
        "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES('tenant-a',?,?,?,1,'ACTIVE')",
        id(n + 100),
        id(n),
        id(99));
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,dispatch_id,dispatches) VALUES('tenant-a',?,?,?,1,'RUNNING','{}',REPEAT('a',64),?,1)",
        id(n + 200),
        id(n),
        id(n + 100),
        id(n + 300));
    db.update(
        "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state) VALUES('tenant-a',?,?,?,?,REPEAT('a',64),'DISPATCHED')",
        id(n + 300),
        id(n),
        id(n + 200),
        digest);
    String binding = new SessionContracts(5).binding();
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,5,?,?,TIMESTAMPADD(SECOND,604800,CURRENT_TIMESTAMP(6)))",
        id(n),
        binding,
        Payloads.hash(binding));
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a',?,?,'SESSION_HISTORY_STORAGE',8388608,'RESERVED','KNOWN')",
        id(n) + ":session-history",
        id(n));
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,dispatch_id,category,amount,state,usage_quality) VALUES('tenant-a',?,?,?,'MODEL',100,'RESERVED','UNKNOWN')",
        id(n + 300),
        id(n),
        id(n + 300));
    db.update(
        "UPDATE tenant_budget SET token_held=token_held+100,history_storage_held=history_storage_held+8388608 WHERE tenant_id='tenant-a'");
    db.update(
        "UPDATE task_session_history SET body_bytes=? WHERE tenant_id='tenant-a' AND task_id=?",
        SessionRepairSqlOracle.inspect(db, id(n)).body(),
        id(n));
    return new Lease(
        "tenant-a",
        "alice",
        id(n),
        id(n + 100),
        1,
        id(99),
        Instant.now().plusSeconds(150),
        "repair",
        List.of("session_event_read", "session_search", "session_trace"),
        1);
  }

  private static void settlement() {
    for (boolean usage : List.of(false, true)) {
      int n = usage ? 21 : 20;
      var lease = seedModel(n, usage ? "CANCEL_REQUESTED" : "RUNNING");
      root.modelFailed(
          lease,
          id(n + 300),
          "FIXTURE_FAILED",
          usage ? new ModelUsage(3L, 4L, 7L, null, null, null, null, null) : null);
      var r =
          db.queryForMap(
              "SELECT state,actual,amount FROM reservation WHERE tenant_id='tenant-a' AND id=?",
              id(n + 300));
      check(
          (usage ? "SETTLED" : "HELD_UNKNOWN").equals(r.get("state")),
          "modelFailed real SQL usage=" + usage);
      if (usage)
        check(
            ((Number) r.get("actual")).longValue() == 7,
            "known usage and token settlement committed");
      presentBalance(n);
    }
    var rejectedLease = seedModel(23, "RUNNING");
    boolean rejected = false;
    try {
      root.modelResponded(
          rejectedLease,
          id(223),
          id(323),
          "{}",
          List.of(
              new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                  "0", "fixture", "session_trace", "x".repeat(65536))),
          7,
          new ModelUsage(3L, 4L, 7L, null, null, null, null, null));
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure expected) {
      check("TOOL_INPUT_INVALID".equals(expected.code()), "ordinary rejection whitelist code");
      rejected = true;
    }
    check(
        rejected
            && db.queryForObject(
                    "SELECT actual FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                    Long.class,
                    id(323))
                == 7
            && "TOOL_INPUT_INVALID"
                .equals(
                    db.queryForObject(
                        "SELECT code FROM model_dispatch WHERE tenant_id='tenant-a' AND id=?",
                        String.class,
                        id(323)))
            && db.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='model.rejected'",
                    Long.class,
                    id(23))
                == 1,
        "ordinary response rejection usage token and audit commit before throw");
    presentBalance(23);
    var lease = seedModel(22, "RUNNING");
    String fence =
        "io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.CommitFenceMapper.settlementFence";
    for (String mode :
        List.of(
            "original",
            "wrong-task",
            "wrong-step",
            "wrong-dispatch",
            "old-fence",
            "new-attempt",
            "expired",
            "responded",
            "recovered")) {
      if (mode.equals("wrong-step"))
        db.update(
            "UPDATE model_dispatch SET step_id=? WHERE tenant_id='tenant-a' AND id=?",
            id(221),
            id(322));
      if (mode.equals("expired"))
        db.update(
            "UPDATE task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
            id(22));
      if (mode.equals("responded"))
        db.update(
            "UPDATE step SET response_snapshot='{}' WHERE tenant_id='tenant-a' AND id=?", id(222));
      if (mode.equals("recovered")) {
        db.update(
            "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES('tenant-a',?,?,?,2,'ACTIVE')",
            id(422),
            id(22),
            id(99));
        db.update(
            "UPDATE task SET current_attempt_id=?,fence=2 WHERE tenant_id='tenant-a' AND id=?",
            id(422),
            id(22));
      }
      var q = query();
      var p = new HashMap<String, Object>();
      p.put("query", q);
      p.put("tenant", "tenant-a");
      p.put("task", mode.equals("wrong-task") ? id(20) : id(22));
      p.put(
          "attempt",
          mode.equals("new-attempt") ? id(120) : mode.equals("recovered") ? id(422) : id(122));
      p.put("worker", id(99));
      p.put("fence", mode.equals("old-fence") ? 0L : mode.equals("recovered") ? 2L : 1L);
      p.put("dispatch", mode.equals("wrong-dispatch") ? id(320) : id(322));
      int changed = transactions.required(q, () -> mappers.update(fence, p));
      check(
          changed == (List.of("original", "recovered").contains(mode) ? 1 : 0),
          "real settlement fence " + mode);
      db.update(
          "UPDATE model_dispatch SET step_id=? WHERE tenant_id='tenant-a' AND id=?",
          id(222),
          id(322));
      db.update(
          "UPDATE step SET response_snapshot=NULL WHERE tenant_id='tenant-a' AND id=?", id(222));
      db.update(
          "UPDATE task SET lease_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
          id(22));
    }
  }

  private static void presentBalance(int n) {
    var expected = SessionRepairSqlOracle.inspect(db, id(n));
    var balance =
        db.queryForMap(
            "SELECT h.metadata_bytes,h.body_bytes,h.reserved_bytes,h.held_bytes,r.amount,r.actual,r.state FROM task_session_history h JOIN reservation r ON r.tenant_id=h.tenant_id AND r.id=CONCAT(h.task_id,':session-history') WHERE h.tenant_id='tenant-a' AND h.task_id=?",
            id(n));
    check(
        ((Number) balance.get("metadata_bytes")).longValue() == expected.metadata()
            && ((Number) balance.get("body_bytes")).longValue() == expected.body()
            && ((Number) balance.get("reserved_bytes")).longValue() == 8388608
            && ((Number) balance.get("held_bytes")).longValue() == 8388608
            && ((Number) balance.get("amount")).longValue() == 8388608
            && balance.get("actual") == null
            && "RESERVED".equals(balance.get("state")),
        "independent PRESENT 18 projections BODY and reservation " + n + " " + expected);
  }

  private static void purge() {
    seedModel(30, "SUCCEEDED");
    db.update(
        "UPDATE task_attempt SET state='SUCCEEDED',quiescent=TRUE,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND task_id=?",
        id(30));
    db.update(
        "UPDATE reservation SET state='SETTLED',actual=1,usage_quality='KNOWN' WHERE tenant_id='tenant-a' AND task_id=? AND category='MODEL'",
        id(30));
    db.update(
        "UPDATE model_dispatch SET state='FAILED' WHERE tenant_id='tenant-a' AND task_id=?",
        id(30));
    db.update(
        "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant-a',?,'{}',REPEAT('a',64),2,'{}',REPEAT('b',64),2)",
        id(30));
    db.update(
        "INSERT INTO task_session_scope(tenant_id,caller_task_id,owner_actor_id,caller_binding_digest,caller_tools_digest,authority_snapshot,authority_digest,authority_bytes,candidate_count,created_at,retain_until,next_validation_at) SELECT tenant_id,id,'alice',REPEAT('a',64),REPEAT('b',64),'{}',REPEAT('c',64),2,0,created_at,TIMESTAMPADD(SECOND,604800,created_at),CURRENT_TIMESTAMP(6) FROM task WHERE tenant_id='tenant-a' AND id=?",
        id(30));
    db.update(
        "UPDATE task_session_history SET body_bytes=0 WHERE tenant_id='tenant-a' AND task_id=?",
        id(30));
    var present = SessionRepairSqlOracle.inspect(db, id(30));
    check(
        present.body() > 0
            && present.body()
                != db.queryForObject(
                    "SELECT body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                    Long.class,
                    id(30)),
        "independent oracle detects omitted BODY even with body_bytes zero");
    db.update(
        "UPDATE task_session_history SET body_bytes=? WHERE tenant_id='tenant-a' AND task_id=?",
        present.body(),
        id(30));
    long heldBefore =
        db.queryForObject(
            "SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'",
            Long.class);
    var before = SessionDependencyScenario.snapshot(db);
    for (boolean inject : List.of(true, false)) {
      var tail = new IllegalStateException("injected tail after finalizer");
      var tailReached = new java.util.concurrent.atomic.AtomicBoolean();
      var v = view(query());
      var q = v.queryContext();
      boolean failed = false;
      try {
        transactions.required(
            q,
            () -> {
              var task = v.locked("tenant-a", id(30));
              var retention = v.historyRetentionRecords.bind(task);
              v.historyRetention.purge(retention);
              SessionQueryTransaction.current(q)
                  .account(
                      id(30),
                      true,
                      c ->
                          new SessionMetadataAccountingServiceImpl()
                              .account(
                                  new MapperSessionMetadataAccounting(v.sessions, task, true), c));
              if (inject)
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      public void beforeCommit(boolean readOnly) {
                        tailReached.set(true);
                        throw tail;
                      }
                    });
              return null;
            });
      } catch (RuntimeException failure) {
        failed = true;
        if (!inject || failure != tail) throw failure;
      }
      check(failed == inject, "purge finalizer and tail outcome " + inject);
      check(tailReached.get() == inject, "purge reached exact beforeCommit injection " + inject);
      if (inject)
        check(
            before.equals(SessionDependencyScenario.snapshot(db)),
            "purge full snapshot rolled back after finalizer");
    }
    var balance =
        db.queryForMap(
            "SELECT h.metadata_bytes,h.body_bytes,h.held_bytes,h.reserved_bytes,r.amount,r.actual,r.state FROM task_session_history h JOIN reservation r ON r.tenant_id=h.tenant_id AND r.id=CONCAT(h.task_id,':session-history') WHERE h.tenant_id='tenant-a' AND h.task_id=?",
            id(30));
    long metadata = ((Number) balance.get("metadata_bytes")).longValue();
    var independent = SessionRepairSqlOracle.inspect(db, id(30));
    check(
        independent.metadata() == metadata && independent.body() == 0,
        "independent INFORMATION_SCHEMA 18 projections and every BODY " + independent);
    check(
        heldBefore
                - db.queryForObject(
                    "SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'",
                    Long.class)
            == 8388608 - metadata,
        "tenant exact once refund reserved minus retained");
    check(
        ((Number) balance.get("amount")).longValue() == 8388608
            && ((Number) balance.get("reserved_bytes")).longValue() == 8388608
            && ((Number) balance.get("held_bytes")).longValue() == metadata
            && ((Number) balance.get("actual")).longValue() == metadata
            && ((Number) balance.get("body_bytes")).longValue() == 0,
        "PURGED original reserve distinct from settled metadata " + metadata);
    var after = SessionDependencyScenario.snapshot(db);
    // cleanup predicates must make the second cleanup a no-op.
    root.historyRetention.cleanup();
    check(
        after.equals(SessionDependencyScenario.snapshot(db)), "second cleanup snapshot unchanged");
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    org.flywaydb.core.Flyway.configure()
        .dataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"))
        .load()
        .migrate();
    source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    db = new JdbcTemplate(source);
    db.update(
        "INSERT INTO tenant_budget(tenant_id,history_storage_limit) VALUES('tenant-a',100663296)");
    db.update("INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice')");
    db.update(
        "INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','jimu.session.trace','0.1.0',REPEAT('a',64))");
    db.update(
        "INSERT INTO worker_process(id,host_id,pid,start_ticks) VALUES(?,?,123,1)", id(99), id(98));
    mappers = LedgerMapperSessions.create(source);
    transactions = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    models = new ModelProfiles(env);
    root = new JdbcLedger(source, models, null, null, null);
    SessionDependencyScenario.main(args);
    dependencies();
    settlement();
    purge();
    System.out.println(
        "REPAIR_SQL_COMPLETE synthetic authority; actual Mapper/transaction/finalizer; no A03 or new-fence execution acceptance");
  }
}
