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

/** Web 观察、取消和保存公开入口；复用原凭证 wire 夹具，外层 rollback。 */
public final class WebFetchScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;
  private static String output;

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
            WebFetchScenario.class.getClassLoader(),
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
                    WebFetchScenario.class.getClassLoader(),
                    new Class<?>[] {java.sql.ResultSet.class},
                    (rp, rm, ra) -> {
                      Object item = invoke(result, rm, ra);
                      if (measuring
                          && rm.getName().startsWith("get")
                          && ra != null
                          && ra.length == 1)
                        getters.add(
                            Map.of(
                                "method",
                                rm.getName(),
                                "column",
                                ra[0].toString(),
                                "type",
                                item == null ? "NULL" : item.getClass().getName()));
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
    for (String table : List.of("artifact", "reservation", "event", "invocation", "task_attempt"))
      out.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND task_id=?", task));
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
    if (value instanceof Map<?, ?> map) {
      var converted = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> converted.put(key.toString(), evidenceValue(item)));
      return converted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(WebFetchScenario::evidenceValue).toList();
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
    if (primary != null) primary.printStackTrace(System.out);
    require(
        expected.equals(out.get("code")),
        name + " expected " + expected + " got " + out.get("code"));
  }

  private static void setup() {
    ledger.registerWorker(
        "web-private", "web-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("web-private").orElseThrow();
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
    // WebFetchReceiptsTest original wire/pin/input binding, persisted without executing
    // Host/provider.
    step = JdbcLedger.id();
    invocation = JdbcLedger.id();
    String url = "https://fixture.example/source";
    String input =
        Payloads.json(new io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput(url));
    output =
        Payloads.json(
            new io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput(
                200, url, "Safe local fixture", false));
    String request = Payloads.json(Map.of("requestBody", "{}", "bodyDigest", Payloads.hash("{}")));
    String response =
        Payloads.json(
            Map.of(
                "calls", List.of(Map.of("id", "call", "name", "web_fetch", "arguments", input))));
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest) VALUES(?,?,?,?,1,'PREPARED',?,?,?,?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        request,
        Payloads.hash(request),
        response,
        Payloads.hash(response));
    var pin = ledger.tools(row).pins().get("web_fetch");
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,1,'0:call','call',?,'web_fetch',?,?,?,?,?,'RUNNING')",
        lease.tenant(),
        invocation,
        task,
        step,
        lease.attemptId(),
        JdbcLedger.id(),
        pin.version(),
        pin.digest(),
        pin.installationEpoch(),
        input,
        Payloads.hash(input));
    ledger.reserve(lease.tenant(), task, invocation, null, "TOOL", 1, "RESERVED", "KNOWN");
  }

  private static Object observe(
      io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation value) {
    ledger.observeWebFetch(lease, invocation, value);
    return null;
  }

  private static Object preserve(String value) {
    ledger.preserveWebFetchResult(lease, invocation, value);
    return null;
  }

  private static void change(String column, Object value) {
    db.update(
        "UPDATE invocation SET " + column + "=? WHERE tenant_id=? AND id=?",
        value,
        lease.tenant(),
        invocation);
  }

  private static Lease altered(
      Lease good, String tenant, String attempt, long fence, String worker) {
    return new Lease(
        tenant,
        good.actor(),
        good.taskId(),
        attempt,
        fence,
        worker,
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
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-web", props));
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
    task = args[1];
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {
      setup();
      var phase =
          new io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation(
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase.BODY,
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure.NONE);
      var failed =
          new io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation(
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase.DNS,
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure.DNS);
      call("observe-phase", () -> observe(phase), "OK");
      call("observe-phase-replay", () -> observe(phase), "OK");
      call("observe-failure", () -> observe(failed), "OK");
      call("observe-null", () -> observe(null), "java.lang.NullPointerException");
      change("tool", "other");
      call("observe-tool", () -> observe(phase), "INVOCATION_STATE");
      change("tool", "web_fetch");
      change("plugin_version", "0.2.0");
      call("observe-version", () -> observe(phase), "INVOCATION_STATE");
      change("plugin_version", "0.3.0");
      change("state", "SUCCEEDED");
      call("observe-state", () -> observe(phase), "INVOCATION_STATE");
      call("preserve-state", () -> preserve(output), "INVOCATION_STATE");
      change("state", "RUNNING");
      String originalInvocation = invocation;
      invocation = JdbcLedger.id();
      call("preserve-missing-invocation", () -> preserve(output), "NOT_FOUND");
      invocation = originalInvocation;
      change("input_digest", "0".repeat(64));
      call("preserve-proof", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      change(
          "input_digest",
          Payloads.hash(
              Payloads.json(
                  new io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput(
                      "https://fixture.example/source"))));
      var good = lease;
      lease = altered(good, good.tenant(), good.attemptId(), good.fence() + 1, good.worker());
      call("fence-observe", () -> observe(phase), "FENCE_REJECTED");
      call("fence-preserve", () -> preserve(output), "FENCE_REJECTED");
      lease = good;
      call("preserve-new", () -> preserve(output), "OK");
      call("preserve-replay", () -> preserve(output), "OK");
      // Original fenced(false) allows observations and preservation of an admitted effect
      // after access revocation; do not accidentally strengthen it into an admission check.
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("permission-observe", () -> observe(phase), "OK");
      call("permission-preserve", () -> preserve(output), "OK");
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      String different = output.replace("Safe local fixture", "Changed local fixture");
      call("preserve-conflict", () -> preserve(different), "WEB_RESULT_PROOF_INVALID");
      String receipt =
          db.queryForObject(
              "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
              String.class,
              lease.tenant(),
              task,
              "web-result:" + invocation);
      db.update(
          "UPDATE event SET code=? WHERE tenant_id=? AND task_id=? AND fact_id=?",
          "0".repeat(64),
          lease.tenant(),
          task,
          "web-result:" + invocation);
      call("preserve-receipt-conflict", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      db.update(
          "UPDATE event SET code=? WHERE tenant_id=? AND task_id=? AND fact_id=?",
          receipt,
          lease.tenant(),
          task,
          "web-result:" + invocation);
      change("output_snapshot", different);
      call("preserve-output-conflict", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      change("output_snapshot", output);
      change("output_digest", "0".repeat(64));
      call("preserve-digest-conflict", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      change("output_digest", Payloads.hash(output));
      db.update(
          "DELETE FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
          lease.tenant(),
          task,
          "web-result:" + invocation);
      call("preserve-orphan-output", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      change("output_snapshot", null);
      call("preserve-orphan-digest", () -> preserve(output), "WEB_RESULT_PROOF_INVALID");
      change("output_snapshot", output);
      // Old original attempt is diagnostic authority only: current fence advanced, expired,
      // access revoked, original attempt stopped. No execution or settlement is requested.
      db.update(
          "UPDATE task SET fence=fence+1,lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND,state='NEEDS_RECONCILIATION' WHERE tenant_id=? AND id=?",
          lease.tenant(),
          task);
      db.update(
          "UPDATE task_attempt SET state='STOPPED' WHERE tenant_id=? AND id=?",
          lease.tenant(),
          lease.attemptId());
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      var cancel =
          new io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation(
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase.BODY,
              io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure.CANCELLED,
              new io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Cancellation(
                  io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Reason
                      .CHECKPOINT_REJECTED,
                  io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase.BODY,
                  123,
                  io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.CheckpointCode
                      .FENCE_REJECTED,
                  7));
      call("cancel-expired-original", () -> observe(cancel), "OK");
      change("state", "OUTCOME_UNKNOWN");
      call("cancel-unknown-replay", () -> observe(cancel), "OK");
      lease = altered(good, good.tenant(), good.attemptId(), good.fence() + 1, good.worker());
      call("cancel-foreign-fence", () -> observe(cancel), "WEB_OBSERVATION_BINDING_REJECTED");
      lease = altered(good, good.tenant(), good.attemptId(), good.fence(), "foreign-worker");
      call("cancel-foreign-worker", () -> observe(cancel), "WEB_OBSERVATION_BINDING_REJECTED");
      lease = altered(good, good.tenant(), JdbcLedger.id(), good.fence(), good.worker());
      call("cancel-foreign-attempt", () -> observe(cancel), "WEB_OBSERVATION_BINDING_REJECTED");
      lease = altered(good, "tenant-b", good.attemptId(), good.fence(), good.worker());
      call("cancel-foreign-tenant", () -> observe(cancel), "NOT_FOUND");
      lease = good;
      change("state", "SUCCEEDED");
      call("cancel-succeeded", () -> observe(cancel), "WEB_OBSERVATION_BINDING_REJECTED");
    } catch (Throwable failure) {
      primary = failure;
      failure.printStackTrace(System.out);
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
    require(
        initial.equals(facts()), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
