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

  private static void historyWitness(
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease) throws Exception {
    var v = view();
    var q = v.queryContext();
    q.restrictLineage();
    v.sessionTransaction(
        () -> {
          var task = v.fenced(lease, true);
          v.sessionScopes.require(task);
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
    root = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    root.sessionReadEnabled(true, List.of());
    root.sessionSearchEnabled(true);
    root.sessionTraceEnabled(true);
    root.priorSessionSearchEnabled(true);
    root.sessionLineageEnabled(true);
    if (args.length > 1 && args[1].equals("sql-length")) {
      SessionEventHistoryLengthSqlScenario.verify(db);
      return;
    }
    if (args.length > 1 && args[1].startsWith("borrowed")) {
      SessionBorrowedMetadataSqlScenario.verify(db, args[1]);
      if (args[1].equals("borrowed-shapes") || args[1].equals("borrowed-boundaries")) return;
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
        var v = view();
        var q = v.queryContext();
        var beforeAdmission = SessionDependencyScenario.snapshot(db);
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
        }
        if (args.length > 1 && args[1].contains("witness"))
          independentAccounting(nodes.getLast(), "after-submit-" + i);
        if (args.length > 1 && args[1].contains("event-history"))
          SessionEventHistorySqlScenario.verify(root, db, nodes.getLast());
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
        root.finish(lease, "joint terminal source " + i);
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
