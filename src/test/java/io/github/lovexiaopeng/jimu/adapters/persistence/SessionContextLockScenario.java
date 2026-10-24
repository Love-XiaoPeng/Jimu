package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deterministic private budget-lock contention; the blocker survives the bounded query. */
public final class SessionContextLockScenario {
  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", args[1]);
    var lease =
        new ExecutionLedger.Lease(
            "tenant-a",
            (String) task.get("owner_actor_id"),
            args[1],
            (String) task.get("current_attempt_id"),
            ((Number) task.get("fence")).longValue(),
            (String) task.get("worker_id"),
            ((LocalDateTime) task.get("deadline")).toInstant(ZoneOffset.UTC),
            (String) task.get("goal"),
            Arrays.asList(((String) task.get("capabilities")).split(",")),
            ((Number) task.get("installation_epoch")).longValue());
    var ledger = new JdbcLedger(source, new ModelProfiles(env));
    ledger.sessionReadEnabled(true, java.util.List.of());
    ledger.priorSessionSearchEnabled(true);
    if (args.length > 3 && args[3].equals("warm")) {
      ledger.sessionContext(lease);
      System.out.println("PASS same lease uncontended context before contention measurement");
    }
    try (var holder = source.getConnection()) {
      holder.setAutoCommit(false);
      try {
        try (var statement = holder.createStatement();
            var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
          result.next();
          System.out.println("BLOCKER connection=" + result.getLong(1));
        }
        try (var statement = holder.createStatement();
            var result =
                statement.executeQuery(
                    "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")) {
          if (!result.next()) throw new AssertionError("Private tenant missing");
        }
        long started = System.nanoTime();
        try {
          ledger.sessionContext(lease);
          throw new AssertionError("Expected bounded lock rejection");
        } catch (org.springframework.dao.CannotAcquireLockException original) {
          if (!args[2].equals("original")) throw original;
          System.out.println(
              "REPRODUCED original raw CannotAcquireLockException; NOT product pass");
        } catch (TaskFailure mapped) {
          if (!args[2].equals("fixed")
              || mapped.status() != 504
              || !mapped.code().equals("SESSION_QUERY_TIMEOUT")
              || mapped.getCause() != null)
            throw new AssertionError("Expected sanitized 504", mapped);
          System.out.println("PASS sanitized 504 SESSION_QUERY_TIMEOUT without SQL cause");
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        if (elapsed < 900 || elapsed > 2300) throw new AssertionError("elapsed=" + elapsed);
        System.out.println("PASS bounded lock query elapsedMs=" + elapsed);
        try (var statement = holder.createStatement();
            var result = statement.executeQuery("SELECT 1")) {
          if (!result.next()) throw new AssertionError("Blocker was cancelled by query");
        }
      } finally {
        holder.rollback();
      }
    }
    ledger.sessionContext(lease);
    System.out.println("PASS same lease context after blocker rollback");
  }
}
