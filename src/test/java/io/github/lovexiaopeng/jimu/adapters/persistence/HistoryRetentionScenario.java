package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
import java.util.Set;
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
public final class HistoryRetentionScenario {
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
  private static final Set<String> bodies =
      Set.of(
          "request_snapshot",
          "goal",
          "summary",
          "response_snapshot",
          "input_snapshot",
          "output_snapshot",
          "usage_snapshot",
          "event_snapshot",
          "normalized_query_snapshot",
          "read_sources_snapshot",
          "authority_snapshot",
          "target_snapshot",
          "source_task_id",
          "context_snapshot",
          "normalized_snapshot",
          "read_refs_snapshot");

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
    if (v instanceof List<?> l) return l.stream().map(HistoryRetentionScenario::normalize).toList();
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
        "UPDATE task_session_history SET retain_until=? WHERE tenant_id='tenant-a' AND task_id=?",
        aged ? "2020-01-01" : "2099-01-01",
        task);
    db.update(
        "UPDATE tenant_budget SET history_storage_held=? WHERE tenant_id='tenant-a'", storage);
  }

  private static void cleanup(JdbcLedger owner) {
    try {
      var m = JdbcLedger.class.getDeclaredMethod("cleanupSessionHistory");
      m.setAccessible(true);
      m.invoke(owner);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException f) throw f;
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JdbcLedger configured(javax.sql.DataSource data) {
    return new JdbcLedger(data, models);
  }

  private static void reclaimed() {
    var h =
        db.queryForMap(
            "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", task);
    require(
        "PURGED".equals(h.get("content_state")) && ((Number) h.get("body_bytes")).longValue() == 0,
        "history content purged");
    var taskRow = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    long metadata = new JdbcSessionRecords(db).metadata(taskRow, true);
    long held = ((Number) h.get("held_bytes")).longValue();
    require(
        metadata == held && metadata == ((Number) h.get("metadata_bytes")).longValue(),
        "all actual stored scalar metadata equals fixed point");
    require(
        scalar(
                "SELECT actual FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                task + ":session-history")
            == metadata,
        "reservation actual exact metadata");
    require(
        scalar("SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - 8388608 + metadata,
        "one exact fixed point storage release");
    require(
        scalar(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='session_history.content_deleted'",
                task)
            == 1,
        "one deletion event");
    var receipt =
        Payloads.read(
            (String) h.get("cleanup_receipt"), com.fasterxml.jackson.databind.JsonNode.class);
    for (String t :
        List.of(
            "task",
            "step",
            "invocation",
            "model_dispatch",
            "session_public_event",
            "invocation_session_search",
            "invocation_session_read",
            "invocation_session_trace",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_scope_query")) {
      var retained = receipt.path(t);
      require(retained.size() == original.get(t).size(), "all original receipt rows " + t);
      for (int i = 0; i < retained.size(); i++)
        for (var field : bodies) {
          var raw = original.get(t).get(i).get(field);
          if (raw != null) {
            var f = retained.get(i).path(field);
            require(
                f.path("sha256").asText().equals(Payloads.hash(raw.toString()))
                    && f.path("bytes").asLong()
                        == raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "retained original hash and UTF8 bytes " + t + "." + field);
          }
        }
    }
    require(
        scalar(
                "SELECT COUNT(*) FROM step WHERE tenant_id='tenant-a' AND task_id=? AND (request_cleanup_state<>'SESSION_PURGED' OR request_snapshot<>'' OR response_snapshot IS NOT NULL OR retained_request_digest<>request_digest)",
                task)
            == 0,
        "step body NULL and digest preserved");
    require(
        scalar(
                "SELECT COUNT(*) FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND (input_snapshot<>'' OR output_snapshot IS NOT NULL)",
                task)
            == 0,
        "invocation body NULL preserved");
    for (var t :
        List.of(
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_scope_query",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_trace"))
      require(
          scalar(
                  "SELECT COUNT(*) FROM "
                      + t
                      + " WHERE tenant_id='tenant-a' AND "
                      + key(t)
                      + "=? AND content_state<>'PURGED'",
                  task)
              == 0,
          "all existing scope/read bodies purged " + t);
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
                                        "private history cleanup SQL fault", "45000");
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

  private static void reject(Runnable work, String code) {
    try {
      work.run();
      throw new AssertionError("failure accepted");
    } catch (RuntimeException failure) {
      if (code != null) {
        Throwable cause = failure;
        while (!(cause instanceof TaskFailure) && cause.getCause() != null)
          cause = cause.getCause();
        require(cause instanceof TaskFailure f && code.equals(f.code()), "exact refusal " + code);
      }
      System.out.println("FAILURE_EXPECTED " + failure);
    }
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
    for (String state : List.of("SUCCEEDED", "RUNNING", "WAITING_USER", "NEEDS_RECONCILIATION")) {
      reset(state, !state.equals("SUCCEEDED"));
      String before = facts();
      cleanup(ledger);
      require(before.equals(facts()), "retained candidate boundary " + state);
    }
    System.out.println("RESULT candidate retention and terminal boundaries");
    for (String condition :
        List.of(
            "state='HELD_UNKNOWN'",
            "usage_quality='UNKNOWN'",
            "state='RESERVED',category='TOOL'")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET " + condition + " WHERE tenant_id='tenant-a' AND id=?",
          task + ":session-history");
      String before = facts();
      cleanup(ledger);
      require(before.equals(facts()), "unsafe reservation retains " + condition);
    }
    for (String state : List.of("DISPATCHED", "USAGE_UNKNOWN", "BOUND_EXCEEDED")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE model_dispatch SET state=? WHERE tenant_id='tenant-a' AND task_id=?",
          state,
          task);
      String before = facts();
      cleanup(ledger);
      require(before.equals(facts()), "unsafe model retains " + state);
    }
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE task_attempt SET quiescent=FALSE WHERE tenant_id='tenant-a' AND task_id=?", task);
    String before = facts();
    cleanup(ledger);
    require(before.equals(facts()), "candidate unquiet writer retains");
    System.out.println("RESULT unknown model reservation and writer guards");
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(state, true);
      var measured = scoped(System.nanoTime() + 60_000_000_000L);
      cleanup(measured);
      reclaimed();
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + json(measured.queryContext().resources()));
      String after = facts();
      cleanup(ledger);
      require(after.equals(facts()), "idempotent repeat");
    }
    System.out.println(
        "RESULT terminal cleanup exact body receipt metadata fixed point refund and NULL");
    for (String mutation : List.of("binding_digest=REPEAT('0',64)", "binding_snapshot='{}'")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE task_session_history SET "
              + mutation
              + " WHERE tenant_id='tenant-a' AND task_id=?",
          task);
      before = facts();
      reject(() -> cleanup(ledger), "SESSION_HISTORY_VERSION_UNSUPPORTED");
      require(before.equals(facts()), "binding rejection preserves all facts");
    }
    System.out.println("RESULT exact binding refusals");
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE task SET model_snapshot=REPEAT('x',65000) WHERE tenant_id='tenant-a' AND id=?",
        task);
    before = facts();
    reject(() -> cleanup(ledger), "SESSION_HISTORY_LIMIT");
    require(
        before.equals(facts()), "metadata overflow rejects after body writes with full rollback");
    System.out.println("RESULT metadata quota rollback");
    reset("SUCCEEDED", true);
    var hits = new AtomicInteger();
    cleanup(
        failing(
            "SELECT p.tenant_id,p.task_id FROM task_session_history p",
            hits,
            () ->
                db.update(
                    "UPDATE task_session_history SET retain_until='2099-01-01' WHERE tenant_id='tenant-a' AND task_id=?",
                    task)));
    require(
        hits.get() == 1
            && scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='session_history.content_deleted'",
                    task)
                == 0,
        "locked retention recheck after actual candidate scan");
    System.out.println("RESULT candidate to lock retention recheck");
    for (String prefix :
        List.of(
            "UPDATE session_public_event SET event_snapshot=",
            "UPDATE invocation SET input_snapshot=",
            "UPDATE task_session_history SET content_state='PURGED'",
            "INSERT INTO event(",
            "UPDATE task_session_history SET metadata_bytes=",
            "UPDATE reservation SET state='SETTLED'",
            "UPDATE tenant_budget SET history_storage_held=history_storage_held-")) {
      reset("SUCCEEDED", true);
      before = facts();
      hits = new AtomicInteger();
      var current = hits;
      reject(() -> cleanup(failing(prefix, current, null)), null);
      require(hits.get() == 1 && before.equals(facts()), "whole transaction rollback " + prefix);
      cleanup(ledger);
      reclaimed();
    }
    System.out.println("RESULT seven actual SQL failure rollback and recovery sites");
    reset("SUCCEEDED", true);
    before = facts();
    var owner = new JdbcLedger[1];
    hits = new AtomicInteger();
    var timed =
        scoped(
            failing(
                "INSERT INTO event(",
                hits,
                () -> owner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 60_000_000_000L);
    owner[0] = timed;
    reject(() -> cleanup(timed), "SESSION_QUERY_TIMEOUT");
    require(
        hits.get() == 1 && before.equals(facts()),
        "after event before metadata deadline rolls back");
    reset("SUCCEEDED", true);
    hits = new AtomicInteger();
    var last =
        scoped(
            failing(
                "UPDATE tenant_budget SET history_storage_held=history_storage_held-",
                hits,
                () -> owner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 60_000_000_000L);
    owner[0] = last;
    cleanup(last);
    reclaimed();
    reject(() -> last.queryContext().remainingMillis(), "SESSION_QUERY_TIMEOUT");
    String committed = facts();
    cleanup(ledger);
    require(
        committed.equals(facts()),
        "last SQL deadline original committed boundary and idempotent recovery");
    System.out.println("RESULT original core beforeCommit deadline gap remains OPEN");
    reset("SUCCEEDED", true);
    try (var blocker = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement(
              "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")) {
        statement.executeQuery().close();
      }
      var a = pool.submit(() -> cleanup(configured(source)));
      var b = pool.submit(() -> cleanup(configured(source)));
      Files.writeString(
          barrier.resolve("waiting"), "two independent history cleanup ledgers waiting budget");
      long end = System.nanoTime() + 15_000_000_000L;
      while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end) Thread.sleep(10);
      require(
          Files.exists(barrier.resolve("release")), "bounded external lock observation barrier");
      blocker.rollback();
      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
    }
    reclaimed();
    require(
        foreign.equals(
            json(db.queryForList("SELECT * FROM task_session_history WHERE tenant_id='tenant-b'"))),
        "foreign tenant facts unchanged");
    System.out.println(
        "RESULT two actual lock wait contenders single settlement and tenant isolation");
    reset("SUCCEEDED", false);
    if (!original.get("task_session_scope").isEmpty()) {
      db.update(
          "UPDATE task_session_scope SET invalidated_at=CURRENT_TIMESTAMP(6),next_validation_at='2020-01-01' WHERE tenant_id='tenant-a' AND caller_task_id=?",
          task);
      var method = JdbcLedger.class.getDeclaredMethod("validateSessionScopes");
      method.setAccessible(true);
      method.invoke(ledger);
      reclaimed();
      System.out.println("RESULT shared invalidated scope maintenance purge and fixed point");
      reset("SUCCEEDED", false);
    }
  }
}
