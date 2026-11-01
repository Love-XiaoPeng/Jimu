package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.TypedLineageOutputCandidate;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.domain.SessionLineageGraph;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 私库真实准入/终态来源；同图首错保留，绝不把局部SQL/typed阶段当成完整M2或A03。 */
public final class SessionJointProjectionScenario {
  private static JdbcTemplate db;
  private static JdbcLedger root;
  private static final Identity IDENTITY = new Identity("tenant-a", "alice");
  private static final List<String> nodes = new ArrayList<>();

  private static JdbcLedger view() throws Exception {
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    return (JdbcLedger)
        factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static void phase(String label, SessionQueryContext q) {
    System.out.println(
        "PHASE "
            + Payloads.json(
                Map.of(
                    "phase",
                    label,
                    "resources",
                    q.resources(),
                    "requested",
                    q.requestedWork(),
                    "completed",
                    q.completedWork(),
                    "scratchLive",
                    q.scratchLive(),
                    "scratchPeak",
                    q.scratchPeak(),
                    "statements",
                    q.statements(),
                    "nodes",
                    List.copyOf(nodes),
                    "atNanos",
                    System.nanoTime(),
                    "operationRejection",
                    q.operationRejection() == null ? "none" : q.operationRejection())));
    System.out.flush();
  }

  private static long scan(MapperSessionMetadataAccounting records) {
    long bytes = 0;
    for (var table : SessionMetadataAccounting.Table.values()) {
      try (var batch = records.batch(table)) {
        for (var row : batch) {
          bytes += 32;
          for (var value : row.values()) bytes += 8 + batch.scalarBytes(value);
        }
      }
    }
    return bytes;
  }

  private static void projection(String task) throws Exception {
    var oracle = SessionRepairSqlOracle.inspect(db, task);
    SessionRepairSqlOracle.dump(db, task, "genuine-first-node");
    var v = view();
    var q = v.queryContext();
    q.restrictLineage();
    long actual =
        v.sessionTransaction(
            () -> scan(new MapperSessionMetadataAccounting(v.sessions, v.locked("tenant-a", task))),
            q.deadlineNanos());
    check(
        actual == oracle.metadata(),
        "18 actual Mapper projections equal independent INFORMATION_SCHEMA oracle " + actual);
    phase("18-projection-scan", q);
    int cases = 0;
    for (var table : SessionMetadataAccounting.Table.values()) {
      if (SessionMetadataProjection.forTable(table) == null) continue;
      for (String mode :
          table == SessionMetadataAccounting.Table.TASK_ATTEMPT
              ? List.of("consumer", "reserve", "caught-reserve")
              : List.of("consumer", "reserve")) {
        var before = SessionDependencyScenario.snapshot(db);
        var scoped = view();
        var query = scoped.queryContext();
        query.restrictLineage();
        boolean refused = false;
        try {
          scoped.sessionTransaction(
              () -> {
                var row = scoped.locked("tenant-a", task);
                var records = new MapperSessionMetadataAccounting(scoped.sessions, row);
                records.account(oracle.metadata() + 1);
                Object proof = new Object();
                try {
                  if (mode.endsWith("reserve"))
                    query.retainScratch(
                        proof,
                        1048576
                            - query.scratchLive()
                            - SessionMetadataProjection.forTable(table).retainedBytes
                            + 1);
                  try (var batch = records.batch(table)) {
                    for (var r : batch) for (var value : r.values()) batch.scalarBytes(value);
                    if (mode.equals("consumer"))
                      throw new IllegalStateException("projection-consumer-marker");
                  } catch (RuntimeException refusal) {
                    if (!mode.equals("caught-reserve")) throw refusal;
                    check(
                        refusal instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure f
                            && f.code().equals("SESSION_HISTORY_LIMIT"),
                        "caught exact reserve refusal");
                    return null;
                  }
                } finally {
                  query.releaseScratch(proof);
                }
                throw new AssertionError("must reject " + table);
              },
              query.deadlineNanos());
        } catch (RuntimeException expected) {
          refused =
              mode.equals("consumer")
                  ? expected.getMessage().equals("projection-consumer-marker")
                  : expected instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure f
                      && f.code().equals("SESSION_HISTORY_LIMIT");
        }
        check(
            refused && before.equals(SessionDependencyScenario.snapshot(db)),
            "22-table rollback " + table + " " + mode);
        check(query.scratchLive() == 65536, "released cursor/batch " + table + " " + mode);
        cases++;
      }
    }
    var before = SessionDependencyScenario.snapshot(db);
    var tail = view();
    var tq = tail.queryContext();
    tq.restrictLineage();
    boolean rejected = false;
    try {
      tail.sessionTransaction(
          () -> {
            var records =
                new MapperSessionMetadataAccounting(tail.sessions, tail.locked("tenant-a", task));
            records.account(oracle.metadata() + 1);
            scan(records);
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                      public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("projection-tail-marker");
                      }
                    });
            return null;
          },
          tq.deadlineNanos());
    } catch (RuntimeException expected) {
      rejected = "projection-tail-marker".equals(expected.getMessage());
    }
    check(
        rejected && before.equals(SessionDependencyScenario.snapshot(db)),
        "all-projection beforeCommit 22-table rollback");
    System.out.println("PROJECTION_CASES " + cases);
  }

  private static void joint(String caller) throws Exception {
    // 独立原reader输出作oracle；候选事务必须同时持有三份baseline和三份typed输出。
    var base = view();
    var bq = base.queryContext();
    bq.restrictLineage();
    var targets = List.of(nodes.getFirst(), nodes.get(4), caller);
    Object baselineOwner = new Object(), baselineWorkspace = new Object();
    bq.retainScratch(baselineOwner, 3L * (128 + 2 * 8192 + 128));
    bq.retainScratch(baselineWorkspace, 163840);
    List<String> baseline;
    try {
      baseline =
          base.sessionTransaction(
              () -> {
                base.authorizeSessionLineage(IDENTITY, caller, bq.deadlineNanos());
                var graph = base.lineages.verify(IDENTITY.tenant(), caller);
                var contracts = new SessionLineageContracts();
                var out = new ArrayList<String>();
                for (String target : targets)
                  out.add(
                      contracts.output(
                          SessionLineageGraph.trace(
                              caller,
                              target,
                              graph.authorityDigest(),
                              graph.graphDigest(),
                              graph.genesis(),
                              graph.nodes(),
                              graph.children(),
                              bq::operation)));
                return List.copyOf(out);
              },
              bq.deadlineNanos());
    } catch (RuntimeException | Error failure) {
      bq.releaseScratch(baselineOwner);
      throw failure;
    } finally {
      bq.releaseScratch(baselineWorkspace);
    }
    SessionRepairSqlOracle.dump(db, caller, "joint-baseline");
    var before = SessionDependencyScenario.snapshot(db);
    var v = view();
    var q = v.queryContext();
    q.restrictLineage();
    try {
      q.retainScratch(baselineOwner, 3L * (128 + 2 * 8192 + 128));
    } finally {
      bq.releaseScratch(baselineOwner);
    }
    var outputs = new ArrayList<TypedLineageOutputCandidate.OwnedOutput>();
    String[] last = {"authority"};
    try {
      v.sessionTransaction(
          () -> {
            v.authorizeSessionLineage(IDENTITY, caller, q.deadlineNanos());
            var graph = v.lineages.verify(IDENTITY.tenant(), caller);
            check(graph.genesis().size() == 9, "genuine same nine-node graph");
            phase(last[0], q);
            for (int i = 0; i < targets.size(); i++) {
              last[0] = "typed-" + i;
              Object resultOwner = new Object();
              q.retainScratch(resultOwner, 16384);
              try {
                var result =
                    SessionLineageGraph.trace(
                        caller,
                        targets.get(i),
                        graph.authorityDigest(),
                        graph.graphDigest(),
                        graph.genesis(),
                        graph.nodes(),
                        graph.children(),
                        q::operation);
                var output = TypedLineageOutputCandidate.output(result, q);
                outputs.add(output);
                String original = baseline.get(i);
                check(
                    q.primitive(
                        "joint.raw.compare",
                        Math.max(output.raw().length(), original.length()),
                        () -> output.raw().equals(original)),
                    "same-fixture original raw " + i);
                Object compareOwner = new Object();
                q.retainScratch(compareOwner, 4096L + 4L * original.length());
                try {
                  byte[] encoded =
                      q.primitive(
                          "joint.baseline.encode",
                          original.length(),
                          () -> original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  String expectedDigest =
                      q.primitive(
                          "joint.baseline.hash", encoded.length, () -> Payloads.hash(encoded));
                  check(
                      q.primitive(
                          "joint.digest.compare", 64, () -> expectedDigest.equals(output.digest())),
                      "same-fixture independent digest " + i);
                } finally {
                  q.releaseScratch(compareOwner);
                }
                phase(last[0], q);
              } finally {
                q.releaseScratch(resultOwner);
              }
            }
            last[0] = "18-projections";
            scan(new MapperSessionMetadataAccounting(v.sessions, v.locked("tenant-a", caller)));
            phase(last[0], q);
            last[0] = "finish-current-live-beforeCommit";
            return null;
          },
          q.deadlineNanos());
    } finally {
      for (var output : outputs) output.close();
      outputs.clear();
      q.releaseScratch(baselineOwner);
      phase(last[0], q);
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "read-only candidate independent DB unchanged");
      System.out.println(
          "MISSING actual M2 slots/normalized/receipt/usage, public response ownership, ACK/new fence; helper reach is not full PASS");
    }
  }

  /** Original no-source M1 only: complete service transaction, never call the model. */
  private static void ordinaryModelAdmission() throws Exception {
    var contracts = new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts();
    var pin = contracts.descriptor();
    db.update(
        "UPDATE installation SET plugin_version=?,plugin_digest=? WHERE tenant_id='tenant-a' AND plugin_id=?",
        pin.version(),
        pin.outputSha256(),
        pin.capability());
    for (boolean scoped : List.of(false, true)) {
      String key = "ordinary-m1-" + scoped;
      var submitted =
          root.submit(
              IDENTITY,
              key,
              new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                  key,
                  List.of("session_event_read"),
                  List.of(),
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(120, 8, 20, null)));
      root.registerWorker(key, key, ProcessHandle.current().pid(), 1, "private", "private");
      var lease = root.claim(key).orElseThrow();
      check(lease.taskId().equals(submitted.taskId()), "ordinary M1 exact genuine claim " + scoped);
      var model = root.modelSnapshot(lease);
      var messages =
          List.of(
              new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                  "user", root.sessionContext(lease), List.of(), List.of()));
      var tool =
          org.springframework.ai.tool.definition.ToolDefinition.builder()
              .name("session_event_read")
              .description(contracts.description())
              .inputSchema(contracts.inputSchema())
              .build();
      String body =
          new io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol()
              .body(model, messages, List.of(tool));
      String request =
          Payloads.json(
              new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
                  2,
                  model.model(),
                  messages,
                  lease.capabilities(),
                  lease.installationEpoch(),
                  model.stream(),
                  ModelProfiles.digest(model),
                  body,
                  Payloads.hash(body)));
      var step = root.prepare(lease, request);
      JdbcLedger admission = root;
      if (scoped) {
        var method =
            JdbcLedger.class.getDeclaredMethod(
                "queryView",
                io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
                boolean.class);
        method.setAccessible(true);
        admission = (JdbcLedger) method.invoke(root, lease, true);
      }
      check(
          admission.queryContext() == null || !admission.queryContext().lineage(),
          "actual ordinary M1 route " + scoped);
      String dispatch = admission.dispatchModel(lease, step.id());
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM model_dispatch WHERE tenant_id='tenant-a' AND id=? AND task_id=?",
                  Long.class,
                  dispatch,
                  lease.taskId())
              == 1,
          "ordinary M1 commits original dispatch " + scoped);
      if (scoped)
        check(admission.queryContext().scratchLive() == 1048576, "ordinary query owners released");
      check(
          io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                  .currentOwner()
              == null,
          "ordinary M1 Work released " + scoped);
    }
  }

  private static void modelAdmissionPreflight(String fault) throws Exception {
    String goal = "qa-r54-single-response-three-trace";
    byte[] raw =
        Payloads.json(Map.of("goal", goal, "history_session_ids", List.of()))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var v = view();
    var submitted =
        v.submitSessionLineage(
            IDENTITY,
            fault == null ? "m1-preflight" : "m1-preflight-" + fault,
            end -> raw,
            v.queryContext().deadlineNanos());
    String worker = fault == null ? "m1-private-worker" : "m1-worker-" + fault;
    root.registerWorker(worker, worker, ProcessHandle.current().pid(), 1, "private", "private");
    var lease = root.claim(worker).orElseThrow();
    check(lease.taskId().equals(submitted.taskId()), "M1 claims its exact genuine task");
    var model = root.modelSnapshot(lease);
    var definitions = new ArrayList<org.springframework.ai.tool.definition.ToolDefinition>();
    for (String name : lease.capabilities()) {
      String description, schema;
      if (name.equals("session_trace")) {
        var contracts = new SessionLineageContracts();
        description = contracts.description();
        schema = contracts.inputSchema();
      } else if (name.equals("session_search")) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts(5);
        description = contracts.description();
        schema = contracts.inputSchema();
      } else {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts(5);
        description = contracts.description();
        schema = contracts.inputSchema();
      }
      definitions.add(
          org.springframework.ai.tool.definition.ToolDefinition.builder()
              .name(name)
              .description(description)
              .inputSchema(schema)
              .build());
    }
    var messages =
        List.of(
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                "user", goal, List.of(), List.of()),
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                "user", root.sessionContext(lease), List.of(), List.of()));
    String body =
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol()
            .body(model, messages, definitions);
    String encoded =
        Payloads.json(
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
                2,
                model.model(),
                messages,
                lease.capabilities(),
                lease.installationEpoch(),
                model.stream(),
                ModelProfiles.digest(model),
                body,
                Payloads.hash(body)));
    var step = root.prepare(lease, encoded);
    var factory =
        JdbcLedger.class.getDeclaredMethod(
            "queryView",
            io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
            boolean.class);
    factory.setAccessible(true);
    var admissionRoot = root;
    SessionFinalMapperFailures.FaultSource callback = null;
    boolean extensionProbe = "extension-mcp".equals(fault);
    if (fault != null) {
      String run = "m1-current-evaluation-" + fault;
      db.update("INSERT INTO evaluation_budget(tenant_id,id) VALUES('tenant-a',?)", run);
      db.update(
          "INSERT INTO evaluation_budget(tenant_id,id,state) VALUES('tenant-a',?,'STOPPED')",
          run + "-replacement");
      db.update(
          "UPDATE task SET evaluation_run_id=? WHERE tenant_id='tenant-a' AND id=?",
          run,
          lease.taskId());
      if (extensionProbe)
        db.update(
            "UPDATE task SET mcp_snapshot='{}',mcp_digest=REPEAT('a',64) WHERE tenant_id='tenant-a' AND id=?",
            lease.taskId());
      callback = new SessionFinalMapperFailures.FaultSource(db.getDataSource());
      callback.table = fault.startsWith("budget-") ? "tenant_budget" : "task";
      if (callback.table.equals("task"))
        callback.sqlContains = " AS model_admission_extended FROM task";
      else callback.sqlContains = "SELECT tenant_id FROM tenant_budget WHERE";
      callback.fault = extensionProbe ? "none" : "write";
      var callbackDb = new JdbcTemplate(callback);
      callback.write =
          () -> {
            if (fault.endsWith("actor"))
              callbackDb.update(
                  "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
            else if (fault.endsWith("tool"))
              callbackDb.update(
                  "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_trace'");
            else if (fault.endsWith("replace"))
              callbackDb.update(
                  "UPDATE task SET evaluation_run_id=? WHERE tenant_id='tenant-a' AND id=?",
                  run + "-replacement",
                  lease.taskId());
            else if (fault.endsWith("quota"))
              callbackDb.update(
                  "UPDATE evaluation_budget SET requests_used=max_requests WHERE tenant_id='tenant-a' AND id=?",
                  run);
            else
              callbackDb.update(
                  "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id='tenant-a' AND id=?",
                  run);
          };
      admissionRoot =
          new JdbcLedger(
              callback,
              root.models,
              null,
              null,
              null,
              io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
                  .create(callback));
      admissionRoot.sessionReadEnabled(true, List.of());
      admissionRoot.sessionSearchEnabled(true);
      admissionRoot.sessionTraceEnabled(true);
      admissionRoot.priorSessionSearchEnabled(true);
      admissionRoot.sessionLineageEnabled(true);
    }
    var admission = (JdbcLedger) factory.invoke(admissionRoot, lease, true);
    var before = SessionDependencyScenario.snapshot(db);
    var evaluationBefore =
        db.queryForList("SELECT * FROM evaluation_budget WHERE tenant_id='tenant-a' ORDER BY id");
    boolean revoked = fault != null && (fault.endsWith("actor") || fault.endsWith("tool"));
    var rolledBack = new java.util.concurrent.atomic.AtomicReference<List<Object>>();
    boolean committed = false;
    try {
      if (fault != null && fault.endsWith("quota")) {
        admission.sessionTransaction(
            () -> {
              var facts = new MapperModelWorkflows(admission).lockAdmission(lease, step.id());
              var limits = facts.limits();
              check(limits.requests() == limits.maxRequests(), "M1 fresh quota after " + fault);
              throw new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
                  409, "EXPECTED_QUOTA_ROLLBACK");
            },
            admission.queryContext().deadlineNanos());
      }
      String dispatch;
      if (revoked)
        dispatch =
            admission.sessionTransaction(
                () -> {
                  // Observe the failed business transaction before its existing REQUIRES_NEW
                  // scope invalidation runs. That separate maintenance commit is intentional.
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .registerSynchronization(
                          new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                              check(
                                  status == STATUS_ROLLED_BACK,
                                  "M1 revoked business transaction rolled back");
                              rolledBack.set(SessionDependencyScenario.snapshot(db));
                            }
                          });
                  return admission.dispatchModel(lease, step.id());
                },
                admission.queryContext().deadlineNanos());
      else dispatch = admission.dispatchModel(lease, step.id());
      committed = true;
      check(dispatch != null, "full M1 committed before any model request");
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure failure) {
      check(
          before.equals(revoked ? rolledBack.get() : SessionDependencyScenario.snapshot(db)),
          "M1 failure rolls back 22 tables");
      if (revoked) {
        check(
            db.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                Boolean.class,
                lease.taskId()),
            "M1 separate scope invalidation committed after revoked admission rollback");
        check(
            db.queryForObject(
                    "SELECT COUNT(*) FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=?",
                    Integer.class,
                    lease.taskId())
                == 0,
            "M1 revoked admission creates no dispatch");
      }
      check(
          evaluationBefore.equals(
              db.queryForList(
                  "SELECT * FROM evaluation_budget WHERE tenant_id='tenant-a' ORDER BY id")),
          "M1 callback evaluation rows also roll back");
      System.out.println("M1_PREFLIGHT_FAILURE " + failure.code());
      failure.printStackTrace(System.out);
      if (fault == null) {
        if (!failure.code().equals("SESSION_HISTORY_LIMIT")) throw failure;
      } else
        check(
            failure
                .code()
                .equals(
                    extensionProbe
                        ? "SNAPSHOT_CORRUPT"
                        : fault.endsWith("quota")
                            ? "EXPECTED_QUOTA_ROLLBACK"
                            : fault.endsWith("actor")
                                ? "SESSION_SCOPE_UNAVAILABLE"
                                : fault.endsWith("tool")
                                    ? "SESSION_SCOPE_UNAVAILABLE"
                                    : "MODEL_BUDGET_EXCEEDED"),
            "M1 refuses latest evaluation state after " + fault);
    } finally {
      if (fault != null)
        check(
            (extensionProbe || callback.changed)
                && !committed
                && callback.statements == 1
                && (callback.sqlContains == null
                    || callback.matchedSql.contains(callback.sqlContains)),
            "M1 evaluation callback executed without commit " + fault);
      var q = admission.queryContext();
      phase("m1-preflight-committed=" + committed, q);
      check(q.scratchLive() == 65536, "M1 all owners released");
      System.out.println("M1_PREFLIGHT checkpointBytes=" + encoded.length() + " modelRequests=0");
    }
    if (fault != null) {
      // Private callback fixtures only: free their running slot after rollback assertions.
      // This SQL is not a production terminal settlement or a worker acceptance result.
      db.update(
          "UPDATE task SET state='FAILED',lease_until=NULL WHERE tenant_id='tenant-a' AND id=?",
          lease.taskId());
      db.update(
          "UPDATE task_attempt SET state='FAILED',quiescent=TRUE,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND id=?",
          lease.attemptId());
      db.update("UPDATE tenant_budget SET running=running-1 WHERE tenant_id='tenant-a'");
    }
  }

  /** 第二确认提交后才开始的新query；真实require/graph/history/current-live完整保留。 */
  private static void secondEventProof(String id) throws Exception {
    var before = SessionDependencyScenario.snapshot(db);
    var v = view();
    var q = v.queryContext();
    // This direct persistence probe must select the same v5 profile as the public entry before
    // opening its new Work. The generic queryView starts with the legacy 1MiB scratch reserve.
    q.restrictLineage();
    try {
      v.sessionTransaction(
          () -> {
            v.sessionScopes.lock("tenant-a", id);
            var task = v.locked("tenant-a", id);
            v.sessionScopes.ensure(task);
            var full = v.sessions.history(task, true);
            var narrow = v.sessions.acceptedEventHistory(task, true);
            check(
                full.size() == 15
                    && narrow.size() == 8
                    && full.entrySet().containsAll(narrow.entrySet()),
                "independent second full15/event8 after fresh scope graph");
            return null;
          },
          q.deadlineNanos());
      phase("independent-second-eventHistory-committed", q);
    } finally {
      phase("independent-second-eventHistory-returned", q);
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "independent second proof all22 unchanged");
      check(q.scratchLive() == 65536, "independent second owners released");
    }
  }

  private static void historyWitness(
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease) throws Exception {
    var v = view();
    var q = v.queryContext();
    q.restrictLineage();
    v.sessionTransaction(
        () -> {
          var task = v.fenced(lease, true);
          v.sessionScopes.ensure(task);
          // 实际commit fence更新和末端retained fence；不注入合成scope或空回调。
          v.todoCommitFence(lease);
          v.sessions.history(task, true);
          long reads = q.resources().reads();
          v.sessions.historyProof(task);
          check(v.sessions.historyRetained(task), "second proof-only history consumer retained");
          check(
              q.resources().reads() == reads,
              "two real checked-history consumers add no ResultSet");
          phase("history-witness-consumers", q);
          return null;
        },
        q.deadlineNanos());
    phase("history-witness-committed-live-fence", q);
    check(q.scratchLive() == 65536, "history witness transaction releases references");
  }

  private static void independentAccounting(String task, String label) {
    var expected = SessionRepairSqlOracle.inspect(db, task);
    var history =
        db.queryForMap(
            "SELECT metadata_bytes,body_bytes,reserved_bytes,held_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
            task);
    check(
        ((Number) history.get("metadata_bytes")).longValue() == expected.metadata(),
        label + " independent 18-table metadata " + expected.metadata());
    check(
        ((Number) history.get("body_bytes")).longValue() == expected.body(),
        label + " independent all BODY " + expected.body());
    var reservation =
        db.queryForMap(
            "SELECT amount,actual,state FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='SESSION_HISTORY_STORAGE'",
            task);
    check(
        ((Number) history.get("reserved_bytes")).longValue() == 8388608
            && ((Number) history.get("held_bytes")).longValue() == 8388608
            && ((Number) reservation.get("amount")).longValue() == 8388608
            && reservation.get("actual") == null
            && "RESERVED".equals(reservation.get("state")),
        label + " PRESENT original reserve and held unchanged");
    SessionRepairSqlOracle.dump(db, task, label);
  }

  private static void ownedSubmit(
      JdbcLedger v,
      int ordinal,
      byte[] input,
      io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse response) {
    v.submitSessionLineage(
        IDENTITY,
        "joint-node-" + ordinal,
        end -> input,
        v.queryContext().deadlineNanos(),
        response);
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View plainSubmit(
      JdbcLedger v, int ordinal, byte[] input) {
    return v.submitSessionLineage(
        IDENTITY, "joint-node-" + ordinal, end -> input, v.queryContext().deadlineNanos());
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    db.update(
        "INSERT INTO tenant_budget(tenant_id,history_storage_limit) VALUES('tenant-a',536870912)");
    db.update("INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice')");
    try (var in =
        SessionJointProjectionScenario.class.getResourceAsStream("/session/v5/catalog-v5.json")) {
      var catalog = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
      for (var tool : catalog.get("tools"))
        db.update(
            "INSERT INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a',?,?,?)",
            tool.get("name").asText(),
            tool.get("version").asText(),
            tool.get("outputSha256").asText());
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("joint", props));
    var mapperSession =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            source);
    boolean observe = args.length > 1 && args[1].contains("observe");
    if (observe) mapperSession.getConfiguration().addInterceptor(new SessionHistoryObservation());
    root = new JdbcLedger(source, new ModelProfiles(env), null, null, null, mapperSession);
    root.sessionReadEnabled(true, List.of());
    root.sessionSearchEnabled(true);
    root.sessionTraceEnabled(true);
    root.priorSessionSearchEnabled(true);
    root.sessionLineageEnabled(true);
    if (args.length > 1 && args[1].equals("m1-ordinary")) {
      ordinaryModelAdmission();
      return;
    }
    if (args.length > 1 && args[1].equals("m1-callbacks")) {
      for (String at : List.of("budget", "task"))
        for (String change : List.of("stop", "replace", "quota", "actor", "tool"))
          modelAdmissionPreflight(at + "-" + change);
      return;
    }
    if (args.length > 1 && args[1].startsWith("m1-callbacks-")) {
      String at = args[1].substring("m1-callbacks-".length());
      for (String change : List.of("stop", "replace", "quota", "actor", "tool"))
        modelAdmissionPreflight(at + "-" + change);
      return;
    }
    if (args.length > 1 && args[1].startsWith("m1-eval-")) {
      modelAdmissionPreflight(args[1].substring(8));
      return;
    }
    if (args.length > 1 && args[1].equals("m1-preflight")) {
      modelAdmissionPreflight(null);
      return;
    }
    if (args.length > 1 && args[1].equals("sql-history-length")) {
      SessionHistoryLengthSqlScenario.verify(db);
      return;
    }
    if (args.length > 1 && args[1].equals("sql-length")) {
      SessionEventHistoryLengthSqlScenario.verify(db);
      return;
    }
    if (args.length > 1 && args[1].startsWith("borrowed")) {
      SessionBorrowedMetadataSqlScenario.verify(db, args[1]);
      if (args[1].equals("borrowed-shapes")
          || args[1].equals("borrowed-source-types")
          || args[1].equals("borrowed-query-task")
          || args[1].equals("borrowed-model-task")
          || args[1].equals("borrowed-model-task-native")
          || args[1].equals("borrowed-model-task-boundaries")
          || args[1].equals("borrowed-model-task-faults")
          || args[1].equals("borrowed-node-input")
          || args[1].equals("borrowed-model-step")
          || args[1].equals("borrowed-model-admission")
          || args[1].equals("borrowed-model-consumers")
          || args[1].equals("borrowed-ordinary-faults")
          || args[1].startsWith("borrowed-row-")
          || args[1].equals("borrowed-boundaries")
          || args[1].startsWith("borrowed-preflight")
          || args[1].equals("borrowed-scalar-nulls")
          || args[1].equals("borrowed-special-nulls")
          || args[1].equals("borrowed-final-faults")
          || args[1].equals("borrowed-task-faults")
          || args[1].equals("borrowed-history-faults")) return;
    } else if (args.length < 2 || !args[1].startsWith("genuine"))
      SessionTaskSizedSqlScenario.verify(db);
    String worker = "joint-private-worker";
    root.registerWorker(worker, worker, ProcessHandle.current().pid(), 1, "private", "private");
    String last = "begin";
    int maximumNodes =
        args.length > 1 && args[1].startsWith("genuine-first")
            ? 1
            : args.length > 1 && args[1].startsWith("genuine-two") ? 2 : 9;
    try {
      for (int i = 0; i < maximumNodes; i++) {
        last = "submit-" + i;
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("goal", "joint-node-" + i);
        fields.put("history_session_ids", List.copyOf(nodes));
        if (!nodes.isEmpty()) fields.put("parent_session_id", nodes.getLast());
        byte[] raw = Payloads.json(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var beforeAdmission = SessionDependencyScenario.snapshot(db);
        if (observe) SessionHistoryObservation.snapshot(db, "before-submit-" + i);
        // 22表独立baseline先于请求；从这里起同一个2秒query贯穿准入、终算和wire。
        var v = view();
        var q = v.queryContext();
        try {
          if (args.length > 1 && args[1].endsWith("-owned")) {
            var response = new io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse();
            response.prepare(q.deadlineNanos(), 8192);
            response.prepareInput(q.deadlineNanos(), 16384);
            try {
              var inputLease = response.readInput();
              try {
                ownedSubmit(v, i, raw, response);
              } finally {
                raw = null;
                inputLease.close();
                response.produced();
              }
              String id = response.taskId();
              System.out.println("OWNED_ADMISSION_COMMITTED " + id + " live=" + q.scratchLive());
              // 同累计query实际生产pump/cursor；sink替身不代公开HTTP或三trace M2。
              io.github.lovexiaopeng.jimu.api.SessionTaskPumpScenario.write(
                  response, q.deadlineNanos());
              nodes.add(id);
            } finally {
              response.close();
              response.produced();
            }
          } else {
            var task = plainSubmit(v, i, raw);
            raw = null;
            nodes.add(task.taskId());
          }
        } catch (RuntimeException failure) {
          boolean unchanged = beforeAdmission.equals(SessionDependencyScenario.snapshot(db));
          System.out.println("OWNED_FAILURE_DB_UNCHANGED " + unchanged);
          if (!unchanged && args.length > 1 && args[1].endsWith("-owned"))
            System.out.println(
                "RESPONSE_FAILED_AFTER_ACCEPTANCE; committed SQL is not rolled back by wire failure");
          else check(unchanged, "failed genuine admission rolls back all 22 tables");
          throw failure;
        } finally {
          phase(last, q);
          if (observe) SessionHistoryObservation.snapshot(db, "after-submit-" + i);
        }
        if (args.length > 1 && args[1].contains("response-only")) return;
        if (i == 0 && args.length > 1 && args[1].contains("metadata-negative"))
          SessionHistorySizedMetadataSqlScenario.verify(root, db, nodes.getLast());
        if (args.length > 1 && args[1].contains("witness"))
          independentAccounting(nodes.getLast(), "after-submit-" + i);
        if (args.length > 1
            && args[1].contains("event-history")
            && (!args[1].contains("second-event-history") || i == 1)) {
          if (args[1].contains("r31")) secondEventProof(nodes.getLast());
          else SessionEventHistorySqlScenario.verify(root, db, nodes.getLast());
        }
        if (i == 1 && args.length > 1 && args[1].contains("r31")) return;
        if (args.length > 1 && args[1].contains("append-failure"))
          SessionAppendFailureSqlScenario.verify(root, db, nodes.getLast());
        if (args.length > 1 && args[1].contains("event11"))
          SessionAcceptedEventSqlScenario.verify(root, db, nodes.getLast());
        if (i == 8) break;
        last = "claim-" + i;
        var lease = root.claim(worker).orElseThrow();
        check(lease.taskId().equals(nodes.getLast()), "claim exact genuine source " + i);
        if (args.length > 1 && args[1].contains("access"))
          SessionAccessSqlScenario.verify(root, db, lease);
        if (args.length > 1 && args[1].contains("witness")) {
          last = "history-witness-" + i;
          historyWitness(lease);
        }
        if (args.length > 1 && args[1].contains("witness-negative-")) {
          SessionHistoryWitnessSqlScenario.verify(
              root, db, lease, args[1].substring(args[1].lastIndexOf('-') + 1));
          return;
        }
        last = "finish-" + i;
        var beforeFinish = SessionDependencyScenario.snapshot(db);
        if (observe) SessionHistoryObservation.snapshot(db, "before-finish-" + i);
        // Invoke the exact factory used by root.finish, retaining its context only for evidence.
        var finishFactory =
            JdbcLedger.class.getDeclaredMethod(
                "queryView",
                io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
                boolean.class);
        finishFactory.setAccessible(true);
        var terminal = (JdbcLedger) finishFactory.invoke(root, lease, true);
        try {
          terminal.finish(lease, "joint terminal source " + i);
        } catch (RuntimeException failure) {
          check(
              beforeFinish.equals(SessionDependencyScenario.snapshot(db)),
              "failed legal finish rolls back all 22 tables");
          throw failure;
        } finally {
          phase("finish-" + i, terminal.queryContext());
          if (observe) SessionHistoryObservation.snapshot(db, "after-finish-" + i);
        }
        root.quiescent(lease);
        check(
            db.queryForObject("SELECT state FROM task WHERE id=?", String.class, lease.taskId())
                .equals("SUCCEEDED"),
            "legal terminal source " + i);
        if (args.length > 1 && args[1].contains("witness"))
          independentAccounting(nodes.getLast(), "after-finish-" + i);
        if (i == 0
            && !(args.length > 1 && args[1].endsWith("-owned"))
            && !(args.length > 1 && args[1].contains("admission-only")))
          projection(nodes.getFirst());
      }
      if (maximumNodes < 9) {
        System.out.println("BOUNDED_ADMISSION_PROBE_COMPLETED " + maximumNodes);
        return;
      }
      last = "joint-candidate";
      joint(nodes.getLast());
    } catch (RuntimeException first) {
      System.out.println("FIRST_REAL_FIXTURE_FAILURE " + last + " " + first);
      first.printStackTrace(System.out);
      System.out.println("REACHED_NODES " + Payloads.json(nodes));
      System.out.flush();
      // A budget rejection is preserved as a failed candidate, not reported as a scenario crash.
      if (!(first instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure f)
          || !f.code().equals("SESSION_HISTORY_LIMIT")) throw first;
    }
    System.out.println(
        "JOINT_CHECKPOINT_COMPLETE; not stable candidate; no A03 or full M2/ACK claim");
  }
}
