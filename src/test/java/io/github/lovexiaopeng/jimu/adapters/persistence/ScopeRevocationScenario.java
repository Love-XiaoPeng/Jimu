package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一驱动对准确旧新JAR；只恢复本次私有夹具创建的行，实际产品清理链。 */
public final class ScopeRevocationScenario {
  private static DriverManagerDataSource source;
  private static JdbcTemplate db;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static String task;
  private static Path barrier;
  private static Map<String, Object> budget;
  private static final Map<String, List<Map<String, Object>>> original = new LinkedHashMap<>();
  private static final List<String> tables =
      List.of(
          "task",
          "task_session_history",
          "session_public_event",
          "invocation_session_read",
          "invocation_session_search",
          "invocation_session_trace",
          "task_session_scope",
          "task_session_scope_target",
          "step_session_scope",
          "invocation_session_scope_query",
          "task_attempt",
          "step",
          "invocation",
          "model_dispatch",
          "reservation",
          "event");

  private static String key(String table) {
    return table.equals("task") ? "id" : table.contains("scope") ? "caller_task_id" : "task_id";
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static long scalar(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  private static Object normalize(Object v) {
    if (v instanceof java.time.temporal.TemporalAccessor || v instanceof java.util.Date)
      return v.toString();
    if (v instanceof Map<?, ?> m) {
      var result = new TreeMap<String, Object>();
      m.forEach((k, x) -> result.put(k.toString(), normalize(x)));
      return result;
    }
    if (v instanceof List<?> l) return l.stream().map(ScopeRevocationScenario::normalize).toList();
    return v;
  }

  private static String json(Object v) {
    return Payloads.json(normalize(v));
  }

  private static String facts() {
    var result = new LinkedHashMap<String, Object>();
    for (var t : tables)
      result.put(
          t,
          db.queryForList(
              "SELECT * FROM " + t + " WHERE tenant_id='tenant-a' AND " + key(t) + "=?", task));
    result.put("budget", db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'"));
    return json(result);
  }

  private static String replayFacts() {
    var value = Payloads.read(facts(), com.fasterxml.jackson.databind.JsonNode.class);
    for (var scope : value.path("task_session_scope"))
      ((com.fasterxml.jackson.databind.node.ObjectNode) scope).remove("next_validation_at");
    return value.toString();
  }

  private static String refusalFacts() {
    var value = Payloads.read(facts(), com.fasterxml.jackson.databind.JsonNode.class);
    for (var scope : value.path("task_session_scope")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) scope).remove("next_validation_at");
      ((com.fasterxml.jackson.databind.node.ObjectNode) scope).remove("invalidated_at");
    }
    for (var history : value.path("task_session_history"))
      ((com.fasterxml.jackson.databind.node.ObjectNode) history).remove("metadata_bytes");
    return value.toString();
  }

  private static void capture(String id) {
    task = id;
    original.clear();
    for (var t : tables)
      original.put(
          t,
          db.queryForList(
              "SELECT * FROM " + t + " WHERE tenant_id='tenant-a' AND " + key(t) + "=?", task));
    budget = db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'");
  }

  private static void reset() {
    long event = ((Number) original.get("task").getFirst().get("next_event_seq")).longValue();
    long history =
        ((Number) original.get("task_session_history").getFirst().get("next_seq")).longValue();
    db.update("DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND seq>?", task, event);
    db.update(
        "DELETE FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND seq>=?",
        task,
        history);
    for (var t : tables) {
      var keys =
          db.queryForList(
              "SELECT COLUMN_NAME FROM information_schema.key_column_usage WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION",
              String.class,
              t);
      for (var row : original.get(t)) restore(t, row, keys);
    }
    restore("tenant_budget", budget, List.of("tenant_id"));
  }

  private static void restore(String table, Map<String, Object> row, List<String> keys) {
    var cols = row.keySet().stream().filter(c -> !keys.contains(c)).toList();
    var values = new ArrayList<Object>();
    cols.forEach(c -> values.add(row.get(c)));
    keys.forEach(c -> values.add(row.get(c)));
    db.update(
        "UPDATE "
            + table
            + " SET "
            + String.join(",", cols.stream().map(c -> c + "=?").toList())
            + " WHERE "
            + String.join(" AND ", keys.stream().map(c -> c + "=?").toList()),
        values.toArray());
  }

  private static final io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity OWNER =
      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");

  private static void revoke(JdbcLedger owner) {
    owner.revokeSessionSearchScope(OWNER, task, true, owner.queryContext().deadlineNanos());
  }

  private static void reject(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("accepted " + code);
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure f) {
      require(f.code().equals(code), "actual refusal " + code);
    }
  }

  private static JdbcLedger current() throws Exception {
    return scoped(System.nanoTime() + 60_000_000_000L);
  }

  private static void revoked() {
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND grant_state='REVOKED' AND revoked_at IS NOT NULL AND invalidated_at IS NOT NULL",
                task)
            == 1,
        "scope atomically revoked");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT' AND held_bytes=8388608",
                task)
            == 1,
        "retained history not prematurely released");
  }

  private static JdbcLedger scoped(long end) throws Exception {
    return scoped(ledger, end);
  }

  private static JdbcLedger scoped(JdbcLedger owner, long end) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(owner, end, new QueryResources(0, 0, 0));
  }

  private static JdbcLedger failing(String prefix, AtomicInteger hits, Runnable hook) {
    var injected =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws SQLException {
            var actual = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      try {
                        var value = m.invoke(actual, a);
                        if (m.getName().equals("prepareStatement")
                            && ((String) a[0]).startsWith(prefix)) {
                          var statement = (Statement) value;
                          return Proxy.newProxyInstance(
                              java.sql.PreparedStatement.class.getClassLoader(),
                              new Class<?>[] {java.sql.PreparedStatement.class},
                              (proxy, method, args) -> {
                                if (method.getName().startsWith("execute")) {
                                  hits.incrementAndGet();
                                  if (hook == null && hits.get() == 1)
                                    throw new SQLException(
                                        "private scope maintenance SQL fault", "45000");
                                  try {
                                    var result = method.invoke(statement, args);
                                    if (hook != null) hook.run();
                                    return result;
                                  } catch (InvocationTargetException f) {
                                    throw f.getCause();
                                  }
                                }
                                try {
                                  return method.invoke(statement, args);
                                } catch (InvocationTargetException f) {
                                  throw f.getCause();
                                }
                              });
                        }
                        return value;
                      } catch (InvocationTargetException f) {
                        throw f.getCause();
                      }
                    });
          }
        };
    return configured(injected);
  }

  private static JdbcLedger configured(javax.sql.DataSource data) {
    return new JdbcLedger(data, models);
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new PropertiesPropertySource("private-history-retention", props));
    models = new ModelProfiles(env);
    source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                props.getProperty("jimu.db.url"),
                props.getProperty("jimu.db.migration-user"),
                props.getProperty("jimu.db.migration-password")));

    ledger = configured(source);
    barrier = Path.of(args[2]);
    String terminal = args[1], queued = args[3];
    String foreign =
        json(db.queryForList("SELECT * FROM task_session_history WHERE tenant_id='tenant-b'"));
    capture(terminal);
    String before = facts();
    for (var identity :
        List.of(
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "carol"),
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-b", "bob"))) {
      var request = current();
      reject(
          "SESSION_SCOPE_UNAVAILABLE",
          () ->
              request.revokeSessionSearchScope(
                  identity, task, false, request.queryContext().deadlineNanos()));
      require(before.equals(facts()), "foreign refusal leaves exact task and ledger facts");
    }
    var malformed = current();
    reject(
        "TOOL_INPUT_INVALID",
        () ->
            malformed.revokeSessionSearchScope(
                OWNER, task, false, malformed.queryContext().deadlineNanos()));
    require(before.equals(facts()), "malformed owner input rolls back without invalidation");
    System.out.println("RESULT owner and tenant authorization precedes malformed input");
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    try {
      var denied = current();
      reject(
          "SESSION_SCOPE_UNAVAILABLE",
          () ->
              denied.revokeSessionSearchScope(
                  OWNER, task, false, denied.queryContext().deadlineNanos()));
    } finally {
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    }
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND grant_state='ACTIVE' AND invalidated_at IS NOT NULL",
                task)
            == 1,
        "identity loss refuses revoke but afterCompletion post persists");
    require(
        scalar(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id='cancel'",
                task)
            == 0,
        "identity loss invents no cancellation");
    reset();
    System.out.println("RESULT current actor loss before input with durable invalidation");
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_RECONCILIATION")) {
      reset();
      db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", state, task);
      String taskBefore =
          json(db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task));
      var measured = current();
      revoke(measured);
      revoked();
      require(
          taskBefore.equals(
              json(
                  db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task))),
          "terminal and UNKNOWN preserve task state and fence");
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY terminal " + json(measured.queryContext().resources()));
      String repeated = facts();
      revoke(current());
      require(repeated.equals(facts()), "repeat revoke idempotent");
    }
    reset();
    System.out.println("RESULT four terminal and UNKNOWN states preserve task and idempotency");
    var hits = new AtomicInteger();
    var broken =
        scoped(
            failing("UPDATE task_session_scope SET grant_state='REVOKED'", hits, null),
            System.nanoTime() + 60_000_000_000L);
    before = facts();
    reject("SESSION_QUERY_UNAVAILABLE", () -> revoke(broken));
    require(hits.get() == 1 && before.equals(facts()), "revoke SQL fault rolls back exact facts");
    System.out.println("RESULT revoke SQL fault rollback");
    var owner = new JdbcLedger[1];
    hits = new AtomicInteger();
    var gateHits = hits;
    var gate =
        scoped(
            failing(
                "UPDATE task_session_scope SET grant_state='REVOKED'",
                gateHits,
                () ->
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                            new org.springframework.transaction.support
                                .TransactionSynchronization() {
                              public int getOrder() {
                                return Integer.MIN_VALUE;
                              }

                              public void beforeCommit(boolean readOnly) {
                                owner[0].queryContext().restrictDeadline(System.nanoTime() - 1);
                              }
                            })),
            System.nanoTime() + 60_000_000_000L);
    owner[0] = gate;
    before = facts();
    reject("SESSION_QUERY_TIMEOUT", () -> revoke(gate));
    require(
        hits.get() == 1 && before.equals(facts()),
        "last beforeCommit deadline rolls back revoke and accounting");
    System.out.println("RESULT original scope final beforeCommit deadline retained");
    reset();
    capture(queued);
    require(
        "QUEUED".equals(original.get("task").getFirst().get("state")),
        "actual accepted queued task without worker");
    for (String state : List.of("QUEUED", "RUNNING", "WAITING_USER")) {
      reset();
      db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", state, task);
      var measured = current();
      revoke(measured);
      revoked();
      require(
          scalar(
                  "SELECT COUNT(*) FROM task WHERE tenant_id='tenant-a' AND id=? AND state=? AND cancel_requested_at IS NOT NULL",
                  task,
                  state.equals("QUEUED") ? "CANCELLED" : "CANCEL_REQUESTED")
              == 1,
          "active cancellation keeps original state rule " + state);
      require(
          scalar(
                  "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id='cancel'",
                  task)
              == 1,
          "one cancellation event");
      if (state.equals("QUEUED")) {
        require(
            scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id='terminal'",
                    task)
                == 1,
            "queued terminal event committed atomically");
        require(
            scalar("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'")
                == ((Number) budget.get("queued")).longValue() - 1,
            "queued counter released once");
      }
      String repeated = replayFacts();
      String scheduledBefore =
          json(
              db.queryForList(
                  "SELECT next_validation_at FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                  task));
      revoke(current());
      String scheduledAfter =
          json(
              db.queryForList(
                  "SELECT next_validation_at FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                  task));
      System.out.println(
          "DETAIL repeat scheduling " + state + " " + scheduledBefore + " -> " + scheduledAfter);
      require(
          repeated.equals(replayFacts()),
          "active replay identical except original invalidation scheduling");
    }
    reset();
    System.out.println("RESULT queued running waiting cancellation and ledger events atomic");
    for (String prefixSql :
        List.of("INSERT INTO event(", "UPDATE task_session_history SET metadata_bytes=")) {
      reset();
      hits = new AtomicInteger();
      var failures = hits;
      var failure = scoped(failing(prefixSql, failures, null), System.nanoTime() + 60_000_000_000L);
      before = refusalFacts();
      reject("SESSION_QUERY_UNAVAILABLE", () -> revoke(failure));
      require(
          hits.get() > 0 && before.equals(refusalFacts()),
          "cancellation and accounting fault rolls back revoke task events reservations budget");
      require(
          scalar(
                  "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND grant_state='ACTIVE' AND revoked_at IS NULL AND invalidated_at IS NOT NULL",
                  task)
              == 1,
          "refusal afterCompletion persists only invalidation not revoke");
      var row = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
      require(
          scalar(
                  "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                  task)
              == new JdbcSessionRecords(db).metadata(row, true),
          "independently committed invalidation metadata is exact");
      System.out.println(
          "DETAIL fault post-note "
              + prefixSql
              + " "
              + json(
                  db.queryForList(
                      "SELECT grant_state,revoked_at,invalidated_at,next_validation_at FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                      task)));
    }
    revoke(current());
    revoked();
    reset();
    System.out.println("RESULT event accounting rollback and recovery no replay");
    var first = current();
    var second = current();
    try (var blocker = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")) {
        statement.executeQuery().close();
      }
      var a = pool.submit(() -> revoke(first));
      var b = pool.submit(() -> revoke(second));
      Files.writeString(barrier.resolve("waiting"), "two actual revoke lock contenders");
      long end = System.nanoTime() + 1_500_000_000L;
      while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end) Thread.sleep(5);
      require(Files.exists(barrier.resolve("release")), "externally observed budget contention");
      blocker.rollback();
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);
    }
    revoked();
    require(
        scalar(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id='cancel'",
                task)
            == 1,
        "concurrent revoke one cancel event");
    require(
        scalar("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'")
            == ((Number) budget.get("queued")).longValue() - 1,
        "concurrent revoke one queue release");
    require(
        foreign.equals(
            json(db.queryForList("SELECT * FROM task_session_history WHERE tenant_id='tenant-b'"))),
        "nonempty foreign tenant history untouched");
    reset();
    System.out.println(
        "RESULT two actual lock contenders single cancellation and tenant isolation");
  }
}
