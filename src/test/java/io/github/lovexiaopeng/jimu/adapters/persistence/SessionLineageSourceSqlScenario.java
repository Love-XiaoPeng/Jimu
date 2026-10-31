package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 新切片的独立MySQL夹具；不执行原受理/finish/response业务probe。 */
public final class SessionLineageSourceSqlScenario {
  @org.apache.ibatis.plugin.Intercepts({
    @org.apache.ibatis.plugin.Signature(
        type = org.apache.ibatis.executor.statement.StatementHandler.class,
        method = "prepare",
        args = {java.sql.Connection.class, Integer.class}),
    @org.apache.ibatis.plugin.Signature(
        type = org.apache.ibatis.executor.Executor.class,
        method = "query",
        args = {
          org.apache.ibatis.mapping.MappedStatement.class,
          Object.class,
          org.apache.ibatis.session.RowBounds.class,
          org.apache.ibatis.session.ResultHandler.class
        })
  })
  public static final class Fault implements org.apache.ibatis.plugin.Interceptor {
    String mode;

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      if (invocation.getTarget()
          instanceof org.apache.ibatis.executor.statement.StatementHandler handler) {
        var bound = handler.getBoundSql();
        String sql = bound.getSql();
        if (mode != null
            && mode.startsWith("metadata-")
            && sql.contains("s.authority_bytes")
            && sql.contains("t.owner_actor_id")) {
          sql =
              switch (mode) {
                case "metadata-column" ->
                    sql.replace("t.request_snapshot", "t.request_digest AS request_snapshot");
                case "metadata-charset" ->
                    sql.replace(
                        "t.request_snapshot",
                        "CONVERT(t.request_snapshot USING latin1) AS request_snapshot");
                default -> sql.replace("h.history_version", "t.goal,h.history_version");
              };
          org.apache.ibatis.reflection.SystemMetaObject.forObject(bound).setValue("sql", sql);
        }
        return invocation.proceed();
      }
      String id = ((org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0]).getId();
      if (id.endsWith("SessionLineageMapper.sourceFact")) {
        if ("sql-error".equals(mode))
          throw new org.springframework.dao.DataAccessResourceFailureException(
              "source SQL", new java.sql.SQLException("injected", "08006"));
        if ("error".equals(mode)) throw new AssertionError("injected source Error");
      }
      Object result = invocation.proceed();
      if (id.endsWith("SessionLineageMapper.sourceFact") && mode.startsWith("column:")) {
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) result;
        String key = mode.substring(7);
        var row = rows.getFirst();
        Object value = row.get(key);
        row.put(
            key,
            value instanceof Number n
                ? n.longValue() + 1
                : value instanceof LocalDateTime date ? date.plusSeconds(1) : "!" + value);
      }
      return result;
    }
  }

  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private static final String CALLER = "00000000-0000-4000-8000-000000000002";

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }

  private static JdbcLedger view(JdbcLedger root) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    var v =
        (JdbcLedger)
            method.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    v.queryContext().restrictLineage();
    return v;
  }

  public static void main(String[] args) throws Exception {
    var p = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      p.load(in);
    }
    var ds =
        new DriverManagerDataSource(
            p.getProperty("jimu.db.url"),
            p.getProperty("jimu.db.user"),
            p.getProperty("jimu.db.password"));
    org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
    var db = new JdbcTemplate(ds);
    var mappers = LedgerMapperSessions.create(ds);
    var root = new JdbcLedger(ds, null, null, null, null, mappers);
    var fault = new Fault();
    mappers.getConfiguration().addInterceptor(fault);
    String request =
        "{\"history_session_ids\":[],\"request\":{\"capabilityIds\":[\"session_event_read\",\"session_search\",\"session_trace\"]}}";
    String tools = "c".repeat(64);
    var contracts = new SessionContracts(5);
    var created = LocalDateTime.now(java.time.Clock.systemUTC()).withNano(0);
    var retain = created.plusDays(7);
    String timestamp =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").format(created);
    String until = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").format(retain);
    db.update("INSERT INTO tenant_budget(tenant_id) VALUES('tenant')");
    db.update("INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant','owner')");
    var codec = new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts();
    for (String id : List.of(ID, CALLER)) {
      db.update(
          "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,created_at,deadline,max_models,max_tools,tools_digest) VALUES('tenant',?,'owner',?,?,?,'source-fixture',?,1,'SUCCEEDED',?,?,2,3,?)",
          id,
          id,
          Payloads.hash(request),
          request,
          SessionLineagePolicyV5.CAPABILITIES,
          created,
          retain,
          tools);
      db.update(
          "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until,next_seq) VALUES('tenant',?,5,?,?,?,1)",
          id,
          contracts.historySnapshot(null),
          Payloads.hash(contracts.binding()),
          retain);
      String authority =
          "{\"version\":1,\"tenant_id\":\"tenant\",\"caller_session_id\":\""
              + id
              + "\",\"owner_actor_id\":\"owner\",\"caller_binding_sha256\":\""
              + Payloads.hash(contracts.binding())
              + "\",\"caller_tools_sha256\":\""
              + tools
              + "\",\"authority_policy\":\"explicit-terminal-closed-v5\",\"created_at\":\""
              + timestamp
              + "\",\"retain_until\":\""
              + until
              + "\",\"targets\":[]}";
      db.update(
          "INSERT INTO task_session_scope(tenant_id,caller_task_id,owner_actor_id,caller_binding_digest,caller_tools_digest,authority_snapshot,authority_digest,authority_bytes,candidate_count,created_at,retain_until,next_validation_at) VALUES('tenant',?,'owner',?,?,?,?,?,0,?,?,?)",
          id,
          Payloads.hash(contracts.binding()),
          tools,
          authority,
          Payloads.hash(authority),
          authority.length(),
          created,
          retain,
          created);

      var g =
          new io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis(
              1,
              id,
              "ROOT",
              timestamp,
              Payloads.hash(request),
              Payloads.hash(authority),
              Payloads.hash(contracts.binding()),
              null,
              null);
      String genesis = codec.genesis(g);
      String graph =
          codec.graph(
              new io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Graph(
                  1,
                  id,
                  Payloads.hash(authority),
                  List.of(
                      new io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Node(
                          id, genesis, Payloads.hash(genesis)))));
      db.update(
          "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant',?,?,?,?,?,?,?)",
          id,
          genesis,
          Payloads.hash(genesis),
          genesis.length(),
          graph,
          Payloads.hash(graph),
          graph.length());
    }
    var modes =
        args.length > 1
            ? new java.util.ArrayList<String>(
                List.of("graph", "metadata-column", "metadata-charset", "metadata-extra"))
            : new java.util.ArrayList<String>(
                List.of(
                    "valid",
                    "fallback",
                    "request",
                    "tools",
                    "binding",
                    "version",
                    "epoch",
                    "authority",
                    "owner",
                    "sql-error",
                    "error"));
    if (args.length > 1)
      for (String key :
          List.of(
              "tenant_id",
              "id",
              "created_at",
              "request_digest",
              "capabilities",
              "installation_epoch",
              "state",
              "authority_bytes",
              "scope_content",
              "record_version",
              "authority_version",
              "caller_tools_digest",
              "caller_binding_digest",
              "candidate_count",
              "scope_created",
              "scope_retain",
              "history_retain")) modes.add("column:" + key);
    for (String mode : modes) {
      fault.mode = mode;
      var v = view(root);
      var q = v.queryContext();
      var marker = new IllegalStateException("deliberate fixture rollback");
      Throwable failure = null;
      try {
        v.sessionTransaction(
            () -> {
              var o = SessionQueryTransaction.current(q);
              o.admission();
              v.sessionScopes.lock("tenant", List.of(ID, CALLER));
              var caller =
                  new SessionScopeRecords.Caller(
                      "tenant",
                      CALLER,
                      "owner",
                      SessionLineagePolicyV5.CAPABILITIES,
                      tools,
                      1,
                      "SUCCEEDED",
                      created,
                      Payloads.hash(request));
              var source = v.sessionScopes.eligibility.sourceFacts(caller, ID, true);
              check(o.stagedSource(caller, ID) == source, "genuine sourceFacts staged " + mode);
              if (mode.equals("request"))
                db.update(
                    "UPDATE task SET request_snapshot=REPLACE(request_snapshot,'trace','tracE') WHERE tenant_id='tenant' AND id=?",
                    ID);
              if (mode.equals("tools"))
                db.update(
                    "UPDATE task SET tools_digest=REPEAT('d',64) WHERE tenant_id='tenant' AND id=?",
                    ID);
              if (mode.equals("binding"))
                db.update(
                    "UPDATE task_session_history SET binding_digest=REPEAT('d',64) WHERE tenant_id='tenant' AND task_id=?",
                    ID);
              if (mode.equals("epoch"))
                db.update(
                    "UPDATE task SET installation_epoch=2 WHERE tenant_id='tenant' AND id=?", ID);
              if (mode.equals("authority"))
                db.update(
                    "UPDATE task_session_scope SET authority_digest=REPEAT('d',64) WHERE tenant_id='tenant' AND caller_task_id=?",
                    ID);
              if (mode.equals("owner"))
                db.update(
                    "UPDATE task_session_scope SET owner_actor_id='other' WHERE tenant_id='tenant' AND caller_task_id=?",
                    ID);
              if (mode.equals("version"))
                db.update(
                    "UPDATE task_session_history SET history_version=4 WHERE tenant_id='tenant' AND task_id=?",
                    ID);
              var records = new MapperSessionLineageRecords(v);
              if (mode.equals("graph")) {
                var service =
                    new io.github.lovexiaopeng.jimu.kernel.session.service.impl
                        .SessionLineageServiceImpl(records);
                var proof = service.verify("tenant", ID);
                check(
                    proof.nodes().size() == 1,
                    "SourceFact to fresh standalone Stored and full graph validation");
                var own = v.sessionScopes.records.scope(caller);
                var authority = v.sessionScopes.eligibility.snapshot(caller, own);
                o.scope(
                    caller,
                    new SessionScopeRecords.Validated(
                        own,
                        authority,
                        List.of(source.caller()),
                        java.util.Map.of(ID, source.scope())),
                    () -> {});
                o.graph("tenant", CALLER, proof);
                check(
                    o.stagedSource(caller, ID) == null,
                    "graph publication ends source staging after synchronous borrow");
                throw marker;
              }
              var result =
                  records.withProof(
                      () -> records.fact("tenant", mode.equals("fallback") ? CALLER : ID));
              check(
                  result.id().equals(mode.equals("fallback") ? CALLER : ID),
                  "Fact returned " + mode);
              check(
                  q.statements().keySet().stream()
                      .anyMatch(
                          s ->
                              s.endsWith(
                                  mode.equals("fallback")
                                      ? "SessionLineageMapper.fact"
                                      : "SessionLineageMapper.sourceFact")),
                  "actual named SQL route " + mode);
              throw marker;
            },
            q.deadlineNanos());
      } catch (Throwable e) {
        failure = e;
      }
      if (mode.equals("valid") || mode.equals("fallback") || mode.equals("graph"))
        check(failure == marker, "new SQL/old SQL completed before fixture rollback " + mode);
      else {
        check(failure != null && failure != marker, "fresh field mismatch rejected " + mode);
        check(q.recordedFailure() != null, "failed read poisons Work " + mode);
        check(
            q.statements().keySet().stream()
                .noneMatch(s -> s.endsWith("SessionLineageMapper.fact")),
            "no full-reader fallback after new SQL " + mode);
      }
      check(q.scratchLive() == 65536, "all owners released " + mode);
      System.out.println(
          "SOURCE_QUERY "
              + mode
              + " "
              + q.resources()
              + " failure="
              + (failure == null ? "none" : failure.getClass().getSimpleName()));
    }
    System.out.println("SOURCE_SQL_COMPLETE scoped fixture only; no whole business acceptance");
  }
}
