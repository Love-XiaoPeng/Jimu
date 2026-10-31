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
public final class SessionLineageNodeInputSqlScenario {
  private static final java.util.ArrayList<java.util.Map<String, Object>> TIMELINE =
      new java.util.ArrayList<>();
  private static long origin;

  private static void event(String name, long start, Object detail) {
    TIMELINE.add(
        java.util.Map.of(
            "name",
            name,
            "startMicros",
            (start - origin) / 1000,
            "elapsedMicros",
            (System.nanoTime() - start) / 1000,
            "detail",
            String.valueOf(detail)));
  }

  private static java.util.Map<String, Object> fingerprint(Object value) {
    String wire = Payloads.json(value);
    return java.util.Map.of(
        "bytes",
        wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "sha256",
        Payloads.hash(wire),
        "value",
        wire);
  }

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
    java.util.Map<String, Object> previous;
    final Object fillOwner = new Object();

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      long start = System.nanoTime();
      String label = invocation.getMethod().getName();
      try {
        return invoke(invocation);
      } finally {
        event("mybatis." + label, start, mode);
      }
    }

    private Object invoke(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      if (invocation.getTarget()
          instanceof org.apache.ibatis.executor.statement.StatementHandler handler) {
        var bound = handler.getBoundSql();
        String sql = bound.getSql();
        if (mode != null
            && mode.startsWith("metadata-")
            && sql.contains("l.genesis_snapshot")
            && !sql.contains("OCTET_LENGTH")) {
          sql =
              switch (mode) {
                case "metadata-column" ->
                    sql.replace("t.request_snapshot", "t.request_digest AS request_snapshot");
                case "metadata-charset" ->
                    sql.replace(
                        "t.request_snapshot",
                        "CONVERT(t.request_snapshot USING latin1) AS request_snapshot");
                case "metadata-type" ->
                    sql.replace(
                        "l.record_version", "CAST(l.record_version AS CHAR) AS record_version");
                default -> sql.replace("l.record_version", "t.goal,l.record_version");
              };
          org.apache.ibatis.reflection.SystemMetaObject.forObject(bound).setValue("sql", sql);
        }
        if (mode != null && mode.startsWith("size-metadata-") && sql.contains("OCTET_LENGTH")) {
          sql =
              switch (mode) {
                case "size-metadata-label" ->
                    sql.replace("AS genesis_bytes", "AS wrong_genesis_bytes");
                case "size-metadata-type" ->
                    sql.replace(
                        "OCTET_LENGTH(l.graph_snapshot)",
                        "CAST(OCTET_LENGTH(l.graph_snapshot) AS CHAR)");
                default -> sql.replace(" AS request_bytes", " AS request_bytes,t.goal");
              };
          org.apache.ibatis.reflection.SystemMetaObject.forObject(bound).setValue("sql", sql);
        }
        event("sql.prepare", System.nanoTime(), fingerprint(sql));
        return invocation.proceed();
      }
      String id = ((org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0]).getId();
      if (id.endsWith("SessionLineageMapper.nodeInput")) {
        if ("sql-error".equals(mode))
          throw new org.springframework.dao.DataAccessResourceFailureException(
              "source SQL", new java.sql.SQLException("injected", "08006"));
        if ("error".equals(mode)) throw new AssertionError("injected source Error");
      }
      Object result = invocation.proceed();
      if (id.endsWith("SessionLineageMapper.nodeInputSizes") && mode.equals("allocation-refusal")) {
        var query =
            (io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext)
                ((java.util.Map<?, ?>) invocation.getArgs()[1]).get("query");
        query.retainScratch(fillOwner, 1048576 - query.scratchLive());
      }

      if (id.endsWith("SessionLineageMapper.nodeInput")) {
        @SuppressWarnings("unchecked")
        var rows = (List<java.util.Map<String, Object>>) result;
        var values = new java.util.ArrayList<java.util.Map<String, String>>();
        for (var row : rows) {
          var fields = new java.util.TreeMap<String, String>();
          row.forEach((key, value) -> fields.put(key, String.valueOf(value)));
          values.add(fields);
        }
        event("sql.sourceFact.result", System.nanoTime(), fingerprint(values));
        if (mode.equals("repeat")) {
          if (previous == null) previous = new java.util.HashMap<>(rows.getFirst());
          else
            for (String key : List.of("request_snapshot", "genesis_snapshot", "graph_snapshot"))
              rows.getFirst().put(key, previous.get(key));
        }
        if (mode.equals("genesis-pin") || mode.equals("graph-pin")) {
          String key = mode.equals("genesis-pin") ? "genesis_snapshot" : "graph_snapshot";
          String digestKey = mode.equals("genesis-pin") ? "genesis_digest" : "graph_digest";
          var json =
              (com.fasterxml.jackson.databind.node.ObjectNode)
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(rows.getFirst().get(key).toString());
          json.put(
              mode.equals("genesis-pin") ? "binding_sha256" : "authority_sha256", "d".repeat(64));
          String changed = Payloads.json(json);
          check(
              changed.length() == rows.getFirst().get(key).toString().length(),
              "same-length independent pin mutation");
          rows.getFirst().put(key, changed);
          rows.getFirst().put(digestKey, Payloads.hash(changed));
        }
        if (mode.equals("missing")) rows.clear();
        if (mode.equals("duplicate")) rows.add(new java.util.HashMap<>(rows.getFirst()));
        if (mode.equals("request-same-length"))
          rows.getFirst()
              .put(
                  "request_snapshot",
                  rows.getFirst().get("request_snapshot").toString().replace("trace", "tracE"));
        if (mode.equals("genesis-same-length"))
          rows.getFirst()
              .put(
                  "genesis_snapshot",
                  rows.getFirst().get("genesis_snapshot").toString().replace("ROOT", "ROOt"));
        if (mode.equals("graph-same-length"))
          rows.getFirst()
              .put(
                  "graph_snapshot",
                  rows.getFirst()
                      .get("graph_snapshot")
                      .toString()
                      .replace("ROOT", "ROOt")
                      .replace("genesis_policy", "genesis_policY"));
        if (mode.startsWith("column:")) {
          String key = mode.substring(7);
          var row = rows.getFirst();
          Object value = row.get(key);
          Object changed =
              value instanceof Number n
                  ? n.longValue() + 1
                  : value instanceof LocalDateTime date ? date.plusSeconds(1) : "!" + value;
          // 在实际SQL/Connector完整返回后注入原有单字段故障；不以手工Map替代SQL。
          row.put(key, changed);
          event(
              "fixture.resultMutation",
              System.nanoTime(),
              fingerprint(
                  java.util.Map.of(
                      "key",
                      key,
                      "before",
                      String.valueOf(value),
                      "after",
                      String.valueOf(changed))));
        }
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
    long start = System.nanoTime();
    var v =
        (JdbcLedger)
            method.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    v.queryContext().restrictLineage();
    event("queryView", start, v.queryContext().deadlineNanos());
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
    boolean negatives = args.length > 1 && args[1].equals("negatives");
    var modes = new java.util.ArrayList<String>();
    if (!negatives) modes.addAll(List.of("graph", "require", "repeat"));
    else {
      modes.addAll(
          List.of(
              "metadata-column",
              "metadata-charset",
              "metadata-extra",
              "metadata-type",
              "missing",
              "duplicate",
              "request-same-length",
              "genesis-same-length",
              "graph-same-length",
              "sql-error",
              "error"));
      for (String key :
          List.of(
              "tenant_id",
              "id",
              "request_digest",
              "lineage_tenant",
              "lineage_task",
              "record_version",
              "genesis_digest",
              "genesis_bytes",
              "graph_digest",
              "graph_bytes",
              "content_state")) modes.add("column:" + key);
    }
    if (args.length > 1 && args[1].equals("repair-four")) {
      modes.clear();
      modes.addAll(
          List.of(
              "genesis-same-length",
              "graph-same-length",
              "column:genesis_digest",
              "column:graph_digest"));
    }
    if (args.length > 1 && args[1].equals("boundaries")) {
      modes.clear();
      modes.addAll(
          List.of(
              "size-metadata-label",
              "size-metadata-type",
              "size-metadata-extra",
              "allocation-refusal",
              "genesis-pin",
              "graph-pin"));
    }
    int failures = 0;
    for (String mode : modes) {
      TIMELINE.clear();
      origin = System.nanoTime();
      fault.mode = mode;
      var v = view(root);
      var q = v.queryContext();
      var marker = new IllegalStateException("deliberate fixture rollback");
      Throwable failure = null;
      Throwable assertion = null;
      try {
        long transactionStart = System.nanoTime();
        v.sessionTransaction(
            () -> {
              event("transaction.begin", transactionStart, "callback entered");
              var o = SessionQueryTransaction.current(q);
              if (!mode.equals("require")) o.admission();
              long lockStart = System.nanoTime();
              v.sessionScopes.lock("tenant", List.of(ID, CALLER));
              event("locks", lockStart, q.resources());
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
              long sourceStart = System.nanoTime();
              var source = v.sessionScopes.eligibility.sourceFacts(caller, ID, true);
              event("sourceFacts", sourceStart, q.resources());
              if (!mode.equals("require"))
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
              if (mode.equals("repeat")) {
                records.withProof(
                    () -> {
                      var first = records.input("tenant", ID);
                      records.hash(first.stored().genesis());
                      records.hash(first.stored().graph());
                      long before = q.completedWork().getOrDefault("encode", 0L);
                      var second = records.input("tenant", ID);
                      check(
                          first.fact().request() == second.fact().request(),
                          "fixture retains same String identity across actual SELECTs");
                      records.hash(second.stored().genesis());
                      records.hash(second.stored().graph());
                      long after = q.completedWork().getOrDefault("encode", 0L);
                      check(after > before, "new SELECT independently encodes same-identity raw");
                      return null;
                    });
                throw marker;
              }
              if (mode.equals("require")) {
                var structure = v.sessionScopes.eligibility.require(source.caller());
                o.holdScope(structure);
                var graph =
                    o.withLineageScope(
                        q,
                        source.caller(),
                        structure,
                        () -> v.sessions.lineageService.verify("tenant", ID));
                check(graph.nodes().size() == 1, "ordinary require uses fresh paired input");
                check(
                    q.statements().keySet().stream()
                        .noneMatch(x -> x.endsWith("SessionLineageMapper.fact")),
                    "require does not repeat authority Fact");
                throw marker;
              }
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
              var service =
                  new io.github.lovexiaopeng.jimu.kernel.session.service.impl
                      .SessionLineageServiceImpl(records);
              var result = service.verify("tenant", ID);
              check(result.nodes().size() == 1, "paired input full validation");
              throw marker;
            },
            q.deadlineNanos());
      } catch (Throwable e) {
        failure = e;
      }
      q.releaseScratch(fault.fillOwner);
      try {
        if (mode.equals("graph") || mode.equals("require") || mode.equals("repeat"))
          check(failure == marker, "new SQL/old SQL completed before fixture rollback " + mode);
        else {
          check(failure != null && failure != marker, "fresh field mismatch rejected " + mode);
          check(q.recordedFailure() != null, "failed read poisons Work " + mode);
          check(
              !"SESSION_QUERY_TIMEOUT"
                  .equals(
                      q.recordedFailure()
                              instanceof
                              io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure task
                          ? task.code()
                          : null),
              "expected fault rather than timeout " + mode);
          check(
              q.statements().keySet().stream()
                  .anyMatch(s -> s.endsWith("SessionLineageMapper.nodeInputSizes")),
              "new source SQL reached " + mode);
          check(
              q.statements().keySet().stream()
                  .noneMatch(s -> s.endsWith("SessionLineageMapper.fact")),
              "no full-reader fallback after new SQL " + mode);
        }
        if (mode.equals("allocation-refusal") || mode.startsWith("size-metadata-"))
          check(
              q.statements().keySet().stream()
                  .noneMatch(x -> x.endsWith("SessionLineageMapper.nodeInput")),
              "refusal before raw SELECT");
        check(q.scratchLive() == 65536, "all owners released " + mode);
      } catch (Throwable e) {
        assertion = e;
        failures++;
      }
      var receipt = new java.util.LinkedHashMap<String, Object>();
      receipt.put("mode", mode);
      receipt.put("success", assertion == null);
      receipt.put("elapsedMicros", (System.nanoTime() - origin) / 1000);
      receipt.put("deadlineNanos", q.deadlineNanos());
      receipt.put("resources", q.resources().toString());
      receipt.put("scratchLive", q.scratchLive());
      receipt.put("firstError", String.valueOf(q.recordedFailure()));
      receipt.put("caught", String.valueOf(failure));
      receipt.put("assertion", String.valueOf(assertion));
      receipt.put("statements", q.statements());
      receipt.put("timeline", List.copyOf(TIMELINE));
      System.out.println("SOURCE_CASE " + Payloads.json(receipt));
      if (failure != null) failure.printStackTrace(System.out);
      if (assertion != null) assertion.printStackTrace(System.out);
      System.out.println(
          "SOURCE_QUERY "
              + mode
              + " "
              + q.resources()
              + " failure="
              + (failure == null ? "none" : failure.getClass().getSimpleName()));
    }
    if (failures != 0) throw new AssertionError("independent scenario failures: " + failures);
    System.out.println("SOURCE_SQL_COMPLETE scoped fixture only; no whole business acceptance");
  }
}
