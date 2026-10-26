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

/** Image public lifecycle using original wire values, real live lease and outer rollback. */
public final class McpLifecycleScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;
  private static String output;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot snapshot;
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
            McpLifecycleScenario.class.getClassLoader(),
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
                    McpLifecycleScenario.class.getClassLoader(),
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
            "mcp_server",
            "mcp_io",
            "mcp_result",
            "mcp_cursor",
            "reservation",
            "event",
            "invocation",
            "step",
            "task_attempt"))
      out.put(
          table,
          table.equals("mcp_server")
              ? db.queryForList("SELECT * FROM mcp_server WHERE tenant_id='tenant-a'")
              : db.queryForList(
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
      return list.stream().map(McpLifecycleScenario::evidenceValue).toList();
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
        "mcp-private", "mcp-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("mcp-private").orElseThrow();
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
    String input =
        Payloads.json(
            name.equals("read_mcp_resource")
                ? Map.of("server", "controlled", "uri", "jimu-fixture://tenant-a/docs/one")
                : Map.of("server", "controlled"));
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

  private static Object reserve() {
    ledger.reserveMcpList(lease, invocation);
    return null;
  }

  private static Object success(
      String value, io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot snap) {
    ledger.mcpSucceeded(lease, invocation, value, snap);
    return null;
  }

  private static void snapshot(boolean templates) {
    var server = ledger.mcpServer(lease, "controlled");
    String name = templates ? "list_mcp_resource_templates" : "list_mcp_resources";
    var pin = ledger.tools(taskRow()).pins().get(name);
    String query =
        Payloads.hash(
            Payloads.json(
                new io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Query(
                    templates ? "resources/templates/list" : "resources/list",
                    "controlled",
                    server.digest(),
                    io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts.DIGEST,
                    pin.version(),
                    pin.digest(),
                    pin.installationEpoch())));
    snapshot =
        McpLifecycleValues.list(
            task, invocation, (String) taskRow().get("mcp_digest"), query, server, templates);
    output = snapshot.pages().getFirst();
  }

  private static Object page(String query, String token) {
    return ledger.mcpPage(lease, query, token);
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

  private static io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot replaceToken(
      io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot v, String token) {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot(
        v.version(),
        v.tenant(),
        v.owner(),
        v.taskId(),
        v.rootInvocation(),
        v.server(),
        v.method(),
        v.bindingDigest(),
        v.queryDigest(),
        v.projectionDigest(),
        v.pages().stream().map(p -> p.replace(McpLifecycleValues.CURSOR, token)).toList(),
        Map.of(Payloads.hash(token), 1));
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
    task = args[1];
    ledger.mcpSettings(new io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings(env));
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {
      setup();
      call("binding", () -> ledger.mcpBinding(lease), "OK");
      call("server", () -> ledger.mcpServer(lease, "controlled"), "OK");
      call("server-missing", () -> ledger.mcpServer(lease, "missing"), "MCP_SERVER_NOT_AUTHORIZED");
      invocation("list_mcp_resources");
      call(
          "dispatch-missing",
          () -> ledger.mcpDispatch(lease, JdbcLedger.id(), "resources/list", "r", "d", false),
          "NOT_FOUND");
      call(
          "dispatch-normal",
          () ->
              ledger.mcpDispatch(
                  lease,
                  invocation,
                  "resources/list",
                  "request-normal",
                  Payloads.hash("normal"),
                  false),
          "OK");
      call(
          "respond-normal",
          () -> {
            ledger.mcpResponded(lease, invocation, 1, null);
            return null;
          },
          "OK");
      call(
          "respond-duplicate",
          () -> {
            ledger.mcpResponded(lease, invocation, 1, null);
            return null;
          },
          "INVOCATION_STATE");
      call(
          "dispatch-cleanup",
          () ->
              ledger.mcpDispatch(
                  lease,
                  invocation,
                  "resources/list",
                  "request-cleanup",
                  Payloads.hash("cleanup"),
                  true),
          "OK");
      call(
          "respond-error",
          () -> {
            ledger.mcpResponded(lease, invocation, 2, "MCP_UNAVAILABLE");
            return null;
          },
          "OK");
      call(
          "cleanup-duplicate",
          () -> ledger.mcpDispatch(lease, invocation, "resources/list", "r", "d", true),
          "MCP_LIMIT_EXCEEDED");
      call("reserve", () -> reserve(), "OK");
      snapshot(false);
      var valid = snapshot;
      snapshot =
          new io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot(
              valid.version(),
              valid.tenant(),
              valid.owner(),
              valid.taskId(),
              valid.rootInvocation(),
              valid.server(),
              valid.method(),
              valid.bindingDigest(),
              valid.queryDigest(),
              Payloads.hash("wrong"),
              valid.pages(),
              valid.cursorPositions());
      call("success-corrupt", () -> success(output, snapshot), "SNAPSHOT_CORRUPT");
      snapshot = valid;
      call("success", () -> success(output, snapshot), "OK");
      call("success-replay", () -> success(output, snapshot), "OK");
      call("success-conflict", () -> success("different", snapshot), "SNAPSHOT_CORRUPT");
      call("page", () -> page(snapshot.queryDigest(), McpLifecycleValues.CURSOR), "OK");
      call("page-query", () -> page("wrong", McpLifecycleValues.CURSOR), "MCP_CURSOR_INVALID");
      call("page-token", () -> page(snapshot.queryDigest(), "wrong"), "MCP_CURSOR_INVALID");
      db.update(
          "UPDATE mcp_result SET body_digest=? WHERE tenant_id=? AND task_id=? AND root_invocation_id=?",
          Payloads.hash("wrong"),
          lease.tenant(),
          task,
          invocation);
      call(
          "page-digest",
          () -> page(snapshot.queryDigest(), McpLifecycleValues.CURSOR),
          "MCP_CURSOR_INVALID");
      db.update(
          "UPDATE mcp_result SET body_digest=? WHERE tenant_id=? AND task_id=? AND root_invocation_id=?",
          Payloads.hash(Payloads.json(snapshot)),
          lease.tenant(),
          task,
          invocation);
      invocation("list_mcp_resource_templates");
      call("reserve-template", () -> reserve(), "OK");
      snapshot(true);
      snapshot = replaceToken(snapshot, "ZYXWVUTSRQPONMLKJIHGFE");
      output = snapshot.pages().getFirst();
      call("success-template", () -> success(output, snapshot), "OK");
      call("page-template", () -> page(snapshot.queryDigest(), "ZYXWVUTSRQPONMLKJIHGFE"), "OK");
      // The cursor is task-wide unique; resources and templates must use separate legal tokens.
      invocation("read_mcp_resource");
      var server = ledger.mcpServer(lease, "controlled");
      output =
          Payloads.json(
              new io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort.Read(
                  1,
                  "controlled",
                  "1",
                  server.digest(),
                  server.profile().schemaDigest(),
                  "untrusted-mcp-content-v1",
                  invocation,
                  Payloads.hash("[]"),
                  "jimu-fixture://tenant-a/docs/one",
                  List.of()));
      call("success-null-snapshot", () -> success(output, null), "OK");
      call(
          "register",
          () ->
              ledger.submit(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      lease.tenant(), lease.actor()),
                  "mcp-second",
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                      "mcp-second",
                      io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES,
                      List.of(),
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                          120, 5, 10, null))),
          "OK");
      call("binding-fence", () -> ledger.mcpBinding(altered(lease)), "FENCE_REJECTED");
      invocation("list_mcp_resources");
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("binding-permission", () -> ledger.mcpBinding(lease), "ACCESS_REVOKED");
      call(
          "dispatch-permission",
          () -> ledger.mcpDispatch(lease, invocation, "resources/list", "r", "d", false),
          "ACCESS_REVOKED");
      call("reserve-permission", () -> reserve(), "ACCESS_REVOKED");
      call("success-permission", () -> success(output, null), "ACCESS_REVOKED");
      call(
          "page-permission",
          () -> page(snapshot.queryDigest(), "ZYXWVUTSRQPONMLKJIHGFE"),
          "ACCESS_REVOKED");
      call(
          "cleanup-permission",
          () ->
              ledger.mcpDispatch(
                  lease, invocation, "resources/list", "r", Payloads.hash("d"), true),
          "OK");
      call(
          "respond-permission",
          () -> {
            ledger.mcpResponded(lease, invocation, 1, null);
            return null;
          },
          "OK");
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
    String restored = facts();
    System.out.println("FULLAFTER " + restored);
    require(
        initial.equals(restored), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
