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
public final class ImageLifecycleScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;
  private static String output;
  private static String inputId, image, key;
  private static io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result result;

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
            ImageLifecycleScenario.class.getClassLoader(),
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
                    ImageLifecycleScenario.class.getClassLoader(),
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
    for (String table :
        List.of(
            "task_image_input",
            "task_image_object",
            "reservation",
            "event",
            "invocation",
            "step",
            "task_attempt"))
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
      return list.stream().map(ImageLifecycleScenario::evidenceValue).toList();
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
        "image-private", "image-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("image-private").orElseThrow();
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
    step = JdbcLedger.id();
    invocation = JdbcLedger.id();
    String input = Payloads.json(Map.of("file_path", "inputs/images/sample.png"));
    String request = Payloads.json(Map.of("requestBody", "{}", "bodyDigest", Payloads.hash("{}")));
    String response =
        Payloads.json(
            Map.of(
                "calls", List.of(Map.of("id", "call", "name", "read_image", "arguments", input))));
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
    var pin = ledger.tools(row).pins().get("read_image");
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,1,'0:call','call',?,'read_image',?,?,?,?,?,'RUNNING')",
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
    db.update("UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
    db.update(
        "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?", lease.tenant(), task);
    inputId =
        (String)
            db.queryForMap(
                    "SELECT input_id FROM task_image_input WHERE tenant_id=? AND task_id=?",
                    lease.tenant(),
                    task)
                .get("input_id");
  }

  private static Object succeeded(io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result value) {
    ledger.imageSucceeded(lease, invocation, key, value);
    return null;
  }

  private static Object timeout() {
    ledger.imageDecodeTimedOut(lease);
    return null;
  }

  private static void inputChange(String column, Object value) {
    db.update(
        "UPDATE task_image_input SET " + column + "=? WHERE tenant_id=? AND input_id=?",
        value,
        lease.tenant(),
        inputId);
  }

  private static void objectChange(String column, Object value) {
    db.update(
        "UPDATE task_image_object SET " + column + "=? WHERE tenant_id=? AND image_id=?",
        value,
        lease.tenant(),
        image);
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
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-image", props));
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
    ledger.imageStorage(
        new io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore(
            Path.of(props.getProperty("jimu.images.root"))));
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {
      setup();
      call("snapshot", () -> ledger.mediaSnapshot(lease), "OK");
      call("input", () -> ledger.imageInput(lease, "inputs/images/sample.png"), "OK");
      call(
          "input-missing",
          () -> ledger.imageInput(lease, "inputs/images/missing.png"),
          "FS_NOT_FOUND");
      String raw =
          (String)
              db.queryForMap(
                      "SELECT raw_base64 FROM task_image_input WHERE tenant_id=? AND input_id=?",
                      lease.tenant(),
                      inputId)
                  .get("raw_base64");
      inputChange("raw_base64", null);
      call("snapshot-null-input", () -> ledger.mediaSnapshot(lease), "IMAGE_UNAVAILABLE");
      inputChange("raw_base64", raw);
      inputChange("revoked_at", java.sql.Timestamp.from(java.time.Instant.now()));
      call(
          "input-revoked",
          () -> ledger.imageInput(lease, "inputs/images/sample.png"),
          "IMAGE_UNAVAILABLE");
      inputChange("revoked_at", null);
      inputChange("expires_at", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(30)));
      call(
          "input-expired",
          () -> ledger.imageInput(lease, "inputs/images/sample.png"),
          "IMAGE_UNAVAILABLE");
      inputChange("expires_at", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
      inputChange("raw_base64", java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2}));
      call(
          "input-length",
          () -> ledger.imageInput(lease, "inputs/images/sample.png"),
          "SNAPSHOT_CORRUPT");
      inputChange("raw_base64", raw);
      change("state", "OUTCOME_UNKNOWN");
      call(
          "reserve-unknown",
          () -> ledger.reserveImage(lease, invocation, inputId),
          "INVOCATION_STATE");
      change("state", "RUNNING");
      call(
          "reserve-missing",
          () -> ledger.reserveImage(lease, JdbcLedger.id(), inputId),
          "INVOCATION_STATE");
      long limit =
          ((Number)
                  db.queryForMap(
                          "SELECT storage_limit FROM tenant_budget WHERE tenant_id=?",
                          lease.tenant())
                      .get("storage_limit"))
              .longValue();
      db.update(
          "UPDATE tenant_budget SET storage_limit=storage_held WHERE tenant_id=?", lease.tenant());
      call(
          "reserve-budget",
          () -> ledger.reserveImage(lease, invocation, inputId),
          "STORAGE_BUDGET_EXCEEDED");
      db.update(
          "UPDATE tenant_budget SET storage_limit=? WHERE tenant_id=?", limit, lease.tenant());
      call(
          "reserve",
          () -> {
            image = ledger.reserveImage(lease, invocation, inputId);
            return image;
          },
          "OK");
      call(
          "reserve-replay",
          () -> ledger.reserveImage(lease, invocation, inputId),
          "EFFECT_OUTCOME_UNKNOWN");
      var entry = ledger.mediaSnapshot(lease).entries().getFirst();
      byte[] bytes = java.util.Base64.getDecoder().decode(raw);
      var normalized =
          new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Image(
              image, "image/png", Payloads.hash(bytes), bytes.length, 3, 2, 3, 2, "jimu-image-v1");
      result =
          new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result(
              entry.filePath(),
              entry.inputId(),
              "image:" + invocation,
              entry.sha256(),
              entry.size(),
              normalized);
      key = image + "-" + normalized.sha256();
      output = Payloads.json(result);
      call("object-reserved", () -> ledger.imageObject(lease, result), "IMAGE_UNAVAILABLE");
      String goodKey = key;
      key = "bad";
      call("success-key", () -> succeeded(result), "SNAPSHOT_CORRUPT");
      key = goodKey;
      objectChange("phase", "COMMITTED");
      call("success-count", () -> succeeded(result), "SNAPSHOT_CORRUPT");
      objectChange("phase", "RESERVED");
      var good = lease;
      lease = altered(good, good.tenant(), good.attemptId(), good.fence() + 1, good.worker());
      call("fence-snapshot", () -> ledger.mediaSnapshot(lease), "FENCE_REJECTED");
      call("fence-success", () -> succeeded(result), "FENCE_REJECTED");
      call("fence-timeout", () -> timeout(), "FENCE_REJECTED");
      lease = good;
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call(
          "permission-input",
          () -> ledger.imageInput(lease, "inputs/images/sample.png"),
          "ACCESS_REVOKED");
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("success", () -> succeeded(result), "OK");
      call("success-replay", () -> succeeded(result), "SNAPSHOT_CORRUPT");
      call("object", () -> ledger.imageObject(lease, result), "OK");
      call("step", () -> ledger.stepImages(lease, step), "OK");
      call("step-empty", () -> ledger.stepImages(lease, JdbcLedger.id()), "OK");
      change("plugin_digest", "0".repeat(64));
      call("object-pin", () -> ledger.imageObject(lease, result), "SNAPSHOT_CORRUPT");
      change("plugin_digest", ledger.tools(taskRow()).pins().get("read_image").digest());
      change("output_digest", "0".repeat(64));
      call("step-output-digest", () -> ledger.stepImages(lease, step), "SNAPSHOT_CORRUPT");
      change("output_digest", Payloads.hash(output));
      objectChange("cleanup_state", "REMOVED");
      call("object-cleanup", () -> ledger.imageObject(lease, result), "IMAGE_UNAVAILABLE");
      objectChange("cleanup_state", "RETAINED");
      String firstInvocation = invocation, firstImage = image, firstKey = key;
      var firstResult = result;
      invocation = JdbcLedger.id();
      db.update(
          "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) SELECT tenant_id,?,task_id,step_id,attempt_id,2,'1:second','second',?,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,'RUNNING' FROM invocation WHERE tenant_id=? AND id=?",
          invocation,
          JdbcLedger.id(),
          lease.tenant(),
          firstInvocation);
      ledger.reserve(lease.tenant(), task, invocation, null, "TOOL", 1, "RESERVED", "KNOWN");
      db.update("UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
      db.update(
          "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
          lease.tenant(),
          task);
      call(
          "reserve-second",
          () -> {
            image = ledger.reserveImage(lease, invocation, inputId);
            return image;
          },
          "OK");
      result =
          new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result(
              entry.filePath(),
              entry.inputId(),
              "image:" + invocation,
              entry.sha256(),
              entry.size(),
              new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Image(
                  image,
                  "image/png",
                  normalized.sha256(),
                  normalized.size(),
                  3,
                  2,
                  3,
                  2,
                  "jimu-image-v1"));
      key = image + "-" + normalized.sha256();
      call("success-second", () -> succeeded(result), "OK");
      call("step-two", () -> ledger.stepImages(lease, step), "OK");
      String secondOutput = Payloads.json(result);
      change(
          "output_snapshot",
          Payloads.json(
              new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result(
                  result.filePath(),
                  result.inputId(),
                  "image:wrong",
                  result.sourceSha256(),
                  result.sourceSize(),
                  result.image())));
      change(
          "output_digest",
          Payloads.hash(
              (String)
                  db.queryForMap(
                          "SELECT output_snapshot FROM invocation WHERE tenant_id=? AND id=?",
                          lease.tenant(),
                          invocation)
                      .get("output_snapshot")));
      call("step-second-late", () -> ledger.stepImages(lease, step), "SNAPSHOT_CORRUPT");
      change("output_snapshot", secondOutput);
      change("output_digest", Payloads.hash(secondOutput));
      invocation = firstInvocation;
      image = firstImage;
      key = firstKey;
      result = firstResult;
      // The unchanged public admission path exercises registration in both exact JARs.
      call(
          "register",
          () ->
              ledger.submit(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      lease.tenant(), lease.actor()),
                  "image-second",
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                      "image-second",
                      List.of("read_image"),
                      List.of(),
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                          120, 5, 10, null)),
                  List.of(
                      new io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Input(
                          "inputs/images/sample.png", raw))),
          "OK");
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("timeout-permission", () -> timeout(), "OK");
      call("timeout-replay", () -> timeout(), "OK");
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
