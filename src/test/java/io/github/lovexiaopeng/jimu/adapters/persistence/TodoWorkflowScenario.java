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

/** Directory ledger lifecycle: synthetic Evidence, live authorization and private rollback. */
public final class TodoWorkflowScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;

  private record Seed(
      String step,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call call,
      String version,
      String digest) {}

  private static final Map<String, Seed> seeds = new LinkedHashMap<>();

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
            TodoWorkflowScenario.class.getClassLoader(),
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
                    TodoWorkflowScenario.class.getClassLoader(),
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
        List.of(
            "task_todo_projection", "reservation", "event", "invocation", "step", "task_attempt"))
      out.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND task_id=?", task));
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
      return list.stream().map(TodoWorkflowScenario::evidenceValue).toList();
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

  private static void setup() {
    ledger.registerWorker(
        "todo-private", "todo-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("todo-private").orElseThrow();
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

  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call callRow(
      String id) {
    var r =
        db.queryForMap("SELECT * FROM invocation WHERE tenant_id=? AND id=?", lease.tenant(), id);
    return new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
        (String) r.get("slot"),
        (String) r.get("provider_id"),
        (String) r.get("tool"),
        (String) r.get("input_snapshot"));
  }

  private static String seedStep(List<String> names, List<String> inputs) {
    step = JdbcLedger.id();
    counter++;
    var calls =
        new ArrayList<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall>();
    for (int n = 0; n < names.size(); n++)
      calls.add(
          new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
              "call" + counter + "-" + n, "function", names.get(n), inputs.get(n)));
    String response =
        Payloads.json(
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response(
                2, "", calls, null, null));
    String request = Payloads.json(Map.of("explicitFixture", true));
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest) VALUES(?,?,?,?,?,'RESPONDED',?,?,?,?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        counter,
        request,
        Payloads.hash(request),
        response,
        Payloads.hash(response));
    for (int n = 0; n < names.size(); n++) {
      String id = JdbcLedger.id();
      var pin = ledger.tools(taskRow()).pins().get(names.get(n));
      db.update(
          "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
          lease.tenant(),
          id,
          task,
          step,
          lease.attemptId(),
          counter * 4 + n,
          n + ":" + calls.get(n).id(),
          calls.get(n).id(),
          JdbcLedger.id(),
          names.get(n),
          pin.version(),
          pin.digest(),
          pin.installationEpoch(),
          inputs.get(n),
          Payloads.hash(inputs.get(n)));
      db.update(
          "UPDATE invocation SET todo_expected_revision=(SELECT revision FROM task_todo_projection WHERE tenant_id=? AND task_id=?) WHERE tenant_id=? AND id=?",
          lease.tenant(),
          task,
          lease.tenant(),
          id);
      seeds.put(
          id,
          new Seed(
              step,
              new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                  n + ":" + calls.get(n).id(), calls.get(n).id(), names.get(n), inputs.get(n)),
              pin.version(),
              pin.digest()));
      if (n == 0) invocation = id;
    }
    return invocation;
  }

  private static Object replace(String id) {
    var r = seeds.get(id);
    return ledger.replaceOrResult(lease, r.step(), r.call(), r.version(), r.digest());
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

    ledger.todosEnabled(true);
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {

      var owner =
          new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");
      task =
          ledger
              .submit(
                  owner,
                  "todo-workflow-private",
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                      "todo-workflow-private",
                      List.of("todo_write"),
                      List.of(),
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                          120, 5, 20, null)))
              .taskId();
      setup();
      System.out.println(
          "PROVENANCE explicit legal response/intent/invocation; no worker/provider execution; outer rollback only");
      call("owner-initial", () -> ledger.getTodos(owner, task), "OK");
      String first =
          seedStep(
              List.of("todo_write"),
              List.of(
                  "{\"todos\":[{\"content\":\"核对/😀\",\"status\":\"in_progress\"},{\"content\":\"留证\",\"status\":\"pending\"}]}"));
      call("replace-first", () -> replace(first), "OK");
      call("owner-content", () -> ledger.getTodos(owner, task), "OK");
      call("replay-first", () -> replace(first), "OK");
      String second = seedStep(List.of("todo_write"), List.of("{\"todos\":[]}"));
      call("replace-second", () -> replace(second), "OK");
      call("owner-empty", () -> ledger.getTodos(owner, task), "OK");
      call("replay-old", () -> replace(first), "OK");
      call("replay-second", () -> replace(second), "OK");
      var r = seeds.get(second);
      call(
          "fence",
          () -> ledger.replaceOrResult(altered(lease), r.step(), r.call(), r.version(), r.digest()),
          "FENCE_REJECTED");
      call(
          "bad-provider",
          () ->
              ledger.replaceOrResult(
                  lease,
                  r.step(),
                  new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                      r.call().slot(), "forged", r.call().tool(), r.call().input()),
                  r.version(),
                  r.digest()),
          "INVOCATION_MISMATCH");
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("replace-permission", () -> replace(second), "ACCESS_REVOKED");
      call("owner-permission", () -> ledger.getTodos(owner, task), "ACCESS_REVOKED");
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call(
          "owner-hidden",
          () ->
              ledger.getTodos(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-a", "carol"),
                  task),
          "NOT_FOUND");
      String absent =
          ledger
              .submit(
                  owner,
                  "todo-absent-private",
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                      "todo-absent-private",
                      List.of("web_fetch"),
                      List.of(),
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                          120, 5, 5, null)))
              .taskId();
      call("owner-absent", () -> ledger.getTodos(owner, absent), "OK");
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
