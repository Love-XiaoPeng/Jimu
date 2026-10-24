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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一私有库驱动对照准确旧/新制品；只恢复本夹具创建的任务事实。 */
public final class TodoRetentionScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static Path barrier;
  private static String task;
  private static long storage, sequence, revision;
  private static final Map<String, List<Map<String, Object>>> original = new LinkedHashMap<>();
  private static final Map<String, Object> taskMetadata = new LinkedHashMap<>();

  private static Object normalize(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    if (value instanceof Map<?, ?> row) {
      var copy = new LinkedHashMap<String, Object>();
      row.forEach((k, v) -> copy.put(k.toString(), normalize(v)));
      return copy;
    }
    if (value instanceof List<?> rows)
      return rows.stream().map(TodoRetentionScenario::normalize).toList();
    return value;
  }

  private static String json(Object value) {
    return Payloads.json(normalize(value));
  }

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

  private static String string(String sql, Object... args) {
    return db.queryForObject(sql, String.class, args);
  }

  private static void remember(String table, String key) {
    original.put(
        table,
        db.queryForList(
            "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?", task));
  }

  private static void restore(String table) {
    String key = table.equals("task_todo_projection") ? "task_id" : "id";
    if (table.equals("invocation_path_binding")) key = "invocation_id";
    for (var row : original.get(table)) {
      String identity = key;
      var columns =
          row.keySet().stream().filter(k -> !k.equals("tenant_id") && !k.equals(identity)).toList();
      var args = new ArrayList<Object>();
      columns.forEach(k -> args.add(row.get(k)));
      args.add(row.get(key));
      db.update(
          "UPDATE "
              + table
              + " SET "
              + String.join(",", columns.stream().map(k -> k + "=?").toList())
              + " WHERE tenant_id='tenant-a' AND "
              + key
              + "=?",
          args.toArray());
    }
  }

  private static void reset(String state, boolean aged) {
    for (String table : original.keySet()) restore(table);
    db.update(
        "DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='todo.content_deleted'",
        task);
    db.update(
        "UPDATE task SET state=?,revision=?,next_event_seq=? WHERE tenant_id='tenant-a' AND id=?",
        state,
        revision,
        sequence,
        task);
    db.update(
        "UPDATE task_todo_projection SET retain_until=? WHERE tenant_id='tenant-a' AND task_id=?",
        aged ? "2020-01-01" : "2099-01-01",
        task);
    db.update("UPDATE tenant_budget SET todo_storage_held=? WHERE tenant_id='tenant-a'", storage);
    expectMetadata();
  }

  private static void retain(
      Map<String, Object> metadata, String prefix, Map<String, Object> row, String... fields) {
    for (String field : fields)
      if (row.get(field) != null) {
        String value = (String) row.get(field);
        metadata.put(
            prefix + field,
            Map.of(
                "sha256",
                Payloads.hash(value),
                "bytes",
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
      }
  }

  private static void expectMetadata() {
    taskMetadata.clear();
    retain(
        taskMetadata,
        "task:",
        db.queryForMap(
            "SELECT request_snapshot,goal,summary FROM task WHERE tenant_id='tenant-a' AND id=?",
            task),
        "request_snapshot",
        "goal",
        "summary");
    for (var row :
        db.queryForList(
            "SELECT id,request_snapshot,response_snapshot FROM step WHERE tenant_id='tenant-a' AND task_id=?",
            task))
      retain(
          taskMetadata,
          "step:" + row.get("id") + ":",
          row,
          "request_snapshot",
          "response_snapshot");
    for (var row :
        db.queryForList(
            "SELECT id,input_snapshot,output_snapshot FROM invocation WHERE tenant_id='tenant-a' AND task_id=?",
            task))
      retain(
          taskMetadata,
          "invocation:" + row.get("id") + ":",
          row,
          "input_snapshot",
          "output_snapshot");
    for (var row :
        db.queryForList(
            "SELECT id,usage_snapshot FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=?",
            task)) retain(taskMetadata, "dispatch:" + row.get("id") + ":", row, "usage_snapshot");
  }

  private static String facts() {
    var rows = new ArrayList<Object>();
    for (String table : original.keySet())
      rows.add(
          db.queryForList(
              "SELECT * FROM "
                  + table
                  + " WHERE tenant_id='tenant-a' AND "
                  + (table.equals("task") ? "id" : "task_id")
                  + "=? ORDER BY "
                  + (table.equals("task_todo_projection") ? "task_id" : "id"),
              task));
    rows.add(
        db.queryForList(
            "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq", task));
    rows.add(
        db.queryForMap(
            "SELECT todo_storage_held,storage_held,question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'"));
    return json(rows);
  }

  private static void reclaimed() {
    String tombstone = "{\"version\":1,\"state\":\"TODO_PURGED\"}";
    require(
        "PURGED"
            .equals(
                string(
                    "SELECT content_state FROM task_todo_projection WHERE tenant_id='tenant-a' AND task_id=?",
                    task)),
        "todo projection PURGED");
    require(
        scalar("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - 2097152,
        "original fixed independent refund");
    require(
        "RELEASED"
            .equals(
                string(
                    "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                    task + ":todo-storage")),
        "strict hold released once");
    require(
        scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='todo.content_deleted'",
                    task)
                == 1
            && scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == sequence + 1
            && scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == revision + 1,
        "original event and revision advance once");
    require(
        Payloads.read(
                string(
                    "SELECT retained_digests FROM task_todo_projection WHERE tenant_id='tenant-a' AND task_id=?",
                    task),
                com.fasterxml.jackson.databind.JsonNode.class)
            .equals(
                Payloads.read(
                    Payloads.json(taskMetadata), com.fasterxml.jackson.databind.JsonNode.class)),
        "exact original body metadata hashes and UTF8 byte lengths");
    for (String table : List.of("task", "step", "invocation", "model_dispatch")) {
      var fields =
          switch (table) {
            case "task" -> List.of("request_snapshot", "goal", "summary");
            case "step" -> List.of("request_snapshot", "response_snapshot");
            case "invocation" -> List.of("input_snapshot", "output_snapshot");
            default -> List.of("usage_snapshot");
          };
      for (var originalRow : original.get(table)) {
        var current =
            db.queryForMap(
                "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND id=?",
                originalRow.get("id"));
        for (String field : fields)
          require(
              java.util.Objects.equals(
                  originalRow.get(field) == null ? null : tombstone, current.get(field)),
              "original nullable tombstone " + table + "." + field);
        if (table.equals("step"))
          require(
              java.util.Objects.equals(
                      current.get("request_digest"), current.get("retained_request_digest"))
                  && "TODO_PURGED".equals(current.get("request_cleanup_state")),
              "step original retained request digest");
      }
    }
    var projection = original.get("task_todo_projection").getFirst();
    var current =
        db.queryForMap(
            "SELECT * FROM task_todo_projection WHERE tenant_id='tenant-a' AND task_id=?", task);
    for (String field :
        List.of(
            "revision",
            "source_invocation_id",
            "source_step_id",
            "committed_attempt_id",
            "committed_fence",
            "updated_seq",
            "output_digest",
            "pending_count",
            "in_progress_count",
            "completed_count",
            "binding_snapshot",
            "binding_digest"))
      require(
          java.util.Objects.equals(current.get(field), projection.get(field)),
          "original todo provenance " + field);
  }

  private static JdbcLedger configured(javax.sql.DataSource data) {
    var value = new JdbcLedger(data, models);
    value.todosEnabled(true);
    return value;
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
                                        "private todo cleanup SQL fault", "45000");
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
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      props.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new PropertiesPropertySource("private-todo-retention", props));
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
    task = args[1];
    barrier = Path.of(args[2]);
    ledger = configured(source);
    for (String table :
        List.of(
            "task", "step", "invocation", "model_dispatch", "task_todo_projection", "reservation"))
      remember(table, table.equals("task") ? "id" : "task_id");
    require(
        original.get("invocation").size() == 3 && original.get("model_dispatch").size() == 4,
        "actual three todo commits and four model dispatches");
    storage = scalar("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    sequence = scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    revision = scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    String foreign =
        json(
            List.of(
                db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM task_todo_projection WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM invocation WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")));
    for (String state : List.of("SUCCEEDED", "RUNNING", "WAITING_USER", "NEEDS_RECONCILIATION")) {
      reset(state, !state.equals("SUCCEEDED"));
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unexpired or noncandidate terminal retained " + state);
    }
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE task_todo_projection SET content_state='PURGED' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    String before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "already purged retained");
    result("candidate retain time terminal and already purged boundaries");
    for (String condition :
        List.of(
            "state='HELD_UNKNOWN'",
            "usage_quality='UNKNOWN'",
            "state='RESERVED',category='TOOL'")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET " + condition + " WHERE tenant_id='tenant-a' AND id=?",
          task + ":todo-storage");
      before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unsafe reservation retained " + condition);
    }
    for (String state : List.of("RUNNING", "OUTCOME_UNKNOWN")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE invocation SET todo_applied_revision=NULL,todo_committed_attempt_id=NULL,todo_committed_fence=NULL,todo_event_seq=NULL,todo_committed_at=NULL,state=? WHERE tenant_id='tenant-a' AND task_id=?",
          state,
          task);
      before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "active invocation retained " + state);
    }
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE model_dispatch SET state='DISPATCHED' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "dispatched model retained");
    result("unknown reserved active invocation and dispatched model retained");
    reset("SUCCEEDED", true);
    var attempts =
        db.queryForList(
            "SELECT * FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?", task);
    var workers =
        db.queryForList(
            "SELECT id,stopped FROM worker_process WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
            task);
    require(!attempts.isEmpty() && !workers.isEmpty(), "actual writer receipts");
    db.update(
        "UPDATE task_attempt SET quiescent=FALSE WHERE tenant_id='tenant-a' AND task_id=?", task);
    for (var worker : workers)
      db.update("UPDATE worker_process SET stopped=FALSE WHERE id=?", worker.get("id"));
    before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "unobserved writer retained");
    for (var attempt : attempts)
      db.update(
          "UPDATE task_attempt SET quiescent=? WHERE tenant_id='tenant-a' AND id=?",
          attempt.get("quiescent"),
          attempt.get("id"));
    for (var worker : workers)
      db.update(
          "UPDATE worker_process SET stopped=? WHERE id=?",
          worker.get("stopped"),
          worker.get("id"));
    result("actual writer receipt required before cleanup");
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(state, true);
      var measured = scoped(System.nanoTime() + 30_000_000_000L);
      measured.cleanupArtifacts(null);
      reclaimed();
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + json(measured.queryContext().resources()));
      String after = facts();
      ledger.cleanupArtifacts(null);
      require(after.equals(facts()), "idempotent second cleanup");
    }
    result("three terminal states exact metadata provenance nullable bodies and refund");
    for (String state : List.of("RELEASED", "SETTLED")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET state=? WHERE tenant_id='tenant-a' AND id=?",
          state,
          task + ":todo-storage");
      before = facts();
      reject(() -> ledger.cleanupArtifacts(null), "RESERVATION_STATE");
      require(
          before.equals(facts()),
          "zero row hold refusal rolls back earlier event and every body " + state);
    }
    result("strict zero hold update rolls back body metadata event and refund");
    for (String mutation :
        List.of(
            "binding_digest=REPEAT('0',64)",
            "binding_snapshot='{}',binding_digest=SHA2('{}',256)",
            "binding_snapshot='{\"version\":1,\"todoVersion\":1}',binding_digest=SHA2('{\"version\":1,\"todoVersion\":1}',256)")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE task_todo_projection SET "
              + mutation
              + " WHERE tenant_id='tenant-a' AND task_id=?",
          task);
      before = facts();
      reject(
          () -> ledger.cleanupArtifacts(null),
          mutation.contains("'{}'") ? "TODO_BINDING_UNSUPPORTED" : "SNAPSHOT_CORRUPT");
      require(before.equals(facts()), "binding refusal before mutations");
    }
    result("binding digest parsed version and exact snapshot refusals");
    for (String boundary : List.of("retain_until='2099-01-01'", "content_state='PURGED'")) {
      reset("SUCCEEDED", true);
      var hits = new AtomicInteger();
      failing(
              "SELECT p.tenant_id,p.task_id FROM task_todo_projection p",
              hits,
              () ->
                  db.update(
                      "UPDATE task_todo_projection SET "
                          + boundary
                          + " WHERE tenant_id='tenant-a' AND task_id=?",
                      task))
          .cleanupArtifacts(null);
      require(
          hits.get() == 1
              && scalar("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                  == storage
              && scalar(
                      "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='todo.content_deleted'",
                      task)
                  == 0,
          "lock-time recheck after actual candidate scan " + boundary);
    }
    result("retain time and state rechecked under product task lock");
    for (String prefix :
        List.of(
            "UPDATE model_dispatch SET usage_snapshot=",
            "UPDATE task SET request_snapshot=",
            "UPDATE task_todo_projection SET content_state='PURGED'",
            "INSERT INTO event(",
            "UPDATE reservation SET state='RELEASED'",
            "UPDATE tenant_budget SET todo_storage_held=todo_storage_held-2097152")) {
      reset("SUCCEEDED", true);
      before = facts();
      var hits = new AtomicInteger();
      reject(() -> failing(prefix, hits, null).cleanupArtifacts(null), null);
      require(
          hits.get() == 1 && before.equals(facts()),
          "same DS whole transaction rollback " + prefix);
      ledger.cleanupArtifacts(null);
      reclaimed();
    }
    result("six actual SQL fault sites roll back body event and accounting then recover");
    reset("SUCCEEDED", true);
    before = facts();
    var owner = new JdbcLedger[1];
    var hits = new AtomicInteger();
    var afterEvent =
        scoped(
            failing(
                "INSERT INTO event(",
                hits,
                () -> owner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 30_000_000_000L);
    owner[0] = afterEvent;
    reject(() -> afterEvent.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    require(
        hits.get() == 1 && before.equals(facts()),
        "after event before hold query deadline rolls back every change");
    ledger.cleanupArtifacts(null);
    reclaimed();
    reset("SUCCEEDED", true);
    var finalOwner = new JdbcLedger[1];
    var finalHits = new AtomicInteger();
    var afterRefund =
        scoped(
            failing(
                "UPDATE tenant_budget SET todo_storage_held=todo_storage_held-2097152",
                finalHits,
                () -> finalOwner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 30_000_000_000L);
    finalOwner[0] = afterRefund;
    reject(() -> afterRefund.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    require(finalHits.get() == 1, "deadline after actual final refund SQL");
    reclaimed();
    String committed = facts();
    ledger.cleanupArtifacts(null);
    require(committed.equals(facts()), "original post-final-SQL commit idempotent recovery");
    System.out.println(
        "LIMITATION original core UoW missing final beforeCommit query deadline check remains OPEN; final refund commits before next-query timeout");
    reset("SUCCEEDED", false);
    var measured = scoped(System.nanoTime() + 30_000_000_000L);
    measured.cleanupArtifacts(null);
    var once = measured.queryContext().resources();
    measured.cleanupArtifacts(null);
    var twice = measured.queryContext().resources();
    require(
        once.reads() > 0
            && twice.reads() == once.reads() * 2
            && twice.bytes() == once.bytes() * 2
            && twice.operations() == once.operations() * 2,
        "explicit physical meter no cache");
    System.out.println("QUERY retained " + json(once));
    reject(
        () -> {
          try {
            scoped(System.nanoTime() - 1).cleanupArtifacts(null);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        "SESSION_QUERY_TIMEOUT");
    result("actual meter absolute deadline rollback and original final commit gap");
    reset("SUCCEEDED", true);
    try (var blocker = source.getConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement
            .executeQuery(
                "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")
            .close();
        var first = pool.submit(() -> configured(source).cleanupArtifacts(null));
        var second = pool.submit(() -> configured(source).cleanupArtifacts(null));
        Files.writeString(
            barrier.resolve("waiting"), "two independent product cleanup connections await budget");
        long end = System.nanoTime() + 12_000_000_000L;
        try {
          while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end)
            Thread.sleep(20);
          require(
              Files.exists(barrier.resolve("release")) && !first.isDone() && !second.isDone(),
              "two actual budget contenders blocked");
        } finally {
          blocker.rollback();
        }
        first.get(25, TimeUnit.SECONDS);
        second.get(25, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    reclaimed();
    result("two actual product lock wait contenders refund and emit once");
    require(
        foreign.equals(
            json(
                List.of(
                    db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-b'"),
                    db.queryForList(
                        "SELECT * FROM task_todo_projection WHERE tenant_id='tenant-b'"),
                    db.queryForList("SELECT * FROM invocation WHERE tenant_id='tenant-b'"),
                    db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")))),
        "other tenant full key facts unchanged");
    for (var invocation : original.get("invocation")) {
      var row =
          db.queryForMap(
              "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND id=?", invocation.get("id"));
      for (String field :
          List.of(
              "output_digest",
              "todo_expected_revision",
              "todo_applied_revision",
              "todo_committed_attempt_id",
              "todo_committed_fence",
              "todo_event_seq",
              "todo_committed_at"))
        require(
            java.util.Objects.equals(row.get(field), invocation.get(field)),
            "invocation effect provenance preserved " + field);
    }
    require(
        scalar("SELECT fence FROM task WHERE tenant_id='tenant-a' AND id=?", task)
            == ((Number) original.get("task").getFirst().get("fence")).longValue(),
        "task fence unchanged");
    result("tenant full keys and committed effect provenance preserved");
    reset("SUCCEEDED", false);
  }
}
