package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Complete session admission with explicit legal source fixtures and private outer rollback. */
public final class ExecutionScopeConsumptionScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static Statement statement(Statement actual, String text, int connection) {
    var binds = new LinkedHashMap<String, Object>();
    var setters = new ArrayList<String>();
    var getters = new ArrayList<Map<String, Object>>();
    return (Statement)
        Proxy.newProxyInstance(
            ExecutionScopeConsumptionScenario.class.getClassLoader(),
            new Class<?>[] {text == null ? Statement.class : PreparedStatement.class},
            (p, m, a) -> {
              if (m.getName().startsWith("set")
                  && a != null
                  && a.length >= 2
                  && a[0] instanceof Integer) {
                setters.add(m.getName());
                binds.put(a[0].toString(), m.getName().equals("setNull") ? null : a[1]);
              }
              if (measuring && m.getName().startsWith("execute")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("connection", connection);
                row.put("statement", text == null ? "Statement" : "PreparedStatement");
                row.put("execute", m.getName());
                row.put(
                    "sql", (text == null ? a[0].toString() : text).replaceAll("\\s+", " ").strip());
                row.put("binds", new LinkedHashMap<>(binds));
                row.put("setters", new ArrayList<>(setters));
                row.put("getters", getters);
                sql.add(row);
              }
              Object value = invoke(actual, m, a);
              if (value instanceof java.sql.ResultSet result)
                return Proxy.newProxyInstance(
                    ExecutionScopeConsumptionScenario.class.getClassLoader(),
                    new Class<?>[] {java.sql.ResultSet.class},
                    (rp, rm, ra) -> {
                      Object item = invoke(result, rm, ra);
                      if (measuring
                          && rm.getName().startsWith("get")
                          && ra != null
                          && ra.length >= 1) {
                        var access = new LinkedHashMap<String, Object>();
                        access.put("method", rm.getName());
                        access.put("column", ra[0].toString());
                        access.put("type", item == null ? "NULL" : item.getClass().getName());
                        access.put("value", evidenceValue(item));
                        access.put(
                            "arguments",
                            java.util.Arrays.stream(ra)
                                .map(
                                    v ->
                                        v instanceof Class<?> type
                                            ? type.getName()
                                            : String.valueOf(v))
                                .toList());
                        getters.add(access);
                      }
                      return item;
                    });
              return value;
            });
  }

  private static Map<String, Object> proof() {
    var out = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "task_session_history",
            "task_session_scope",
            "task_session_scope_target",
            "session_public_event",
            "reservation",
            "event",
            "tenant_budget",
            "task_attempt",
            "worker_process",
            "evaluation_budget",
            "evaluation_execution_scope",
            "jimu_provisioning_receipt"))
      out.put(table, db.queryForList("SELECT * FROM \u0060" + table + "\u0060 ORDER BY 1,2"));
    return out;
  }

  private static String facts() {
    var out = new LinkedHashMap<String, Object>();
    for (String table : db.queryForList("SHOW TABLES", String.class))
      out.put(
          table,
          db.queryForList("SELECT * FROM `" + table + "`").stream()
              .map(Object::toString)
              .sorted()
              .toList());
    return Payloads.json(out);
  }

  private static Object evidenceValue(Object value) {
    if (value instanceof java.util.Optional<?> optional)
      return evidenceValue(optional.orElse(null));
    if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    if (value instanceof Record record) {
      var converted = new LinkedHashMap<String, Object>();
      for (var component : record.getClass().getRecordComponents()) {
        try {
          converted.put(component.getName(), evidenceValue(component.getAccessor().invoke(record)));
        } catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("record evidence conversion", failure);
        }
      }
      return converted;
    }
    if (value instanceof Map<?, ?> map) {
      var converted = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> converted.put(key.toString(), evidenceValue(item)));
      return converted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(ExecutionScopeConsumptionScenario::evidenceValue).toList();
    return value;
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    var before = proof();
    int prior = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    Object result = null;
    Throwable primary = null;
    measuring = true;
    try {
      result = work.get();
    } catch (Throwable failure) {
      primary = failure;
    } finally {
      measuring = false;
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    int restored = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    out.put("sessionPrior", prior);
    out.put("sessionAfter", restored);
    require(prior == restored, "session restored after " + name);
    out.put("before", before);
    out.put("after", proof());
    out.put("result", result);
    out.put("sql", new ArrayList<>(sql));
    out.put(
        "code",
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName());
    System.out.println("CALL " + Payloads.json(evidenceValue(out)));
    if (primary != null) {
      primary.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(primary)));
    }
    require(
        expected.equals(out.get("code")),
        name + " expected " + expected + " got " + out.get("code"));
  }

  private static List<Map<String, Object>> exceptionFacts(Throwable failure) {
    var queue = new java.util.ArrayDeque<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var facts = new ArrayList<Map<String, Object>>();
    queue.add(failure);
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      if (!seen.add(current)) continue;
      var fact = new LinkedHashMap<String, Object>();
      fact.put("type", current.getClass().getName());
      fact.put("message", current.getMessage());
      if (current instanceof SQLException sqlFailure) {
        fact.put("SQLState", sqlFailure.getSQLState());
        fact.put("vendor", sqlFailure.getErrorCode());
        if (sqlFailure.getNextException() != null) queue.add(sqlFailure.getNextException());
      }
      if (current.getCause() != null) queue.add(current.getCause());
      queue.addAll(java.util.Arrays.asList(current.getSuppressed()));
      facts.add(fact);
    }
    return facts;
  }

  private static String submit(String key, String goal, List<String> caps) {
    return ledger
        .submit(
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
            key,
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                goal,
                caps,
                List.of(),
                new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(600, 16, 32, null)))
        .taskId();
  }

  private static Map<String, Object> taskRow(String id) {
    return db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
  }

  private static void scenario(
      javax.sql.DataSource source,
      Properties scoped,
      io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeBinding.Input input) {
    call("no-scope-target", () -> ledger.executionScopes.targetRun("tenant-a", null), "OK");
    call("no-scope-consume", () -> ledger.requireScope("tenant-a", null, null, null), "OK");
    call(
        "ordinary-submit",
        () -> submit("ordinary-scope", "ordinary local", List.of("web_fetch")),
        "OK");
    var ordinary =
        db.queryForMap(
            "SELECT id FROM task WHERE idempotency_key='ordinary-scope' AND tenant_id='tenant-a'");
    ledger.cancel(
        new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
        (String) ordinary.get("id"));
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("local-synthetic", scoped));
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    var scope = input.scope();
    var req = ledger.models.scopeRequirement();
    var plan =
        io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans.plan("qwen-main-v1");
    String p = Payloads.json(plan),
        approval =
            Payloads.json(
                new java.util.TreeMap<>(
                    Map.of("activeCandidateOnly", "qwen3.7-plus-2026-05-26", "fixtureOnly", true)));
    require(Payloads.hash(approval).equals(req.approvalDigest()), "synthetic approval digest");
    db.update(
        "INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,plan_snapshot,plan_digest) VALUES(?,?,?,?,?,?,?)",
        "tenant-a",
        req.runId(),
        2,
        63,
        500000000,
        p,
        Payloads.hash(p));
    db.update(
        "INSERT INTO jimu_provisioning_receipt(record_version,issue_id,instance_uuid,tenant_id,run_id,approval_snapshot,approval_digest,plan_digest,materials_digest) VALUES(?,?,?,?,?,?,?,?,?)",
        1,
        req.issueId(),
        req.instanceUuid(),
        req.tenantId(),
        req.runId(),
        approval,
        Payloads.hash(approval),
        Payloads.hash(p),
        plan.materialsDigest());
    String raw =
        io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes.canonical(scope);
    db.update(
        "INSERT INTO evaluation_execution_scope(tenant_id,run_id,record_version,scope_snapshot,scope_digest,bound_by,bound_at_utc) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
        req.tenantId(),
        req.runId(),
        1,
        raw,
        Payloads.hash(raw),
        "local-synthetic-explicit-fixture");
    call("receipt-target", () -> ledger.executionScopes.targetRun("tenant-a", "unused"), "OK");
    var run =
        db.queryForMap(
            "SELECT * FROM evaluation_budget WHERE tenant_id='tenant-a' AND id=?", req.runId());
    call(
        "scope-consume",
        () -> ledger.requireScope("tenant-a", run, null, ledger.models.selected("tenant-a")),
        "OK");
    var material =
        io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials.load(plan, "W1");
    call(
        "scoped-submit",
        () ->
            submit(
                "scoped-scope", material.goal(), List.of("web_fetch", "read", "write", "present")),
        "OK");
    String task =
        db.queryForObject(
            "SELECT id FROM task WHERE idempotency_key='scoped-scope' AND tenant_id='tenant-a'",
            String.class);
    ledger.registerWorker(task, task, ProcessHandle.current().pid(), 1, "private", "private");
    var lease = ledger.claim(task).orElseThrow();
    require(lease.taskId().equals(task), "exact scoped task");
    var active =
        db.queryForMap(
            "SELECT * FROM task_attempt WHERE tenant_id='tenant-a' AND id=?", lease.attemptId());
    require(
        active.get("state").equals("ACTIVE") && taskRow(task).get("state").equals("RUNNING"),
        "active lease");
    System.out.println(
        "ACTIVE "
            + Payloads.json(
                evidenceValue(Map.of("task", taskRow(task), "attempt", active, "lease", lease))));
    call(
        "scope-query-metered",
        () -> {
          try {
            var method =
                JdbcLedger.class.getDeclaredMethod(
                    "queryView",
                    long.class,
                    io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                        .QueryResources.class);
            method.setAccessible(true);
            var scopedLedger =
                (JdbcLedger)
                    method.invoke(
                        ledger,
                        System.nanoTime() + 2_000_000_000L,
                        new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                            .QueryResources(2, 12, 4));
            scopedLedger.requireScope("tenant-a", run, null, ledger.models.selected("tenant-a"));
            var counters = scopedLedger.queryContext().resources();
            require(
                counters.reads() > 2 && counters.bytes() > 12 && counters.operations() > 4,
                "original cumulative metering");
            return counters;
          } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
          }
        },
        "OK");
    call("claimed-checkpoint", () -> ledger.checkpoint(lease), "OK");
    var bad =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
            lease.tenant(),
            lease.actor(),
            lease.taskId(),
            lease.attemptId(),
            lease.fence() + 1,
            lease.worker(),
            lease.deadline(),
            lease.goal(),
            lease.capabilities(),
            lease.installationEpoch());
    call("fence-rejected", () -> ledger.checkpoint(bad), "FENCE_REJECTED");
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    call("permission-rejected", () -> ledger.checkpoint(lease), "ACCESS_REVOKED");
    db.update(
        "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    call("permission-recovered", () -> ledger.checkpoint(lease), "OK");
    String digest = (String) taskRow(task).get("execution_scope_digest");
    db.update(
        "UPDATE task SET execution_scope_digest=? WHERE tenant_id='tenant-a' AND id=?",
        "f".repeat(64),
        task);
    call("claimed-mismatch", () -> ledger.checkpoint(lease), "EVALUATION_SCOPE_MISMATCH");
    require(
        "STOPPED"
            .equals(
                db.queryForObject(
                    "SELECT state FROM evaluation_budget WHERE tenant_id='tenant-a' AND id=?",
                    String.class,
                    req.runId())),
        "STOPPED");
    require(
        db.queryForObject(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='evaluation.scope_rejected'",
                Integer.class,
                task)
            == 1,
        "exact rejection event");
    db.update(
        "UPDATE task SET execution_scope_digest=? WHERE tenant_id='tenant-a' AND id=?",
        digest,
        task);
    db.update(
        "UPDATE evaluation_budget SET state='OPEN' WHERE tenant_id='tenant-a' AND id=?",
        req.runId());
    call("scope-recovered", () -> ledger.checkpoint(lease), "OK");
    db.update("DELETE FROM jimu_provisioning_receipt WHERE tenant_id='tenant-a'");
    call("bound-target", () -> ledger.executionScopes.targetRun("tenant-a", null), "OK");
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-mcp", props));
    var plain =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    var observed =
        new DelegatingDataSource(plain) {
          int sequence;

          public Connection getConnection() throws SQLException {
            var raw = super.getConnection();
            int connection = ++sequence;
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object result = invoke(raw, m, a);
                      if (result instanceof Statement s
                          && (m.getName().equals("prepareStatement")
                              || m.getName().equals("createStatement")))
                        return statement(
                            s,
                            m.getName().equals("prepareStatement") ? a[0].toString() : null,
                            connection);
                      return result;
                    });
          }
        };
    var source =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(observed);
    db = new JdbcTemplate(source);
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);

    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {

      System.out.println(
          "PROVENANCE public admission and checkpoint; local-synthetic paid=true snapshot fields only, explicit private receipt/binding; no worker/provider/tool execution; outer rollback only");
      var scoped = new Properties();
      try (var in = Files.newInputStream(Path.of(args[1]))) {
        scoped.load(in);
      }
      var input =
          Payloads.read(Files.readString(Path.of(args[2])), ExecutionScopeBinding.Input.class);
      scenario(source, scoped, input);
    } catch (Throwable failure) {
      primary = failure;
      failure.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(failure)));
    } finally {
      try {
        transactions.rollback(tx);
        System.out.println("ROLLBACK complete");
      } catch (Throwable failure) {
        failure.printStackTrace(System.out);
        if (primary == null) primary = failure;
        else primary.addSuppressed(failure);
      }
    }
    String restored = facts();
    System.out.println("FULLAFTER " + restored);
    require(
        initial.equals(restored), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
