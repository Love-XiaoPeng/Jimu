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
public final class ScopeMaintenanceScenario {
  private static DriverManagerDataSource source;
  private static JdbcTemplate db;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static String task;
  private static Path barrier;
  private static long storage;
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
    if (v instanceof List<?> l) return l.stream().map(ScopeMaintenanceScenario::normalize).toList();
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

  private static void reset(String state, boolean aged) {
    db.update(
        "DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='session_history.content_deleted'",
        task);
    for (var t : tables) {
      var keys =
          db.queryForList(
              "SELECT COLUMN_NAME FROM information_schema.key_column_usage WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION",
              String.class,
              t);
      for (var row : original.get(t)) {
        var cols = row.keySet().stream().filter(c -> !keys.contains(c)).toList();
        var values = new ArrayList<Object>();
        cols.forEach(c -> values.add(row.get(c)));
        keys.forEach(c -> values.add(row.get(c)));
        db.update(
            "UPDATE "
                + t
                + " SET "
                + String.join(",", cols.stream().map(c -> c + "=?").toList())
                + " WHERE "
                + String.join(" AND ", keys.stream().map(c -> c + "=?").toList()),
            values.toArray());
      }
    }
    db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", state, task);
    db.update(
        "UPDATE task_session_scope SET next_validation_at='2020-01-01' WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    db.update(
        "UPDATE tenant_budget SET history_storage_held=? WHERE tenant_id='tenant-a'", storage);
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
                                  if (hook == null)
                                    throw new SQLException(
                                        "private scope maintenance SQL fault", "45000");
                                  try {
                                    var result = method.invoke(statement, args);
                                    hook.run();
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

  private static void validate(JdbcLedger owner) {
    try {
      var m = JdbcLedger.class.getDeclaredMethod("validateSessionScopes");
      m.setAccessible(true);
      m.invoke(owner);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException f) throw f;
      throw new IllegalStateException(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void purged() {
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PURGED' AND body_bytes=0",
                task)
            == 1,
        "body purged");
    var row = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    long bytes = new JdbcSessionRecords(db).metadata(row, true);
    require(
        scalar(
                "SELECT held_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                task)
            == bytes,
        "fixed point metadata held");
    require(
        scalar(
                "SELECT actual FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                task + ":session-history")
            == bytes,
        "atomic reservation settled");
    require(
        scalar("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - 8388608 + bytes,
        "one exact budget refund");
    require(
        scalar(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='session_history.content_deleted'",
                task)
            == 1,
        "one deletion event");
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
    task = args[1];
    barrier = Path.of(args[2]);
    ledger = configured(source);
    for (var t : tables)
      original.put(
          t,
          db.queryForList(
              "SELECT * FROM " + t + " WHERE tenant_id='tenant-a' AND " + key(t) + "=?", task));
    storage = scalar("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    String foreign =
        json(db.queryForList("SELECT * FROM task_session_history WHERE tenant_id='tenant-b'"));

    String parent = args[3];
    db.update(
        "UPDATE task_session_scope SET next_validation_at='2099-01-01' WHERE caller_task_id<>?",
        task);
    reset("SUCCEEDED", false);
    var measured = scoped(System.nanoTime() + 60_000_000_000L);
    validate(measured);
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND invalidated_at IS NULL AND next_validation_at>CURRENT_TIMESTAMP(6)",
                task)
            == 1,
        "valid authority scheduled in first committed transaction");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT'",
                task)
            == 1,
        "valid whole-scope retains body");
    System.out.println("QUERY valid " + json(measured.queryContext().resources()));
    System.out.println("RESULT valid nonempty whole scope schedules and accounts");
    for (String state : List.of("RUNNING", "WAITING_USER", "NEEDS_RECONCILIATION")) {
      reset(state, false);
      db.update(
          "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
          task);
      validate(scoped(System.nanoTime() + 60_000_000_000L));
      require(
          scalar(
                  "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT'",
                  task)
              == 1,
          "nonterminal retains " + state);
    }
    System.out.println("RESULT nonterminal invalidated retains bodies");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope SET grant_state='REVOKED',revoked_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    validate(scoped(System.nanoTime() + 60_000_000_000L));
    purged();
    String once = facts();
    validate(scoped(System.nanoTime() + 60_000_000_000L));
    require(once.equals(facts()), "purged scope is idempotent");
    System.out.println("RESULT revoked terminal one atomic purge settlement and repeat");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope_target SET target_digest=REPEAT('0',64) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    validate(scoped(System.nanoTime() + 60_000_000_000L));
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND invalidated_at IS NULL AND next_validation_at>CURRENT_TIMESTAMP(6)",
                task)
            == 1,
        "corruption defers but original scheduling remains committed");
    System.out.println("RESULT corruption not authority loss and scheduling survives refusal");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    db.update(
        "UPDATE reservation SET usage_quality='UNKNOWN' WHERE tenant_id='tenant-a' AND id=?",
        task + ":session-history");
    validate(scoped(System.nanoTime() + 60_000_000_000L));
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT' AND held_bytes=8388608",
                task)
            == 1,
        "unknown reservation does not release body or hold");
    System.out.println("RESULT unknown keeps original body and hold");
    reset("SUCCEEDED", false);
    String prior =
        db.queryForObject(
            "SELECT state FROM task WHERE tenant_id='tenant-a' AND id=?", String.class, parent);
    try {
      db.update("UPDATE task SET state='RUNNING' WHERE tenant_id='tenant-a' AND id=?", parent);
      validate(scoped(System.nanoTime() + 60_000_000_000L));
      purged();
    } finally {
      db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", prior, parent);
    }
    System.out.println(
        "RESULT source current ineligibility invalidates whole scope and purges caller");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    var hits = new AtomicInteger();
    validate(
        scoped(failing("INSERT INTO event(", hits, null), System.nanoTime() + 60_000_000_000L));
    require(hits.get() == 1, "actual event SQL failure injected");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT'",
                task)
            == 1,
        "event failure rolls back body purge");
    require(
        scalar("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage,
        "event failure rolls back refund");
    db.update(
        "UPDATE task_session_scope SET next_validation_at='2020-01-01' WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    validate(scoped(System.nanoTime() + 60_000_000_000L));
    purged();
    System.out.println("RESULT actual event failure rollback and recovery no replay");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    var owner = new JdbcLedger[1];
    hits = new AtomicInteger();
    var timed =
        scoped(
            failing(
                "UPDATE tenant_budget SET history_storage_held=history_storage_held-",
                hits,
                () -> {
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .registerSynchronization(
                          new org.springframework.transaction.support.TransactionSynchronization() {
                            public int getOrder() {
                              return Integer.MIN_VALUE;
                            }

                            public void beforeCommit(boolean readOnly) {
                              owner[0].queryContext().restrictDeadline(System.nanoTime() - 1);
                            }
                          });
                }),
            System.nanoTime() + 60_000_000_000L);
    owner[0] = timed;
    validate(timed);
    require(
        hits.get() == 1,
        "last refund SQL completed before transaction synchronization deadline hook");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=? AND content_state='PRESENT'",
                task)
            == 1,
        "scope final beforeCommit timeout rolls back body");
    require(
        scalar("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage,
        "scope final beforeCommit timeout rolls back refund");
    System.out.println(
        "RESULT scope final beforeCommit gate preserved independently from core gap");
    reset("SUCCEEDED", false);
    db.update(
        "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND caller_task_id=?",
        task);
    var first = scoped(System.nanoTime() + 60_000_000_000L);
    var second = scoped(System.nanoTime() + 60_000_000_000L);
    try (var blocker = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")) {
        statement.executeQuery().close();
      }
      var a = pool.submit(() -> validate(first));
      var b = pool.submit(() -> validate(second));
      Files.writeString(barrier.resolve("waiting"), "two actual scope validation contenders");
      long end = System.nanoTime() + 1_500_000_000L;
      while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end) Thread.sleep(5);
      require(Files.exists(barrier.resolve("release")), "bounded external scope lock observation");
      blocker.rollback();
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);
    }
    purged();
    require(
        foreign.equals(
            json(db.queryForList("SELECT * FROM task_session_history WHERE tenant_id='tenant-b'"))),
        "foreign tenant history untouched");
    System.out.println("RESULT two observed lock wait contenders one settlement tenant isolation");
    reset("SUCCEEDED", false);
  }
}
