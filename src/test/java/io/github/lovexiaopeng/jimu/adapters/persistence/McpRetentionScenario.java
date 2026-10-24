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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 相同驱动运行准确旧/新JAR；实际MCP结果与游标、同DS事务故障和双连接竞争。 */
public final class McpRetentionScenario {
  private static Object normalize(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    if (value instanceof Map<?, ?> row) {
      var copy = new java.util.LinkedHashMap<String, Object>();
      row.forEach((key, item) -> copy.put(key.toString(), normalize(item)));
      return copy;
    }
    if (value instanceof List<?> rows)
      return rows.stream().map(McpRetentionScenario::normalize).toList();
    return value;
  }

  private static String json(Object value) {
    return Payloads.json(normalize(value));
  }

  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static String task;
  private static Path barrier;
  private static long storage, sequence, revision;
  private static List<Map<String, Object>> results, cursors, holds;

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static void result(String name) {
    System.out.println("RESULT " + name);
  }

  private static long scalar(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  private static void insert(String table, List<Map<String, Object>> rows) {
    for (var row : rows) {
      var keys = row.keySet().stream().toList();
      db.update(
          "INSERT INTO "
              + table
              + " ("
              + String.join(",", keys)
              + ") VALUES ("
              + String.join(",", keys.stream().map(k -> "?").toList())
              + ")",
          keys.stream().map(row::get).toArray());
    }
  }

  private static void reset(String state, boolean expired, boolean aged) {
    db.update("DELETE FROM mcp_cursor WHERE tenant_id='tenant-a' AND task_id=?", task);
    db.update("DELETE FROM mcp_result WHERE tenant_id='tenant-a' AND task_id=?", task);
    insert("mcp_result", results);
    insert("mcp_cursor", cursors);
    db.update(
        "UPDATE mcp_result SET expires_at=? WHERE tenant_id='tenant-a' AND task_id=?",
        expired ? "2020-01-01" : "2099-01-01",
        task);
    for (var hold : holds)
      db.update(
          "UPDATE reservation SET state=?,amount=?,actual=? WHERE tenant_id='tenant-a' AND id=?",
          hold.get("state"),
          hold.get("amount"),
          hold.get("actual"),
          hold.get("id"));
    db.update(
        "DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='mcp.result_deleted'",
        task);
    db.update(
        "UPDATE task SET state=?,updated_at=?,next_event_seq=?,revision=? WHERE tenant_id='tenant-a' AND id=?",
        state,
        aged ? "2020-01-01" : "2099-01-01",
        sequence,
        revision,
        task);
    db.update("UPDATE tenant_budget SET storage_held=? WHERE tenant_id='tenant-a'", storage);
  }

  private static String facts() {
    return json(
        List.of(
            db.queryForList(
                "SELECT * FROM mcp_result WHERE tenant_id='tenant-a' AND task_id=? ORDER BY root_invocation_id",
                task),
            db.queryForList(
                "SELECT * FROM mcp_cursor WHERE tenant_id='tenant-a' AND task_id=? ORDER BY token_digest",
                task),
            db.queryForList(
                "SELECT * FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='MCP_RESULT' ORDER BY id",
                task),
            db.queryForList(
                "SELECT seq,fact_id,type FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                task),
            db.queryForMap(
                "SELECT next_event_seq,revision FROM task WHERE tenant_id='tenant-a' AND id=?",
                task),
            db.queryForMap("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")));
  }

  private static long charge() {
    return scalar(
        "SELECT COALESCE(SUM(COALESCE(actual,amount)),0) FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='MCP_RESULT'",
        task);
  }

  private static void reclaimed(long amount) {
    require(
        scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - amount,
        "exact atomic actual-or-reserved storage release");
    require(
        scalar("SELECT COUNT(*) FROM mcp_cursor WHERE tenant_id='tenant-a' AND task_id=?", task)
            == 0,
        "full tenant/task/root cursor collection deleted");
    require(
        scalar(
                "SELECT COUNT(*) FROM mcp_result WHERE tenant_id='tenant-a' AND task_id=? AND (body IS NOT NULL OR state<>'DELETED')",
                task)
            == 0,
        "result body NULL and DELETED");
    require(
        scalar(
                "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='MCP_RESULT' AND state<>'RELEASED'",
                task)
            == 0,
        "all MCP holds released");
    require(
        scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='mcp.result_deleted'",
                    task)
                == holds.size()
            && scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == sequence + holds.size()
            && scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == revision + holds.size(),
        "one idempotent deletion event per original root");
  }

  private static JdbcLedger scoped(long end) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(ledger, end, new QueryResources(0, 0, 0));
  }

  private static JdbcLedger failing(String prefix, AtomicInteger hits) {
    var injected =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws SQLException {
            var actual = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      try {
                        var value = method.invoke(actual, args);
                        if (method.getName().equals("prepareStatement")
                            && ((String) args[0]).startsWith(prefix)) {
                          var statement = (Statement) value;
                          return Proxy.newProxyInstance(
                              java.sql.PreparedStatement.class.getClassLoader(),
                              new Class<?>[] {java.sql.PreparedStatement.class},
                              (p, m, a) -> {
                                if (m.getName().startsWith("execute")) {
                                  hits.incrementAndGet();
                                  throw new SQLException(
                                      "private injected MCP cleanup failure", "45000");
                                }
                                try {
                                  return m.invoke(statement, a);
                                } catch (InvocationTargetException failure) {
                                  throw failure.getCause();
                                }
                              });
                        }
                        return value;
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }
        };
    return new JdbcLedger(injected, models);
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
        require(
            cause instanceof TaskFailure expected && code.equals(expected.code()),
            "exact refusal " + code);
      }
      System.out.println("FAILURE_EXPECTED " + failure);
    }
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      props.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-mcp-retention", props));
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
    models = new ModelProfiles(env);
    ledger = new JdbcLedger(source, models);
    task = args[1];
    barrier = Path.of(args[2]);
    results =
        db.queryForList(
            "SELECT * FROM mcp_result WHERE tenant_id='tenant-a' AND task_id=? ORDER BY root_invocation_id",
            task);
    cursors =
        db.queryForList(
            "SELECT * FROM mcp_cursor WHERE tenant_id='tenant-a' AND task_id=? ORDER BY token_digest",
            task);
    holds =
        db.queryForList(
            "SELECT id,state,amount,actual FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='MCP_RESULT' ORDER BY id",
            task);
    storage = scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    sequence = scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    revision = scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    require(
        results.size() == 2 && holds.size() == 2 && !cursors.isEmpty(),
        "two actual product MCP roots and cursor fixture");
    String invocation =
        json(
            db.queryForList(
                "SELECT id,state,output_snapshot,output_digest FROM invocation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                task));
    String foreign =
        json(
            List.of(
                db.queryForList(
                    "SELECT * FROM mcp_result WHERE tenant_id='tenant-b' ORDER BY task_id,root_invocation_id"),
                db.queryForList(
                    "SELECT * FROM mcp_cursor WHERE tenant_id='tenant-b' ORDER BY task_id,token_digest"),
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")));
    String proof =
        json(
            db.queryForMap(
                "SELECT fence,mcp_digest FROM task WHERE tenant_id='tenant-a' AND id=?", task));
    for (var test :
        List.of(
            List.of("SUCCEEDED", false),
            List.of("RUNNING", true),
            List.of("NEEDS_RECONCILIATION", true))) {
      reset((String) test.get(0), (boolean) test.get(1), true);
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unexpired/running/UNKNOWN retained " + test);
    }
    result("unexpired/running/UNKNOWN retained");
    reset("FAILED", true, true);
    var attempts =
        db.queryForList(
            "SELECT id,quiescent FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?", task);
    var workers =
        db.queryForList(
            "SELECT id,stopped FROM worker_process WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
            task);
    require(!attempts.isEmpty() && !workers.isEmpty(), "actual worker fixture");
    try {
      db.update(
          "UPDATE task_attempt SET quiescent=FALSE WHERE tenant_id='tenant-a' AND task_id=?", task);
      db.update(
          "UPDATE worker_process SET stopped=FALSE WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
          task);
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unobserved writer retains MCP storage");
    } finally {
      for (var a : attempts)
        db.update(
            "UPDATE task_attempt SET quiescent=? WHERE tenant_id='tenant-a' AND id=?",
            a.get("quiescent"),
            a.get("id"));
      for (var w : workers)
        db.update("UPDATE worker_process SET stopped=? WHERE id=?", w.get("stopped"), w.get("id"));
    }
    result("unobserved writer retained");
    reset("SUCCEEDED", true, true);
    db.update(
        "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id='tenant-a' AND id=?",
        holds.getFirst().get("id"));
    String before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "UNKNOWN blocks all roots");
    result("HELD_UNKNOWN blocks task");
    for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(terminal, true, true);
      long amount = charge();
      var measured = scoped(System.nanoTime() + 15_000_000_000L);
      measured.cleanupArtifacts(null);
      reclaimed(amount);
      if (terminal.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + json(measured.queryContext().resources()));
      ledger.cleanupArtifacts(null);
      reclaimed(amount);
    }
    result("three terminal states atomic purge and event once");
    reset("SUCCEEDED", true, true);
    db.update(
        "UPDATE reservation SET actual=0 WHERE tenant_id='tenant-a' AND id=?",
        holds.getFirst().get("id"));
    db.update(
        "UPDATE reservation SET state='RESERVED',actual=NULL,amount=17 WHERE tenant_id='tenant-a' AND id=?",
        holds.getLast().get("id"));
    ledger.cleanupArtifacts(null);
    reclaimed(17);
    result("actual zero and reserved null fallback");
    for (boolean aged : List.of(false, true)) {
      reset("SUCCEEDED", true, aged);
      db.update("DELETE FROM mcp_cursor WHERE tenant_id='tenant-a' AND task_id=?", task);
      db.update("DELETE FROM mcp_result WHERE tenant_id='tenant-a' AND task_id=?", task);
      long amount = charge();
      before = facts();
      ledger.cleanupArtifacts(null);
      if (aged) reclaimed(amount);
      else require(before.equals(facts()), "missing result has strict seven day task fallback");
    }
    result("missing result uses original task time fallback");
    for (String prefix :
        List.of(
            "UPDATE mcp_result SET body=NULL",
            "UPDATE reservation SET state='RELEASED'",
            "INSERT INTO event")) {
      reset("SUCCEEDED", true, true);
      long amount = charge();
      before = facts();
      var hits = new AtomicInteger();
      reject(() -> failing(prefix, hits).cleanupArtifacts(null), null);
      require(
          hits.get() == 1 && before.equals(facts()),
          "full cursor/body/account/event rollback at " + prefix);
      ledger.cleanupArtifacts(null);
      reclaimed(amount);
    }
    result("cursor purge accounting and late event failure rollback then recovery");
    reset("SUCCEEDED", true, true);
    before = facts();
    var deadline = scoped(System.nanoTime() + 15_000_000_000L);
    var stateReads = new AtomicInteger();
    try (var ignored =
        deadline
            .queryContext()
            .meter(
                () ->
                    (label, value) -> {
                      if (label.equals("state")
                          && "SUCCEEDED".equals(value)
                          && stateReads.incrementAndGet() == 2)
                        deadline.queryContext().restrictDeadline(System.nanoTime() - 1);
                    })) {
      reject(() -> deadline.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    }
    require(
        stateReads.get() == 2 && before.equals(facts()),
        "deadline after purge/accounting rolls back event sequence and all writes");
    reset("SUCCEEDED", false, true);
    var measured = scoped(System.nanoTime() + 15_000_000_000L);
    measured.cleanupArtifacts(null);
    var once = measured.queryContext().resources();
    measured.cleanupArtifacts(null);
    var twice = measured.queryContext().resources();
    require(
        once.reads() > 0
            && twice.reads() == once.reads() * 2
            && twice.bytes() == once.bytes() * 2
            && twice.operations() == once.operations() * 2,
        "physical metering counted once without cache");
    System.out.println("QUERY retained " + json(once));
    var expired = scoped(System.nanoTime() - 1);
    reject(() -> expired.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    result("explicit physical meter and precommit absolute deadline rollback");
    reset("SUCCEEDED", true, true);
    long amount = charge();
    try (var blocker = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement
            .executeQuery(
                "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")
            .close();
        var first = pool.submit(() -> new JdbcLedger(source, models).cleanupArtifacts(null));
        var second = pool.submit(() -> new JdbcLedger(source, models).cleanupArtifacts(null));
        Files.writeString(
            barrier.resolve("waiting"),
            "two actual cleanup contenders await original budget prefix");
        long end = System.nanoTime() + 12_000_000_000L;
        try {
          while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end)
            Thread.sleep(20);
          require(
              Files.exists(barrier.resolve("release")) && !first.isDone() && !second.isDone(),
              "two real connections blocked before cleanup");
        } finally {
          blocker.rollback();
        }
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    reclaimed(amount);
    result("two actual lock wait contenders release and emit once");
    require(
        proof.equals(
            json(
                db.queryForMap(
                    "SELECT fence,mcp_digest FROM task WHERE tenant_id='tenant-a' AND id=?",
                    task))),
        "task fence/MCP digest unchanged; revision advances once per deletion event");
    require(
        invocation.equals(
            json(
                db.queryForList(
                    "SELECT id,state,output_snapshot,output_digest FROM invocation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                    task))),
        "original invocation outputs unchanged");
    require(
        foreign.equals(
            json(
                List.of(
                    db.queryForList(
                        "SELECT * FROM mcp_result WHERE tenant_id='tenant-b' ORDER BY task_id,root_invocation_id"),
                    db.queryForList(
                        "SELECT * FROM mcp_cursor WHERE tenant_id='tenant-b' ORDER BY task_id,token_digest"),
                    db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")))),
        "other tenant result/cursor/budget unchanged");
    result("tenant full key fence output and event preservation");
    reset("SUCCEEDED", false, false);
  }
}
