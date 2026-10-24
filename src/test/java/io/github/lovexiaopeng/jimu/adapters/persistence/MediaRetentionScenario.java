package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 同一驱动运行准确旧/新制品；实际HTTP生成媒体/检查点，私有库故障与双连接屏障。 */
public final class MediaRetentionScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static SecureImageStore disk;
  private static String task, image, key, hash;
  private static byte[] bytes;
  private static long storage, checkpoints, storageRelease, checkpointRelease;
  private static List<Map<String, Object>> steps, inputs, holds;
  private static Path barrier;

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static long scalar(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  private static void result(String name) {
    System.out.println("RESULT " + name);
  }

  private static void reset(String state, boolean aged) {
    db.update(
        "UPDATE task SET state=?,media_cleanup_state='RETAINED',updated_at=? WHERE tenant_id='tenant-a' AND id=?",
        state,
        aged ? "2020-01-01 00:00:00" : "2099-01-01 00:00:00",
        task);
    db.update(
        "UPDATE task_image_object SET cleanup_state='RETAINED' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    for (var row : steps)
      db.update(
          "UPDATE step SET request_snapshot=?,request_digest=?,request_cleanup_state=?,retained_request_digest=? WHERE tenant_id='tenant-a' AND id=?",
          row.get("request_snapshot"),
          row.get("request_digest"),
          row.get("request_cleanup_state"),
          row.get("retained_request_digest"),
          row.get("id"));
    for (var row : inputs)
      db.update(
          "UPDATE task_image_input SET raw_base64=? WHERE tenant_id='tenant-a' AND task_id=? AND input_id=?",
          row.get("raw_base64"),
          task,
          row.get("input_id"));
    for (var row : holds)
      db.update(
          "UPDATE reservation SET state=?,amount=?,actual=? WHERE tenant_id='tenant-a' AND id=?",
          row.get("state"),
          row.get("amount"),
          row.get("actual"),
          row.get("id"));
    db.update(
        "UPDATE tenant_budget SET storage_held=?,media_checkpoint_held=? WHERE tenant_id='tenant-a'",
        storage,
        checkpoints);
    disk.write("tenant-a", image, bytes, hash);
  }

  private static boolean present() {
    try {
      return java.util.Arrays.equals(bytes, disk.read("tenant-a", key, hash, bytes.length));
    } catch (RuntimeException missing) {
      return false;
    }
  }

  private static String retainedFacts() {
    return Payloads.json(
        List.of(
            db.queryForList(
                "SELECT id,request_snapshot,request_digest,request_cleanup_state,retained_request_digest FROM step WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                task),
            db.queryForList(
                "SELECT input_id,raw_base64 FROM task_image_input WHERE tenant_id='tenant-a' AND task_id=? ORDER BY input_id",
                task),
            db.queryForList(
                "SELECT id,state,amount,actual FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category IN ('MEDIA_INPUT','MEDIA_STORAGE','MEDIA_CHECKPOINT') ORDER BY id",
                task),
            db.queryForMap(
                "SELECT storage_held,media_checkpoint_held FROM tenant_budget WHERE tenant_id='tenant-a'")));
  }

  private static void reclaimed() {
    require(!present(), "actual secure media object removed");
    require(
        "DELETED"
            .equals(
                db.queryForObject(
                    "SELECT media_cleanup_state FROM task WHERE tenant_id='tenant-a' AND id=?",
                    String.class,
                    task)),
        "task cleanup finalized");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_image_object WHERE tenant_id='tenant-a' AND task_id=? AND cleanup_state<>'DELETED'",
                task)
            == 0,
        "exact task objects marked deleted");
    require(
        scalar(
                "SELECT COUNT(*) FROM task_image_input WHERE tenant_id='tenant-a' AND task_id=? AND raw_base64 IS NOT NULL",
                task)
            == 0,
        "raw image input purged");
    require(
        scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                == storage - storageRelease
            && scalar("SELECT media_checkpoint_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                == checkpoints - checkpointRelease,
        "atomic actual-or-reserved media byte release exactly once");
    require(
        scalar(
                "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category IN ('MEDIA_INPUT','MEDIA_STORAGE','MEDIA_CHECKPOINT') AND state<>'RELEASED'",
                task)
            == 0,
        "media holds released");
    for (var original : steps) {
      var row =
          db.queryForMap(
              "SELECT request_snapshot,request_digest,request_cleanup_state,retained_request_digest FROM step WHERE tenant_id='tenant-a' AND id=?",
              original.get("id"));
      var expected =
          Payloads.read(
              (String) original.get("request_snapshot"),
              com.fasterxml.jackson.databind.node.ObjectNode.class);
      expected.remove("requestBody");
      require(
          row.get("request_snapshot").equals(Payloads.json(expected))
              && row.get("request_digest").equals(original.get("request_digest"))
              && row.get("request_cleanup_state").equals("MEDIA_PURGED")
              && row.get("retained_request_digest").equals(Payloads.hash(Payloads.json(expected))),
          "only body removed; original digest and exact retained digest preserved");
    }
  }

  private static ArtifactStore store(Runnable before) {
    return new ArtifactStore() {
      public String write(String tenant, String id, byte[] value, String digest) {
        throw new AssertionError();
      }

      public byte[] read(String tenant, String path, String digest, long size) {
        throw new AssertionError();
      }

      public void check() {}

      public void removeUpload(String tenant, String id) {
        require(
            !TransactionSynchronizationManager.isActualTransactionActive(),
            "media delete outside UoW");
        require(
            tenant.equals("tenant-a") && id.equals(image), "persisted full tenant/image identity");
        before.run();
        // Only the fixture serializes physical unlink; this does not close general filesystem
        // races.
        synchronized (disk) {
          disk.removeUpload(tenant, id);
        }
      }
    };
  }

  private static void cleanup(JdbcLedger target, ArtifactStore store) {
    target.imageStorage(store);
    target.cleanupArtifacts(null);
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
                                      "private injected media write failure", "45000");
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

  private static void reject(Runnable operation, String code) {
    try {
      operation.run();
      throw new AssertionError("failure accepted: " + code);
    } catch (RuntimeException expected) {
      if (code != null) {
        Throwable cause = expected;
        while (!(cause instanceof TaskFailure) && cause.getCause() != null)
          cause = cause.getCause();
        require(
            cause instanceof TaskFailure failure && code.equals(failure.code()),
            "exact refusal " + code);
      }
      System.out.println("FAILURE_EXPECTED " + expected);
    }
  }

  private static void awaitFile(String name) throws Exception {
    long end = System.nanoTime() + 12_000_000_000L;
    while (!Files.exists(barrier.resolve(name)) && System.nanoTime() < end) Thread.sleep(20);
    require(Files.exists(barrier.resolve(name)), "bounded barrier " + name);
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      props.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(new PropertiesPropertySource("private-media-retention", props));
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
    disk = new SecureImageStore(Path.of(props.getProperty("jimu.images.root")));
    task = args[1];
    barrier = Path.of(args[2]);
    var object =
        db.queryForMap(
            "SELECT * FROM task_image_object WHERE tenant_id='tenant-a' AND task_id=?", task);
    image = JdbcLedger.s(object, "image_id");
    key = JdbcLedger.s(object, "snapshot_key");
    hash = JdbcLedger.s(object, "sha256");
    bytes = disk.read("tenant-a", key, hash, JdbcLedger.n(object, "size"));
    steps =
        db.queryForList(
            "SELECT id,request_snapshot,request_digest,request_cleanup_state,retained_request_digest FROM step WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
            task);
    inputs =
        db.queryForList(
            "SELECT input_id,raw_base64 FROM task_image_input WHERE tenant_id='tenant-a' AND task_id=?",
            task);
    holds =
        db.queryForList(
            "SELECT id,category,state,amount,actual FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category IN ('MEDIA_INPUT','MEDIA_STORAGE','MEDIA_CHECKPOINT') ORDER BY id",
            task);
    var originalHolds = holds.stream().map(java.util.HashMap::new).toList();
    storage = scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    checkpoints =
        scalar("SELECT media_checkpoint_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    require(
        !steps.isEmpty()
            && !inputs.isEmpty()
            && holds.stream().map(r -> r.get("category")).distinct().count() == 3,
        "actual product checkpoints, raw input and three hold categories");
    // Private lifecycle/accounting fixture: distinguish zero actual, nonnull actual and reserved
    // fallback.
    boolean zero = false;
    for (var hold : holds) {
      switch ((String) hold.get("category")) {
        case "MEDIA_INPUT" -> hold.put("actual", null);
        case "MEDIA_STORAGE" -> {
          hold.put("actual", hold.get("actual") == null ? hold.get("amount") : hold.get("actual"));
          hold.put("amount", ((Number) hold.get("amount")).longValue() + 17);
        }
        default -> {
          if (!zero) {
            hold.put("actual", 0L);
            zero = true;
          }
        }
      }
      long amount =
          ((Number) (hold.get("actual") == null ? hold.get("amount") : hold.get("actual")))
              .longValue();
      if (hold.get("category").equals("MEDIA_CHECKPOINT")) checkpointRelease += amount;
      else storageRelease += amount;
    }
    var taskProof =
        db.queryForMap(
            "SELECT fence,revision,next_event_seq,media_digest FROM task WHERE tenant_id='tenant-a' AND id=?",
            task);
    String foreign =
        Payloads.json(db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'"));
    db.update(
        "UPDATE artifact SET expires_at='2099-01-01' WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    var actual = store(() -> {});

    for (var test :
        List.of(
            List.of("SUCCEEDED", false),
            List.of("RUNNING", true),
            List.of("NEEDS_RECONCILIATION", true))) {
      reset((String) test.get(0), (boolean) test.get(1));
      String before = retainedFacts();
      cleanup(
          ledger,
          store(
              () -> {
                throw new AssertionError("unsafe cleanup");
              }));
      require(
          present() && before.equals(retainedFacts()), "recent/running/UNKNOWN retained " + test);
    }
    result("recent/running/UNKNOWN retained");

    reset("FAILED", true);
    var attempts =
        db.queryForList(
            "SELECT id,quiescent FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?", task);
    var workers =
        db.queryForList(
            "SELECT id,stopped FROM worker_process WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
            task);
    require(!attempts.isEmpty() && !workers.isEmpty(), "actual attempt and worker fixture");
    try {
      db.update(
          "UPDATE task_attempt SET quiescent=FALSE WHERE tenant_id='tenant-a' AND task_id=?", task);
      db.update(
          "UPDATE worker_process SET stopped=FALSE WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
          task);
      String before = retainedFacts();
      cleanup(
          ledger,
          store(
              () -> {
                throw new AssertionError("unobserved writer cleanup");
              }));
      require(
          present() && before.equals(retainedFacts()),
          "unobserved writer retains bytes and accounting");
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

    reset("SUCCEEDED", true);
    db.update(
        "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id='tenant-a' AND id=?",
        holds.getFirst().get("id"));
    String before = retainedFacts();
    cleanup(
        ledger,
        store(
            () -> {
              throw new AssertionError("unknown hold cleanup");
            }));
    require(
        present() && before.equals(retainedFacts()), "unknown hold retains bytes and accounting");
    result("HELD_UNKNOWN retained");

    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(state, true);
      var measured = scoped(System.nanoTime() + 15_000_000_000L);
      cleanup(measured, actual);
      reclaimed();
      if (state.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + Payloads.json(measured.queryContext().resources()));
      cleanup(
          ledger,
          store(
              () -> {
                throw new AssertionError("finalized media selected");
              }));
      reclaimed();
    }
    result("three terminal states purge body and release actual-or-reserved once");

    reset("SUCCEEDED", true);
    before = retainedFacts();
    reject(
        () ->
            cleanup(
                ledger,
                store(
                    () -> {
                      throw new IllegalStateException("private storage failure");
                    })),
        null);
    require(
        present() && before.equals(retainedFacts()),
        "delete failure leaves payload and accounting retained");
    cleanup(ledger, actual);
    reclaimed();
    result("storage failure and retry");

    reset("SUCCEEDED", true);
    before = retainedFacts();
    var hits = new AtomicInteger();
    reject(
        () ->
            cleanup(failing("UPDATE task_image_object SET cleanup_state='DELETING'", hits), actual),
        null);
    require(
        hits.get() == 1
            && present()
            && before.equals(retainedFacts())
            && scalar(
                    "SELECT COUNT(*) FROM task_image_object WHERE tenant_id='tenant-a' AND task_id=? AND cleanup_state='RETAINED'",
                    task)
                == 1,
        "mark transaction rolls back and skips external delete");
    result("mark transaction rollback");

    reset("SUCCEEDED", true);
    before = retainedFacts();
    hits.set(0);
    reject(
        () -> cleanup(failing("UPDATE task SET media_cleanup_state='DELETED'", hits), actual),
        null);
    require(
        hits.get() == 1 && !present() && before.equals(retainedFacts()),
        "late task write rolls back all checkpoint/input/hold/budget writes");
    cleanup(ledger, actual);
    reclaimed();
    result("atomic purge rollback and missing-object recovery");

    reset("SUCCEEDED", true);
    db.update(
        "UPDATE step SET request_snapshot='invalid JSON',request_digest=? WHERE tenant_id='tenant-a' AND id=?",
        "0".repeat(64),
        steps.getFirst().get("id"));
    before = retainedFacts();
    reject(() -> cleanup(ledger, actual), "SNAPSHOT_CORRUPT");
    require(
        !present() && before.equals(retainedFacts()),
        "digest refusal before decode preserves transaction facts");
    result("corrupt snapshot rejected before JSON parse");

    reset("SUCCEEDED", true);
    before = retainedFacts();
    reject(
        () ->
            cleanup(
                ledger,
                store(
                    () ->
                        db.update(
                            "UPDATE task SET state='NEEDS_RECONCILIATION' WHERE tenant_id='tenant-a' AND id=?",
                            task))),
        "IMAGE_UNAVAILABLE");
    require(!present() && before.equals(retainedFacts()), "terminal state rechecked after delete");
    db.update("UPDATE task SET state='SUCCEEDED' WHERE tenant_id='tenant-a' AND id=?", task);
    cleanup(ledger, actual);
    reclaimed();
    result("post-delete terminal refusal and recovery");

    reset("SUCCEEDED", true);
    before = retainedFacts();
    var expired = scoped(System.nanoTime() + 15_000_000_000L);
    reject(
        () ->
            cleanup(
                expired,
                store(() -> expired.queryContext().restrictDeadline(System.nanoTime() - 1))),
        "SESSION_QUERY_TIMEOUT");
    require(
        !present() && before.equals(retainedFacts()),
        "absolute deadline cannot restart for finalization");
    cleanup(ledger, actual);
    reclaimed();
    reset("SUCCEEDED", false);
    var measured = scoped(System.nanoTime() + 15_000_000_000L);
    cleanup(measured, actual);
    var once = measured.queryContext().resources();
    cleanup(measured, actual);
    var twice = measured.queryContext().resources();
    require(
        once.reads() > 0
            && twice.reads() == once.reads() * 2
            && twice.bytes() == once.bytes() * 2
            && twice.operations() == once.operations() * 2,
        "explicit results counted once without cache");
    System.out.println("QUERY retained " + Payloads.json(once));
    reject(
        () -> {
          try {
            cleanup(scoped(System.nanoTime() - 1), actual);
          } catch (RuntimeException failure) {
            throw failure;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        },
        "SESSION_QUERY_TIMEOUT");
    result("physical query meter and absolute pre/post-delete deadline");

    reset("SUCCEEDED", true);
    try (var blocker = source.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement
            .executeQuery(
                "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")
            .close();
        var future = pool.submit(() -> cleanup(ledger, actual));
        Files.writeString(barrier.resolve("waiting"), "budget row held; actual product waiting");
        try {
          awaitFile("release");
          require(!future.isDone(), "cleanup honors original budget lock prefix");
        } finally {
          blocker.rollback();
        }
        future.get(20, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    reclaimed();
    result("actual budget lock wait and normal completion");

    reset("SUCCEEDED", true);
    var entered = new CountDownLatch(2);
    var contenders =
        store(
            () -> {
              entered.countDown();
              try {
                require(
                    entered.await(12, TimeUnit.SECONDS),
                    "two media cleanup contenders reach deletion");
                Files.writeString(
                    barrier.resolve("deleting"), "both media mark transactions committed");
                awaitFile("delete-release");
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    ledger.imageStorage(contenders);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> ledger.cleanupArtifacts(null));
      var second = pool.submit(() -> ledger.cleanupArtifacts(null));
      first.get(25, TimeUnit.SECONDS);
      second.get(25, TimeUnit.SECONDS);
    }
    reclaimed();
    result("two candidate finalizers release once");
    require(
        taskProof.equals(
            db.queryForMap(
                "SELECT fence,revision,next_event_seq,media_digest FROM task WHERE tenant_id='tenant-a' AND id=?",
                task)),
        "fence/revision/events/media digest unchanged");
    require(
        foreign.equals(
            Payloads.json(
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'"))),
        "other tenant budget unchanged");
    result("tenant/full-key and fence/digest preservation");
    reset("SUCCEEDED", false);
    for (var hold : originalHolds)
      db.update(
          "UPDATE reservation SET state=?,amount=?,actual=? WHERE tenant_id='tenant-a' AND id=?",
          hold.get("state"),
          hold.get("amount"),
          hold.get("actual"),
          hold.get("id"));
  }
}
