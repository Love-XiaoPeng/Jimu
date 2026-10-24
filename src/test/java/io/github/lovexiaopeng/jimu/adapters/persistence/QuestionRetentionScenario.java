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
public final class QuestionRetentionScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static Path barrier;
  private static String task;
  private static long storage, sequence, revision, capacity;
  private static final Map<String, List<Map<String, Object>>> original = new LinkedHashMap<>();
  private static final Map<String, Object> taskMetadata = new LinkedHashMap<>();
  private static final Map<String, Map<String, Object>> questionMetadata = new LinkedHashMap<>();

  private static Object normalize(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    if (value instanceof Map<?, ?> row) {
      var copy = new LinkedHashMap<String, Object>();
      row.forEach((k, v) -> copy.put(k.toString(), normalize(v)));
      return copy;
    }
    if (value instanceof List<?> rows)
      return rows.stream().map(QuestionRetentionScenario::normalize).toList();
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
    String key = table.equals("task_directory") ? "task_id" : "id";
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
        "DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='question.content_deleted'",
        task);
    db.update(
        "UPDATE task SET state=?,created_at=?,revision=?,next_event_seq=? WHERE tenant_id='tenant-a' AND id=?",
        state,
        aged ? "2020-01-01" : "2099-01-01",
        revision,
        sequence,
        task);
    db.update("UPDATE tenant_budget SET storage_held=? WHERE tenant_id='tenant-a'", storage);
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
    questionMetadata.clear();
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
            "SELECT id,request_snapshot,answer_snapshot,receipt_snapshot FROM user_question WHERE tenant_id='tenant-a' AND task_id=?",
            task)) {
      var metadata = new LinkedHashMap<String, Object>();
      retain(metadata, "", row, "request_snapshot", "answer_snapshot", "receipt_snapshot");
      questionMetadata.put((String) row.get("id"), metadata);
    }
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
                  + "=? ORDER BY id",
              task));
    rows.add(
        db.queryForList(
            "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq", task));
    rows.add(
        db.queryForMap(
            "SELECT storage_held,question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'"));
    return json(rows);
  }

  private static void metadataEquals(String value, Map<String, Object> expected) {
    require(
        Payloads.read(value, com.fasterxml.jackson.databind.JsonNode.class)
            .equals(
                Payloads.read(
                    Payloads.json(expected), com.fasterxml.jackson.databind.JsonNode.class)),
        "exact retained body hashes and UTF8 lengths");
  }

  private static void reclaimed(long released) {
    String tombstone = "{\"version\":1,\"state\":\"QUESTION_PURGED\"}";
    require(
        "PURGED"
            .equals(
                string(
                    "SELECT question_cleanup_state FROM task WHERE tenant_id='tenant-a' AND id=?",
                    task)),
        "task content PURGED");
    require(
        scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - released,
        "one conditional original fixed storage release");
    require(
        scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='question.content_deleted'",
                    task)
                == 1
            && scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == sequence + 1
            && scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == revision + 1,
        "one atomic original event and revision");
    require(
        tombstone.equals(
                string(
                    "SELECT request_snapshot FROM task WHERE tenant_id='tenant-a' AND id=?", task))
            && tombstone.equals(
                string("SELECT goal FROM task WHERE tenant_id='tenant-a' AND id=?", task)),
        "exact task tombstone");
    metadataEquals(
        string(
            "SELECT question_retained_digests FROM task WHERE tenant_id='tenant-a' AND id=?", task),
        taskMetadata);
    for (var q :
        db.queryForList(
            "SELECT * FROM user_question WHERE tenant_id='tenant-a' AND task_id=?", task)) {
      String id = (String) q.get("id");
      metadataEquals((String) q.get("retained_digests"), questionMetadata.get(id));
      require(tombstone.equals(q.get("request_snapshot")), "question request tombstone");
      for (String body : List.of("answer_snapshot", "receipt_snapshot"))
        require(
            questionMetadata.get(id).containsKey(body)
                ? tombstone.equals(q.get(body))
                : q.get(body) == null,
            "question nullable body preserved " + body);
    }
    require(
        scalar(
                "SELECT COUNT(*) FROM step WHERE tenant_id='tenant-a' AND task_id=? AND (request_cleanup_state<>'QUESTION_PURGED' OR request_snapshot<>? OR (response_snapshot IS NOT NULL AND response_snapshot<>?) OR retained_request_digest<>request_digest)",
                task,
                tombstone,
                tombstone)
            == 0,
        "step tombstones and retained request digest");
    require(
        scalar(
                "SELECT COUNT(*) FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND (input_snapshot<>? OR (output_snapshot IS NOT NULL AND output_snapshot<>?))",
                task,
                tombstone,
                tombstone)
            == 0,
        "invocation nullable tombstones");
  }

  private static JdbcLedger configured(javax.sql.DataSource data) {
    var value = new JdbcLedger(data, models);
    value.questionsEnabled(true);
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
                                        "private directory cleanup SQL fault", "45000");
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
        .addFirst(new PropertiesPropertySource("private-question-retention", props));
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
    for (String table : List.of("task", "step", "invocation", "user_question", "reservation"))
      remember(table, table.equals("task") ? "id" : "task_id");
    require(
        original.get("user_question").size() == 2,
        "two actual accepted question answers and receipts");
    storage = scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    capacity = scalar("SELECT question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    sequence = scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    revision = scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    String foreign =
        json(
            List.of(
                db.queryForList("SELECT * FROM user_question WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")));
    String dispatches =
        json(
            db.queryForList(
                "SELECT * FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                task));
    for (String state : List.of("SUCCEEDED", "RUNNING", "WAITING_USER", "NEEDS_RECONCILIATION")) {
      reset(state, !state.equals("SUCCEEDED"));
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unexpired or noncandidate state retained " + state);
    }
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE task SET question_cleanup_state='PURGED' WHERE tenant_id='tenant-a' AND id=?",
        task);
    String before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "already purged retained");
    result("candidate age terminal and already purged boundaries");
    for (String change : List.of("state='HELD_UNKNOWN'", "usage_quality='UNKNOWN'")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET " + change + " WHERE tenant_id='tenant-a' AND id=?",
          task + ":question-storage");
      before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unsafe reservation retained " + change);
    }
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "unknown invocation retained");
    result("unknown reservations usage and invocation retained");
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
    result("writer receipt required before mutation");
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(state, true);
      var measured = scoped(System.nanoTime() + 30_000_000_000L);
      measured.cleanupArtifacts(null);
      reclaimed(2097152);
      require(
          "RELEASED"
              .equals(
                  string(
                      "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                      task + ":question-storage")),
          "storage hold released once");
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + json(measured.queryContext().resources()));
      String after = facts();
      ledger.cleanupArtifacts(null);
      require(after.equals(facts()), "idempotent second cleanup");
    }
    result("three terminal states exact body metadata accounting and idempotency");
    for (String state : List.of("RELEASED", "SETTLED")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET state=? WHERE tenant_id='tenant-a' AND id=?",
          state,
          task + ":question-storage");
      ledger.cleanupArtifacts(null);
      reclaimed(0);
      require(
          state.equals(
              string(
                  "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                  task + ":question-storage")),
          "zero update keeps original hold state");
    }
    result("zero hold update still purges and emits without budget release");
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE user_question SET answer_snapshot=NULL,receipt_snapshot=NULL,retain_until='2099-01-01' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    expectMetadata();
    ledger.cleanupArtifacts(null);
    reclaimed(2097152);
    result("nullable answer receipt and original task age eligibility");
    for (String prefix :
        List.of(
            "UPDATE user_question SET request_snapshot=",
            "UPDATE task SET request_snapshot=",
            "UPDATE tenant_budget SET storage_held=storage_held-2097152",
            "INSERT INTO event(")) {
      reset("SUCCEEDED", true);
      before = facts();
      var hits = new AtomicInteger();
      reject(() -> failing(prefix, hits, null).cleanupArtifacts(null), null);
      require(hits.get() == 1 && before.equals(facts()), "whole transaction rollback " + prefix);
      ledger.cleanupArtifacts(null);
      reclaimed(2097152);
    }
    result("four same DS SQL failure boundaries roll back and recover");
    reset("SUCCEEDED", true);
    before = facts();
    var owner = new JdbcLedger[1];
    var hits = new AtomicInteger();
    var deadline =
        scoped(
            failing(
                "UPDATE tenant_budget SET storage_held=storage_held-2097152",
                hits,
                () -> owner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 30_000_000_000L);
    owner[0] = deadline;
    reject(() -> deadline.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    require(
        hits.get() == 1 && before.equals(facts()),
        "deadline after refund before event rolls back every body accounting and event");
    ledger.cleanupArtifacts(null);
    reclaimed(2097152);
    reset("SUCCEEDED", true);
    var finalOwner = new JdbcLedger[1];
    var eventHits = new AtomicInteger();
    var afterEvent =
        scoped(
            failing(
                "INSERT INTO event(",
                eventHits,
                () -> finalOwner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 30_000_000_000L);
    finalOwner[0] = afterEvent;
    reject(() -> afterEvent.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    require(eventHits.get() == 1, "deadline restricted after actual final event SQL");
    reclaimed(2097152);
    String committed = facts();
    ledger.cleanupArtifacts(null);
    require(committed.equals(facts()), "original post event commit boundary idempotent recovery");
    System.out.println(
        "LIMITATION original core UoW has no final beforeCommit query deadline check; after-event expiry commits before next-query timeout, retained for both versions");
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
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        },
        "SESSION_QUERY_TIMEOUT");
    result("explicit actual meter and absolute deadline rollback then recovery");
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
              "two real budget contenders blocked");
        } finally {
          blocker.rollback();
        }
        first.get(25, TimeUnit.SECONDS);
        second.get(25, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    reclaimed(2097152);
    result("two product lock wait contenders release and emit once");
    require(
        foreign.equals(
            json(
                List.of(
                    db.queryForList("SELECT * FROM user_question WHERE tenant_id='tenant-b'"),
                    db.queryForList("SELECT * FROM task WHERE tenant_id='tenant-b'"),
                    db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")))),
        "other tenant full key facts unchanged");
    require(
        dispatches.equals(
            json(
                db.queryForList(
                    "SELECT * FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                    task))),
        "model dispatch snapshots and usage unchanged");
    for (var q : original.get("user_question"))
      require(
          java.util.Objects.equals(
                  q.get("request_digest"),
                  string(
                      "SELECT request_digest FROM user_question WHERE tenant_id='tenant-a' AND id=?",
                      q.get("id")))
              && java.util.Objects.equals(
                  q.get("answer_digest"),
                  string(
                      "SELECT answer_digest FROM user_question WHERE tenant_id='tenant-a' AND id=?",
                      q.get("id"))),
          "original question request answer digests retained");
    for (var invocation : original.get("invocation"))
      require(
          java.util.Objects.equals(
              invocation.get("output_digest"),
              string(
                  "SELECT output_digest FROM invocation WHERE tenant_id='tenant-a' AND id=?",
                  invocation.get("id"))),
          "original invocation output digest retained");
    require(
        ((Number) original.get("task").getFirst().get("fence")).longValue()
                == scalar("SELECT fence FROM task WHERE tenant_id='tenant-a' AND id=?", task)
            && capacity
                == scalar(
                    "SELECT question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'"),
        "original fence and separate question capacity unchanged");
    result("tenant provenance dispatch fence and capacity preserved");
    reset("SUCCEEDED", false);
  }
}
