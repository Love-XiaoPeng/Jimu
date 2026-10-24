package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一驱动可加载旧/新制品，实际持有run锁并观测等待后才释放，禁止时间猜测。 */
public final class OperatorBindingLockScenario {
  private OperatorBindingLockScenario() {}

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var settings = new HashMap<String, Object>();
    props.forEach((k, v) -> settings.put(k.toString(), v));
    var env = new StandardEnvironment();
    env.getPropertySources().remove("systemEnvironment");
    env.getPropertySources().remove("systemProperties");
    env.getPropertySources().addFirst(new MapPropertySource("fixture", settings));
    var source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.migration-user"),
            props.getProperty("jimu.db.migration-password"));
    var input =
        Payloads.read(Files.readString(Path.of(args[1])), ExecutionScopeBinding.Input.class);
    var binder = new ExecutionScopeBinding(source, new ModelProfiles(env));
    var db = new JdbcTemplate(source);
    try (var blocker = source.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement(
              "SELECT id FROM evaluation_budget WHERE tenant_id=? AND id=? FOR UPDATE")) {
        lock.setString(1, input.scope().tenantId());
        lock.setString(2, input.scope().runId());
        try (var result = lock.executeQuery()) {
          if (!result.next()) throw new AssertionError("missing run");
        }
      }
      var future = pool.submit(() -> binder.bindOnce(input));
      try {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
        boolean found = false;
        while (System.nanoTime() < end && !future.isDone()) {
          var waits =
              db.queryForList(
                  "SELECT w.REQUESTING_ENGINE_TRANSACTION_ID,w.REQUESTING_THREAD_ID,w.BLOCKING_ENGINE_TRANSACTION_ID,w.BLOCKING_THREAD_ID,l.OBJECT_SCHEMA,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='evaluation_budget'");
          if (!waits.isEmpty()) {
            System.out.println("LOCK_WAIT " + Payloads.json(waits));
            found = true;
            break;
          }
          Thread.sleep(20);
        }
        if (!found) throw new AssertionError("no actual evaluation lock wait");
      } finally {
        blocker.rollback();
      }
      var result = future.get(8, TimeUnit.SECONDS);
      if (!result.get("scope_digest").equals(Payloads.hash((String) result.get("scope_snapshot"))))
        throw new AssertionError("binding changed");
      System.out.println("PASS actual operator run lock wait and unchanged binding");
    }
  }
}
