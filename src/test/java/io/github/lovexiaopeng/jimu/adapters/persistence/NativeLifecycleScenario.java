package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
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

/** Native ledger lifecycle: synthetic identities, real live authorization and private rollback. */
public final class NativeLifecycleScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;

  private static int counter;

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
            NativeLifecycleScenario.class.getClassLoader(),
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
                    NativeLifecycleScenario.class.getClassLoader(),
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

  private static Map<String, Object> taskRow() {
    return db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
  }

  private static Map<String, Object> proof() {
    var out = new LinkedHashMap<String, Object>();
    out.put("task", taskRow());
    for (String table :
        List.of("native_search_run", "reservation", "event", "invocation", "step", "task_attempt"))
      out.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND task_id=?", task));
    out.put(
        "native_search_stop",
        db.queryForList("SELECT * FROM native_search_stop WHERE tenant_id='tenant-a'"));
    out.put("worker_process", db.queryForList("SELECT * FROM worker_process"));
    out.put("budget", db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'"));
    // Raw JDBC types/NULL/full columns are retained in toString; Jackson is evidence only.
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
      return list.stream().map(NativeLifecycleScenario::evidenceValue).toList();
    return value;
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    var before = proof();
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

  private static void setup() {
    ledger.registerWorker(
        "native-private", "native-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("native-private").orElseThrow();
    require(task.equals(lease.taskId()), "exact admitted task");
    var row = taskRow();
    var attempt =
        db.queryForMap(
            "SELECT * FROM task_attempt WHERE tenant_id=? AND id=?",
            lease.tenant(),
            lease.attemptId());
    require(
        "RUNNING".equals(row.get("state")) && "ACTIVE".equals(attempt.get("state")),
        "RUNNING/ACTIVE");
    require(
        lease.attemptId().equals(row.get("current_attempt_id"))
            && lease.worker().equals(row.get("worker_id"))
            && lease.fence() == ((Number) row.get("fence")).longValue(),
        "lease keys");
    require(
        db.queryForObject(
            "SELECT lease_until>CURRENT_TIMESTAMP(6) FROM task WHERE tenant_id=? AND id=?",
            Boolean.class,
            lease.tenant(),
            task),
        "live lease");
    System.out.println(
        "ACTIVE "
            + Payloads.json(
                evidenceValue(Map.of("task", row, "attempt", attempt, "lease", lease.toString()))));
  }

  private static void invocation(String name) {
    step = JdbcLedger.id();
    invocation = JdbcLedger.id();
    counter++;
    String input = Payloads.json(Map.of("pattern", "needle-private"));
    String request = Payloads.json(Map.of("requestBody", "{}", "bodyDigest", Payloads.hash("{}")));
    String response =
        Payloads.json(
            Map.of(
                "calls",
                List.of(Map.of("id", "call" + counter, "name", name, "arguments", input))));
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest) VALUES(?,?,?,?,?,'PREPARED',?,?,?,?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        counter,
        request,
        Payloads.hash(request),
        response,
        Payloads.hash(response));
    var pin = ledger.tools(taskRow()).pins().get(name);
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,? ,?,?,?, ?,?,?,?,?,?,'RUNNING')",
        lease.tenant(),
        invocation,
        task,
        step,
        lease.attemptId(),
        counter,
        "0:call" + counter,
        "call" + counter,
        JdbcLedger.id(),
        name,
        pin.version(),
        pin.digest(),
        pin.installationEpoch(),
        input,
        Payloads.hash(input));
    ledger.reserve(lease.tenant(), task, invocation, null, "TOOL", 1, "RESERVED", "KNOWN");
    db.update("UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
    db.update(
        "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?", lease.tenant(), task);
  }

  private static Lease altered(Lease good) {
    return new Lease(
        good.tenant(),
        good.actor(),
        good.taskId(),
        good.attemptId(),
        good.fence() + 1,
        good.worker(),
        good.deadline(),
        good.goal(),
        good.capabilities(),
        good.installationEpoch());
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation
      currentInvocation() {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation(
        invocation,
        (String)
            db.queryForMap(
                    "SELECT effect_intent_id FROM invocation WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    invocation)
                .get("effect_intent_id"),
        "RUNNING",
        null);
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run run(
      String id, String attempt) {
    var r =
        db.queryForMap(
            "SELECT * FROM native_search_run WHERE tenant_id=? AND root_invocation=?",
            lease.tenant(),
            id);
    return new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run(
        lease.tenant(),
        task,
        id,
        (String) r.get("effect_id"),
        attempt,
        lease.worker(),
        (String) r.get("runtime_directory"),
        (String) r.get("snapshot_digest"),
        (String) r.get("phase"),
        null,
        false);
  }

  private static Object prepare() {
    ledger.prepareSearch(
        lease,
        currentInvocation(),
        "synthetic-directory/" + invocation,
        Payloads.hash("snapshot"),
        Payloads.hash("query"),
        "glob-fixture-pin");
    return null;
  }

  private static Object start(
      String id,
      io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity identity) {
    ledger.searchStarted(lease, id, identity);
    return null;
  }

  private static Object stop(
      io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run run,
      io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity identity) {
    ledger.searchStopped(run, identity);
    return null;
  }

  private static Object complete(String id) {
    ledger.searchCompleted(
        lease, id, Payloads.hash("synthetic-result-bytes"), 71, Map.of("private-cursor", 1));
    return null;
  }

  private static Object remove(
      io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run run) {
    ledger.searchStorageRemoved(run);
    return null;
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

    ledger.nativeSearchAvailability(
        () -> true); // Synthetic readiness only; no runtime/tool launch.
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {

      task =
          ledger
              .submit(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-a", "alice"),
                  "native-lifecycle-private",
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                      "native-lifecycle-private",
                      List.of("glob", "grep"),
                      List.of(),
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                          120, 5, 10, null)))
              .taskId();
      setup();
      invocation("glob");
      String first = invocation;
      call("quiescent-empty", () -> ledger.searchQuiescent(lease, first), "OK");
      call("scan-empty", () -> ledger.nativeSearches("native-private"), "OK");
      call("expired-empty", () -> ledger.expiredSearches("native-private"), "OK");
      call("prepare", () -> prepare(), "OK");
      var firstRun = run(first, lease.attemptId());
      var identity =
          new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity(
              new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity(
                  999999, 7, "synthetic-boot", "synthetic-pidns", "synthetic-observer"),
              null,
              null);
      System.out.println(
          "PROVENANCE synthetic identity/readiness/explicit invocation fixture; NOT real native process receipt");
      call("quiescent-prepared", () -> ledger.searchQuiescent(lease, first), "OK");
      call("scan-prepared", () -> ledger.nativeSearches("native-private"), "OK");
      call("scan-foreign-host", () -> ledger.nativeSearches("foreign-host"), "OK");
      call("start", () -> start(first, identity), "OK");
      call("start-repeat", () -> start(first, identity), "SEARCH_PROCESS_UNPROVEN");
      call("scan-started", () -> ledger.nativeSearches("native-private"), "OK");
      call(
          "stop-unknown",
          () ->
              stop(
                  firstRun,
                  new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity(
                      null, null, null)),
          "SEARCH_PROCESS_UNPROVEN");
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("start-permission", () -> start(first, identity), "ACCESS_REVOKED");
      call("complete-permission", () -> complete(first), "ACCESS_REVOKED");
      call(
          "page-permission",
          () ->
              ledger.searchPage(
                  lease, Payloads.hash("query"), "glob-fixture-pin", "private-cursor"),
          "ACCESS_REVOKED");
      call("stop-old-attempt-permission", () -> stop(firstRun, identity), "OK");
      call("stop-repeat", () -> stop(firstRun, identity), "OK");
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("quiescent-stopped", () -> ledger.searchQuiescent(lease, first), "OK");
      call("complete", () -> complete(first), "OK");
      call("complete-replay", () -> complete(first), "SEARCH_PROCESS_UNPROVEN");
      call(
          "page",
          () ->
              ledger.searchPage(
                  lease, Payloads.hash("query"), "glob-fixture-pin", "private-cursor"),
          "OK");
      call(
          "page-query",
          () -> ledger.searchPage(lease, "different", "glob-fixture-pin", "private-cursor"),
          "SEARCH_CURSOR_INVALID");
      call(
          "page-pin",
          () -> ledger.searchPage(lease, Payloads.hash("query"), "different", "private-cursor"),
          "SEARCH_CURSOR_INVALID");
      call(
          "page-missing",
          () -> ledger.searchPage(lease, Payloads.hash("query"), "glob-fixture-pin", "missing"),
          "SEARCH_CURSOR_INVALID");
      invocation("grep");
      String second = invocation;
      call("prepare-second", () -> prepare(), "OK");
      var secondRun = run(second, lease.attemptId());
      call("remove-running", () -> remove(secondRun), "SEARCH_PROCESS_UNPROVEN");
      invocation("glob");
      call("prepare-total-limit", () -> prepare(), "SEARCH_RESULT_LIMIT");
      var good = lease;
      lease = altered(good);
      call("prepare-fence", () -> prepare(), "FENCE_REJECTED");
      lease = good;
      // Explicit legal recovery state: old native rows retain their original writer attempt.
      // This is a synthetic ledger fixture, not proof that any real process died.
      String newerAttempt = JdbcLedger.id();
      db.update(
          "UPDATE task_attempt SET state='STOPPED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
          lease.tenant(),
          lease.attemptId());
      db.update(
          "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES(?,?,?,?,?,'ACTIVE')",
          lease.tenant(),
          newerAttempt,
          task,
          lease.worker(),
          lease.fence() + 1);
      db.update(
          "UPDATE task SET current_attempt_id=?,fence=fence+1 WHERE tenant_id=? AND id=?",
          newerAttempt,
          lease.tenant(),
          task);
      call("scan-old-writer", () -> ledger.nativeSearches("native-private"), "OK");
      call("stop-old-fence-repeat", () -> stop(firstRun, identity), "OK");
      call("complete-stale-attempt", () -> complete(second), "FENCE_REJECTED");
      call(
          "stop-before-start",
          () ->
              stop(
                  secondRun,
                  new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity(
                      null, null, null)),
          "OK");
      call("remove-reserved", () -> remove(secondRun), "OK");
      call("remove-reserved-repeat", () -> remove(secondRun), "OK");
      db.update(
          "UPDATE native_search_run SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=? AND root_invocation=?",
          lease.tenant(),
          first);
      call("expired-active-task", () -> ledger.expiredSearches("native-private"), "OK");
      db.update(
          "UPDATE task SET state='CANCELLED' WHERE tenant_id=? AND id=?", lease.tenant(), task);
      call("expired-active-attempt", () -> ledger.expiredSearches("native-private"), "OK");
      db.update(
          "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND task_id=?",
          lease.tenant(),
          task);
      call("expired", () -> ledger.expiredSearches("native-private"), "OK");
      call("expired-foreign-host", () -> ledger.expiredSearches("foreign-host"), "OK");
      call("remove-settled", () -> remove(firstRun), "OK");
      call("remove-settled-repeat", () -> remove(firstRun), "OK");
      call("expired-removed", () -> ledger.expiredSearches("native-private"), "OK");
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
