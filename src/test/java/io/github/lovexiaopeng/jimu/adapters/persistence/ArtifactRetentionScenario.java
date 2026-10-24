package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.storage.LocalArtifactStore;
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

/** 精确旧新制品共用：真实产物/本地存储/SQL事务、故障重试及双连接等待。 */
public final class ArtifactRetentionScenario {
  private static JdbcTemplate db;
  private static DriverManagerDataSource source;
  private static ModelProfiles models;
  private static JdbcLedger ledger;
  private static LocalArtifactStore disk;
  private static String task, artifact, upload, key, hash;
  private static byte[] bytes;
  private static long held, amount;
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

  private static void reset(String state, String artifactState, boolean expired, boolean old) {
    db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", state, task);
    db.update(
        "UPDATE artifact SET state=?,expires_at=?,updated_at=? WHERE tenant_id='tenant-a' AND id=?",
        artifactState,
        expired ? "2020-01-01 00:00:00" : "2099-01-01 00:00:00",
        old ? "2020-01-01 00:00:00" : "2099-01-01 00:00:00",
        artifact);
    db.update("UPDATE tenant_budget SET storage_held=? WHERE tenant_id='tenant-a'", held);
    db.update(
        "UPDATE reservation SET state='SETTLED' WHERE tenant_id='tenant-a' AND id=?", artifact);
    disk.write("tenant-a", upload, bytes, hash);
  }

  private static boolean filePresent() {
    try {
      return java.util.Arrays.equals(bytes, disk.read("tenant-a", key, hash, bytes.length));
    } catch (RuntimeException missing) {
      return false;
    }
  }

  private static void reclaimed(String expected) {
    require(
        expected.equals(
            db.queryForObject(
                "SELECT state FROM artifact WHERE tenant_id='tenant-a' AND id=?",
                String.class,
                artifact)),
        "artifact state " + expected);
    require(!filePresent(), "actual object removed");
    require(
        scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'") == held - amount
            && "RELEASED"
                .equals(
                    db.queryForObject(
                        "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                        String.class,
                        artifact)),
        "one atomic full-key hold release");
  }

  private static ArtifactStore store(Runnable before) {
    return new ArtifactStore() {
      public String write(String tenant, String id, byte[] value, String digest) {
        throw new AssertionError("Unexpected write");
      }

      public byte[] read(String tenant, String path, String digest, long size) {
        throw new AssertionError("Unexpected read");
      }

      public void check() {}

      public void removeUpload(String tenant, String id) {
        require(
            !TransactionSynchronizationManager.isActualTransactionActive(), "delete outside UoW");
        require(
            tenant.equals("tenant-a") && id.equals(upload), "exact tenant/upload storage identity");
        before.run();
        // The fixture serializes only the physical delete to make the two settlement contenders
        // deterministic. Concurrent filesystem list/delete races retain the original failure
        // contract.
        synchronized (disk) {
          disk.removeUpload(tenant, id);
        }
      }
    };
  }

  private static JdbcLedger scoped(long end) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(ledger, end, new QueryResources(0, 0, 0));
  }

  private static JdbcLedger failing(String sqlPrefix, AtomicInteger hit) {
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
                            && ((String) args[0]).startsWith(sqlPrefix)) {
                          var statement = (Statement) value;
                          return Proxy.newProxyInstance(
                              java.sql.PreparedStatement.class.getClassLoader(),
                              new Class<?>[] {java.sql.PreparedStatement.class},
                              (p, m, a) -> {
                                if (m.getName().startsWith("execute")) {
                                  hit.incrementAndGet();
                                  throw new SQLException("private injected write failure", "45000");
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

  private static void reject(Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("Injected failure was accepted");
    } catch (RuntimeException expected) {
      System.out.println("FAILURE_EXPECTED " + expected.getClass().getSimpleName());
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
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-retention", props));
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
    disk = new LocalArtifactStore(Path.of(props.getProperty("jimu.storage-root")));
    task = args[1];
    artifact = args[2];
    barrier = Path.of(args[3]);
    var original =
        db.queryForMap("SELECT * FROM artifact WHERE tenant_id='tenant-a' AND id=?", artifact);
    upload = JdbcLedger.s(original, "upload_id");
    key = JdbcLedger.s(original, "snapshot_key");
    hash = JdbcLedger.s(original, "sha256");
    bytes = disk.read("tenant-a", key, hash, JdbcLedger.n(original, "size"));
    held = scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    amount = scalar("SELECT amount FROM reservation WHERE tenant_id='tenant-a' AND id=?", artifact);
    var taskProof =
        db.queryForMap(
            "SELECT fence,revision,next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?",
            task);
    String foreign =
        Payloads.json(db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'"));
    var actualStore = store(() -> {});

    for (var row :
        List.of(
            List.of("FAILED", "STAGING", false, false),
            List.of("SUCCEEDED", "PUBLISHED", false, true),
            List.of("RUNNING", "PUBLISHED", true, true),
            List.of("NEEDS_RECONCILIATION", "PUBLISHED", true, true))) {
      reset((String) row.get(0), (String) row.get(1), (boolean) row.get(2), (boolean) row.get(3));
      ledger.cleanupArtifacts(
          store(
              () -> {
                throw new AssertionError("unsafe delete");
              }));
      require(
          filePresent()
              && scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
                  == held,
          "retain " + row);
    }
    result("recent/future/running/UNKNOWN retained");

    reset("FAILED", "PUBLISHED", true, true);
    var attempts =
        db.queryForList(
            "SELECT id,worker_id,quiescent FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?",
            task);
    var workers =
        db.queryForList(
            "SELECT id,stopped FROM worker_process WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
            task);
    require(
        !attempts.isEmpty() && !workers.isEmpty(),
        "actual product attempt/worker supplies unknown-writer fixture");
    try {
      db.update(
          "UPDATE task_attempt SET quiescent=FALSE WHERE tenant_id='tenant-a' AND task_id=?", task);
      db.update(
          "UPDATE worker_process SET stopped=FALSE WHERE id IN (SELECT worker_id FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?)",
          task);
      ledger.cleanupArtifacts(
          store(
              () -> {
                throw new AssertionError("unobserved writer deleted");
              }));
      require(filePresent(), "unobserved writer retains object");
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

    for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      reset(terminal, "PUBLISHED", true, true);
      var measured = scoped(System.nanoTime() + 15_000_000_000L);
      measured.cleanupArtifacts(actualStore);
      reclaimed("EXPIRED");
      if (terminal.equals("SUCCEEDED"))
        System.out.println("QUERY reclaimed " + Payloads.json(measured.queryContext().resources()));
      ledger.cleanupArtifacts(
          store(
              () -> {
                throw new AssertionError("released hold selected");
              }));
      reclaimed("EXPIRED");
    }
    result("expired three-terminal states release once");
    for (String terminal : List.of("FAILED", "CANCELLED")) {
      reset(terminal, "STAGING", false, true);
      ledger.cleanupArtifacts(actualStore);
      reclaimed("ABANDONED");
    }
    result("failed/cancelled abandoned release");

    reset("SUCCEEDED", "PUBLISHED", true, true);
    reject(
        () ->
            ledger.cleanupArtifacts(
                store(
                    () -> {
                      throw new IllegalStateException("private delete failure");
                    })));
    require(
        filePresent()
            && scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'") == held,
        "delete failure holds accounting");
    ledger.cleanupArtifacts(actualStore);
    reclaimed("EXPIRED");
    result("storage failure then actual retry");

    reset("SUCCEEDED", "PUBLISHED", true, true);
    var hit = new AtomicInteger();
    reject(() -> failing("UPDATE artifact SET state=", hit).cleanupArtifacts(actualStore));
    require(
        hit.get() == 1
            && filePresent()
            && "PUBLISHED"
                .equals(
                    db.queryForObject(
                        "SELECT state FROM artifact WHERE tenant_id='tenant-a' AND id=?",
                        String.class,
                        artifact)),
        "mark failure rolls back and skips delete");
    result("mark transaction rollback");

    reset("SUCCEEDED", "PUBLISHED", true, true);
    hit.set(0);
    reject(
        () ->
            failing("UPDATE reservation SET state='RELEASED' WHERE tenant_id=", hit)
                .cleanupArtifacts(actualStore));
    require(
        hit.get() == 1
            && !filePresent()
            && scalar("SELECT storage_held FROM tenant_budget WHERE tenant_id='tenant-a'") == held
            && "SETTLED"
                .equals(
                    db.queryForObject(
                        "SELECT state FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                        String.class,
                        artifact)),
        "late release failure rolls back budget/hold after committed delete");
    ledger.cleanupArtifacts(actualStore);
    reclaimed("EXPIRED");
    result("settlement rollback and missing-object recovery");

    reset("SUCCEEDED", "PUBLISHED", false, true);
    var measured = scoped(System.nanoTime() + 15_000_000_000L);
    measured.cleanupArtifacts(actualStore);
    var once = measured.queryContext().resources();
    measured.cleanupArtifacts(actualStore);
    var twice = measured.queryContext().resources();
    require(
        once.reads() > 0
            && twice.reads() == once.reads() * 2
            && twice.bytes() == once.bytes() * 2
            && twice.operations() == once.operations() * 2,
        "actual retained cleanup results counted once without cache");
    System.out.println("QUERY retained " + Payloads.json(once));
    try {
      scoped(System.nanoTime() - 1).cleanupArtifacts(actualStore);
      throw new AssertionError("expired deadline accepted");
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (!(cause instanceof TaskFailure) && cause.getCause() != null) cause = cause.getCause();
      require(
          cause instanceof TaskFailure code && code.code().equals("SESSION_QUERY_TIMEOUT"),
          "absolute cleanup deadline");
    }
    result("explicit physical query meter and absolute deadline");

    reset("SUCCEEDED", "PUBLISHED", true, true);
    try (var blocker = source.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement
            .executeQuery(
                "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")
            .close();
        var future = pool.submit(() -> ledger.cleanupArtifacts(actualStore));
        Files.writeString(barrier.resolve("waiting"), "budget row held; product cleanup waiting");
        try {
          awaitFile("release");
          require(!future.isDone(), "cleanup honors budget prefix");
        } finally {
          blocker.rollback();
        }
        future.get(20, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }
    reclaimed("EXPIRED");
    result("actual budget lock wait then normal completion");

    reset("SUCCEEDED", "PUBLISHED", true, true);
    var entered = new CountDownLatch(2);
    var concurrentStore =
        store(
            () -> {
              entered.countDown();
              try {
                require(
                    entered.await(12, TimeUnit.SECONDS),
                    "two cleanup contenders reach external delete");
                Files.writeString(
                    barrier.resolve("deleting"),
                    "two product cleanup transactions committed marks");
                awaitFile("delete-release");
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> ledger.cleanupArtifacts(concurrentStore));
      var second = pool.submit(() -> ledger.cleanupArtifacts(concurrentStore));
      first.get(25, TimeUnit.SECONDS);
      second.get(25, TimeUnit.SECONDS);
    }
    reclaimed("EXPIRED");
    result("two candidate finalizers release same hold once");
    require(
        taskProof.equals(
            db.queryForMap(
                "SELECT fence,revision,next_event_seq FROM task WHERE tenant_id='tenant-a' AND id=?",
                task)),
        "task fence/revision/event sequence unchanged");
    require(
        foreign.equals(
            Payloads.json(
                db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-b'"))),
        "other tenant full budget unchanged");
    result("tenant/full-key and fence/event preservation");
    reset("SUCCEEDED", "PUBLISHED", false, true);
  }
}
