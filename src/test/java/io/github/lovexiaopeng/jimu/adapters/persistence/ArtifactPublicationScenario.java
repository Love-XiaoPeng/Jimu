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

/** 三公开入口的有界旧新对照；合法 SQL setup、外层 rollback，不执行 worker/provider。 */
public final class ArtifactPublicationScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String artifact;
  private static final String HASH = Payloads.hash("产物 publication fixture");

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
            ArtifactPublicationScenario.class.getClassLoader(),
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
                    ArtifactPublicationScenario.class.getClassLoader(),
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
      return list.stream().map(ArtifactPublicationScenario::evidenceValue).toList();
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
        "publication-private",
        "publication-private",
        ProcessHandle.current().pid(),
        1,
        "private",
        "private");
    lease = ledger.claim("publication-private").orElseThrow();
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
        "complete lease keys");
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
    // Explicit legal succeeded invocation with matching task pin, input/output digests and step FK.
    // This does not claim Host/tool/model execution or a provider receipt.
    String step = JdbcLedger.id(), invocation = JdbcLedger.id();
    var pin = ledger.tools(row).pins().get("web_fetch");
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest) VALUES(?,?,?,?,1,'PREPARED','{}',?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        Payloads.hash("{}"));
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest) VALUES(?,?,?,?,?,1,'fixture-slot','fixture-provider',?,'web_fetch',?,?,?,'{}',?,'SUCCEEDED','{}',?)",
        lease.tenant(),
        invocation,
        task,
        step,
        lease.attemptId(),
        JdbcLedger.id(),
        pin.version(),
        pin.digest(),
        lease.installationEpoch(),
        Payloads.hash("{}"),
        Payloads.hash("{}"));
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-publication", props));
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
      call("stage-too-large", () -> ledger.stage(lease, HASH, 1048577), "STORAGE_BUDGET_EXCEEDED");
      call(
          "stage-new",
          () -> {
            var u = ledger.stage(lease, HASH, 3);
            artifact = u.id();
            return u;
          },
          "OK");
      call("stage-staging-replay", () -> ledger.stage(lease, HASH, 3), "OK");
      call(
          "stage-hash-mismatch", () -> ledger.stage(lease, "0".repeat(64), 3), "ARTIFACT_MISMATCH");
      call("stage-size-mismatch", () -> ledger.stage(lease, HASH, 4), "ARTIFACT_MISMATCH");
      // A failed inner transaction marks the outer transaction rollback-only, but the fixture
      // deliberately stays in the same outer transaction and ends by unconditional rollback.
      call(
          "publish-unverified",
          () -> {
            ledger.publish(lease, artifact);
            return null;
          },
          "ARTIFACT_UNVERIFIED");
      call(
          "verify",
          () -> {
            ledger.verified(lease, artifact, "immutable-fixture-key");
            return null;
          },
          "OK");
      call(
          "verify-replay-zero-updates",
          () -> {
            ledger.verified(lease, artifact, "must-not-replace-key");
            return null;
          },
          "OK");
      call(
          "publish",
          () -> {
            ledger.publish(lease, artifact);
            return null;
          },
          "OK");
      call(
          "publish-replay",
          () -> {
            ledger.publish(lease, artifact);
            return null;
          },
          "OK");
      call("stage-published-replay", () -> ledger.stage(lease, HASH, 3), "OK");
      call(
          "verified-missing-count-ignored",
          () -> {
            ledger.verified(lease, JdbcLedger.id(), null);
            return null;
          },
          "OK");
      var good = lease;
      lease =
          new Lease(
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
      for (String entry : List.of("stage", "verified", "publish"))
        call("fence-" + entry, () -> entry(entry), "FENCE_REJECTED");
      lease = good;
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      for (String entry : List.of("stage", "verified", "publish"))
        call("permission-" + entry, () -> entry(entry), "ACCESS_REVOKED");
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

  private static Object entry(String name) {
    switch (name) {
      case "stage" -> {
        return ledger.stage(lease, HASH, 3);
      }
      case "verified" -> ledger.verified(lease, artifact, "key");
      case "publish" -> ledger.publish(lease, artifact);
      default -> throw new AssertionError(name);
    }
    return null;
  }
}
