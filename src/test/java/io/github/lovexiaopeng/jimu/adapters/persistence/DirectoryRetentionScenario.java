package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
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

/** 同一驱动对照准确旧/新JAR；只重置本驱动创建的私有目录任务与其文件。 */
public final class DirectoryRetentionScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static SecureWorkspaceStore workspace;
  private static Path root, barrier;
  private static String task, scope;
  private static long storage, sequence, revision;
  private static final Map<String, List<Map<String, Object>>> original = new LinkedHashMap<>();
  private static final Map<String, byte[]> files = new LinkedHashMap<>();
  private static final Map<String, Object> expectedMetadata = new LinkedHashMap<>();

  private static Object normalize(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    if (value instanceof Map<?, ?> row) {
      var copy = new LinkedHashMap<String, Object>();
      row.forEach((k, v) -> copy.put(k.toString(), normalize(v)));
      return copy;
    }
    if (value instanceof List<?> rows)
      return rows.stream().map(DirectoryRetentionScenario::normalize).toList();
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

  private static void reset(String state, boolean expired) throws Exception {
    for (String table : original.keySet()) restore(table);
    db.update(
        "DELETE FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='working_directory.content_deleted'",
        task);
    db.update(
        "UPDATE task SET state=?,next_event_seq=?,revision=? WHERE tenant_id='tenant-a' AND id=?",
        state,
        sequence,
        revision,
        task);
    db.update(
        "UPDATE task_directory SET retain_until=? WHERE tenant_id='tenant-a' AND task_id=?",
        expired ? "2020-01-01" : "2099-01-01",
        task);
    db.update(
        "UPDATE tenant_budget SET directory_storage_held=? WHERE tenant_id='tenant-a'", storage);
    for (var file : files.entrySet()) Files.write(root.resolve(file.getKey()), file.getValue());
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
                  + (table.equals("task_directory")
                      ? "task_id"
                      : table.equals("invocation_path_binding") ? "invocation_id" : "id"),
              task));
    rows.add(
        db.queryForList(
            "SELECT seq,fact_id,type FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
            task));
    rows.add(
        db.queryForMap(
            "SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'"));
    return json(rows);
  }

  private static void retained(
      String prefix, List<Map<String, Object>> rows, String id, String... fields) {
    for (var row : rows)
      for (String field : fields)
        if (row.get(field) != null) {
          String body = (String) row.get(field);
          String key = prefix + (id == null ? "" : row.get(id) + ":") + field;
          expectedMetadata.put(
              key,
              Map.of(
                  "sha256",
                  Payloads.hash(body),
                  "bytes",
                  body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        }
  }

  private static void reclaimed() {
    require(
        "PURGED"
            .equals(
                string(
                    "SELECT content_state FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                    task)),
        "projection PURGED");
    require(
        "PURGED"
            .equals(
                string(
                    "SELECT workspace_state FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                    task)),
        "workspace PURGED");
    require(
        scalar("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == storage - 8388608,
        "one original directory budget release");
    require(
        "RELEASED"
            .equals(
                string(
                    "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                    task + ":directory-storage")),
        "one original directory hold release");
    require(
        scalar(
                    "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='working_directory.content_deleted'",
                    task)
                == 1
            && scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == sequence + 1
            && scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == revision + 1,
        "one atomic deletion event and revision");
    String metadata =
        string(
            "SELECT retained_digests FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
            task);
    require(
        Payloads.read(metadata, com.fasterxml.jackson.databind.JsonNode.class)
            .equals(
                Payloads.read(
                    Payloads.json(expectedMetadata),
                    com.fasterxml.jackson.databind.JsonNode.class)),
        "all original body hashes and UTF8 byte lengths retained");
    require(
        files.keySet().stream().noneMatch(n -> Files.exists(root.resolve(n))),
        "exact persisted workspace inventory physically removed");
    require(
        scalar(
                "SELECT COUNT(*) FROM invocation_path_binding WHERE tenant_id='tenant-a' AND task_id=? AND (content_state<>'PURGED' OR base_cwd IS NOT NULL OR canonical_paths_snapshot IS NOT NULL)",
                task)
            == 0,
        "path bodies NULL and PURGED");
    require(
        "{\"version\":1,\"state\":\"DIRECTORY_PURGED\"}"
            .equals(
                string(
                    "SELECT request_snapshot FROM task WHERE tenant_id='tenant-a' AND id=?", task)),
        "original tombstone exact bytes");
  }

  private static JdbcLedger configured(javax.sql.DataSource data) {
    var value = new JdbcLedger(data, models);
    value.directoryEnabled(true);
    value.directoryWorkspace(workspace);
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
        .addFirst(new PropertiesPropertySource("private-directory-retention", props));
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
    root = Path.of(props.getProperty("jimu.workspace-root"));
    scope = Payloads.hash("tenant-a:" + task);
    workspace = new SecureWorkspaceStore(root).logicalDirectory();
    ledger = configured(source);
    for (String table :
        List.of(
            "task",
            "task_directory",
            "step",
            "invocation",
            "invocation_path_binding",
            "model_dispatch",
            "reservation")) remember(table, table.equals("task") ? "id" : "task_id");
    try (var listing = Files.list(root)) {
      for (var file : listing.toList())
        if (file.getFileName().toString().startsWith(scope + ".")
            && !file.toString().endsWith(".lock"))
          files.put(file.getFileName().toString(), Files.readAllBytes(file));
    }
    require(
        !files.isEmpty() && !original.get("invocation_path_binding").isEmpty(),
        "actual directory task files and path bindings");
    storage = scalar("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    sequence = scalar("SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    revision = scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    retained("task:", original.get("task"), null, "request_snapshot", "goal", "summary");
    retained(
        "step:",
        original.get("step"),
        "id",
        "request_snapshot",
        "response_snapshot",
        "directory_cwd");
    retained("invocation:", original.get("invocation"), "id", "input_snapshot", "output_snapshot");
    retained(
        "path:",
        original.get("invocation_path_binding"),
        "invocation_id",
        "base_cwd",
        "target_cwd",
        "canonical_paths_snapshot",
        "directory_evidence_snapshot");
    retained("dispatch:", original.get("model_dispatch"), "id", "usage_snapshot");
    retained("directory:", original.get("task_directory"), null, "cwd");
    String foreign =
        json(
            List.of(
                db.queryForList("SELECT * FROM task_directory WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM invocation_path_binding WHERE tenant_id='tenant-b'"),
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")));
    var foreignFiles = new LinkedHashMap<String, byte[]>();
    try (var listing = Files.list(root)) {
      for (var file : listing.toList())
        if (!file.getFileName().toString().startsWith(scope + ".")
            && !file.toString().endsWith(".lock"))
          foreignFiles.put(file.getFileName().toString(), Files.readAllBytes(file));
    }
    for (String state : List.of("SUCCEEDED", "RUNNING", "NEEDS_RECONCILIATION")) {
      reset(state, !state.equals("SUCCEEDED"));
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unexpired or nonterminal retained " + state);
    }
    result("unexpired running and UNKNOWN retained");
    for (String change : List.of("state='HELD_UNKNOWN'", "usage_quality='UNKNOWN'")) {
      reset("SUCCEEDED", true);
      db.update(
          "UPDATE reservation SET " + change + " WHERE tenant_id='tenant-a' AND id=?",
          task + ":directory-storage");
      String before = facts();
      ledger.cleanupArtifacts(null);
      require(before.equals(facts()), "unsafe reservation retained " + change);
    }
    result("HELD_UNKNOWN and unknown usage retained");
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
    String before = facts();
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
    result("writer quiescence required");
    reset("SUCCEEDED", true);
    String activeId =
        original.get("invocation").stream()
            .filter(r -> "write".equals(r.get("tool")))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();
    db.update(
        "UPDATE invocation SET state='RUNNING' WHERE tenant_id='tenant-a' AND id=?", activeId);
    before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "running invocation retained");
    reset("SUCCEEDED", true);
    db.update(
        "UPDATE model_dispatch SET state='DISPATCHED' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    before = facts();
    ledger.cleanupArtifacts(null);
    require(before.equals(facts()), "dispatched model retained");
    result("active invocation and dispatch retained");
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(state, true);
      var measured = scoped(System.nanoTime() + 30_000_000_000L);
      measured.cleanupArtifacts(null);
      reclaimed();
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + json(measured.queryContext().resources()));
      String after = facts();
      ledger.cleanupArtifacts(null);
      require(after.equals(facts()), "idempotent no second release");
    }
    result("three terminal states purge and exact atomic metadata accounting");
    for (String prefix :
        List.of(
            "UPDATE invocation_path_binding SET base_cwd=NULL",
            "UPDATE task_directory SET filesystem_receipt=",
            "UPDATE reservation SET state='RELEASED'")) {
      reset("SUCCEEDED", true);
      before = facts();
      var hits = new AtomicInteger();
      reject(() -> failing(prefix, hits, null).cleanupArtifacts(null), null);
      require(hits.get() == 1, "same DS exact SQL fault");
      String status =
          string(
              "SELECT content_state FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
              task);
      if (prefix.contains("invocation_path_binding"))
        require(
            before.equals(facts())
                && files.keySet().stream().allMatch(n -> Files.exists(root.resolve(n))),
            "first transaction rolls back all bodies and metadata");
      else {
        require(
            "PURGING".equals(status)
                && scalar(
                        "SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                    == storage
                && scalar(
                        "SELECT next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                    == sequence,
            "durable PURGING without event or release");
        require(
            (string(
                        "SELECT filesystem_receipt FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                        task)
                    != null)
                == prefix.contains("reservation"),
            "receipt matches failed transaction boundary");
        require(
            files.keySet().stream().noneMatch(n -> Files.exists(root.resolve(n)))
                == prefix.contains("reservation"),
            "physical deletion matches original transaction boundary");
      }
      ledger.cleanupArtifacts(null);
      reclaimed();
    }
    result("three transaction SQL failures rollback and recover without replay");
    reset("SUCCEEDED", true);
    Path extra = root.resolve(scope + ".11111111-1111-4111-8111-111111111111.part");
    var hits = new AtomicInteger();
    reject(
        () ->
            failing(
                    "UPDATE task_directory SET filesystem_receipt=",
                    hits,
                    () -> {
                      try {
                        Files.writeString(extra, "private changed inventory");
                      } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                      }
                    })
                .cleanupArtifacts(null),
        null);
    require(
        hits.get() == 1
            && "PURGING"
                .equals(
                    string(
                        "SELECT content_state FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                        task))
            && string(
                    "SELECT filesystem_receipt FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                    task)
                != null,
        "changed filesystem inventory refuses after durable receipt");
    String
        generation =
            string(
                "SELECT cleanup_generation FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        receipt =
            string(
                "SELECT filesystem_receipt FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                task);
    Files.delete(extra);
    ledger.cleanupArtifacts(null);
    reclaimed();
    require(
        generation.equals(
                string(
                    "SELECT cleanup_generation FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                    task))
            && receipt.equals(
                string(
                    "SELECT filesystem_receipt FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                    task)),
        "recovery reuses exact persisted generation and receipt");
    result("physical inventory refusal and durable receipt recovery");
    reset("SUCCEEDED", true);
    var deadlineOwner = new JdbcLedger[1];
    var eventWrites = new AtomicInteger();
    var deadline =
        scoped(
            failing(
                "INSERT INTO event(",
                eventWrites,
                () -> deadlineOwner[0].queryContext().restrictDeadline(System.nanoTime() - 1)),
            System.nanoTime() + 30_000_000_000L);
    deadlineOwner[0] = deadline;
    reject(() -> deadline.cleanupArtifacts(null), "SESSION_QUERY_TIMEOUT");
    require(
        eventWrites.get() == 1
            && "PURGING"
                .equals(
                    string(
                        "SELECT content_state FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?",
                        task))
            && scalar("SELECT directory_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                == storage
            && scalar("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", task)
                == revision,
        "late event deadline rolls back final event revision and release");
    ledger.cleanupArtifacts(null);
    reclaimed();
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
    result("explicit physical meter and absolute deadline final rollback");
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
            barrier.resolve("waiting"), "two product cleanup transactions await budget");
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
    result("two product lock wait contenders release once");
    require(
        foreign.equals(
            json(
                List.of(
                    db.queryForList("SELECT * FROM task_directory WHERE tenant_id='tenant-b'"),
                    db.queryForList(
                        "SELECT * FROM invocation_path_binding WHERE tenant_id='tenant-b'"),
                    db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'")))),
        "other tenant full key facts unchanged");
    for (var file : foreignFiles.entrySet())
      require(
          java.util.Arrays.equals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey()))),
          "other task filesystem unchanged");
    for (var row : original.get("invocation"))
      require(
          java.util.Objects.equals(
              row.get("output_digest"),
              string(
                  "SELECT output_digest FROM invocation WHERE tenant_id='tenant-a' AND id=?",
                  row.get("id"))),
          "original invocation output digest retained");
    require(
        ((Number) original.get("task").getFirst().get("fence")).longValue()
            == scalar("SELECT fence FROM task WHERE tenant_id='tenant-a' AND id=?", task),
        "original task fence unchanged");
    result("tenant filesystem fence and retained provenance preserved");
    reset("SUCCEEDED", false);
  }
}
