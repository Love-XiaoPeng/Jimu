package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Fault assertions against real persisted original slots, launched by the foreground test driver.
 */
public final class SessionLedgerScenario {
  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void rejects(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("Expected " + code);
    } catch (TaskFailure failure) {
      require(failure.code().equals(code), "Expected " + code + " got " + failure.code());
    }
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      properties.load(stream);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("runtime", properties));
    var models = new ModelProfiles(env);
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var fault = new java.util.concurrent.atomic.AtomicReference<String>();
    var faultySource =
        new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
          @Override
          public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, values) -> {
                      if (method.getName().equals("commit")) {
                        String point = fault.getAndSet(null);
                        if (point != null) {
                          if (point.equals("before")) connection.rollback();
                          else connection.commit();
                          throw new java.sql.SQLException(
                              "Synthetic lost commit acknowledgement", "08006");
                        }
                      }
                      try {
                        return method.invoke(connection, values);
                      } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }
        };
    var ledger = new JdbcLedger(faultySource, models);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    String taskId = java.util.UUID.fromString(args[1]).toString();
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", taskId);
    var records = new JdbcSessionRecords(db);
    long body = 0;
    for (String table :
        List.of("task", "step", "invocation", "model_dispatch", "session_public_event")) {
      for (var row :
          db.queryForList(
              "SELECT * FROM "
                  + table
                  + " WHERE tenant_id='tenant-a' AND "
                  + (table.equals("task") ? "id" : "task_id")
                  + "=?",
              taskId)) {
        for (String field :
            List.of(
                "goal",
                "request_snapshot",
                "summary",
                "response_snapshot",
                "input_snapshot",
                "output_snapshot",
                "usage_snapshot",
                "event_snapshot"))
          if (row.get(field) != null)
            body += s(row, field).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      }
    }
    var h =
        db.queryForMap(
            "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", taskId);
    require(
        body == n(h, "body_bytes"),
        "All stored body copies must be charged exactly: actual="
            + body
            + " recorded="
            + n(h, "body_bytes"));
    require(
        records.metadata(task, true) == n(h, "metadata_bytes"),
        "All stored metadata must be charged exactly");
    if (args[2].equals("accounting")) {
      System.out.println("PASS exact body and metadata accounting");
      return;
    }
    var i =
        db.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY ordinal LIMIT 1",
            taskId);
    var lease =
        new ExecutionLedger.Lease(
            "tenant-a",
            s(task, "owner_actor_id"),
            taskId,
            s(task, "current_attempt_id"),
            n(task, "fence"),
            s(task, "worker_id"),
            ((LocalDateTime) task.get("deadline")).toInstant(ZoneOffset.UTC),
            s(task, "goal"),
            List.of("session_event_read"),
            n(task, "installation_epoch"));
    var call =
        new ExecutionLedger.Call(
            s(i, "slot"), s(i, "provider_id"), s(i, "tool"), s(i, "input_snapshot"));
    String version = s(i, "plugin_version"),
        digest = s(i, "plugin_digest"),
        step = s(i, "step_id"),
        output = s(i, "output_snapshot");
    long used = n(task, "tools_used");
    if (args[2].startsWith("uncertain-")) {
      String point = args[2].substring("uncertain-".length());
      fault.set(point);
      try {
        ledger.readOrResult(lease, step, call, version, digest);
        throw new AssertionError("Expected dedicated commit-unconfirmed");
      } catch (
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed expected) {
        require(
            db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, taskId)
                == (point.equals("before") ? 0L : 1L),
            "Commit fault must preserve actual commit side");
      }
      String recovered = ledger.readOrResult(lease, step, call, version, digest);
      require(
          recovered.equals(ledger.readOrResult(lease, step, call, version, digest)),
          "Uncertain recovery returns identical output bytes");
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM session_public_event WHERE task_id=? AND type='session.read'",
                  Long.class,
                  taskId)
              == 1,
          "Uncertain recovery appends one receipt");
      System.out.println(
          "PASS commit-unconfirmed " + point + ", original binding and one result/charge/receipt");
      return;
    }
    if (args[2].equals("replay")) {
      var target =
          db.queryForMap(
              "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
              taskId);
      db.update(
          "UPDATE session_public_event SET event_snapshot='{}',event_digest=?,event_bytes=2 WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
          Payloads.hash("{}"),
          taskId);
      try {
        require(
            output.equals(ledger.readOrResult(lease, step, call, version, digest)),
            "Replay must return original bytes without querying changed target");
      } finally {
        db.update(
            "UPDATE session_public_event SET event_snapshot=?,event_digest=?,event_bytes=? WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
            s(target, "event_snapshot"),
            s(target, "event_digest"),
            n(target, "event_bytes"),
            taskId);
      }
      require(
          output.equals(ledger.readOrResult(lease, step, call, version, digest)),
          "Repeated original slot replay");
      var stale =
          new ExecutionLedger.Lease(
              lease.tenant(),
              lease.actor(),
              lease.taskId(),
              lease.attemptId(),
              lease.fence() + 1,
              lease.worker(),
              lease.deadline(),
              lease.goal(),
              lease.capabilities(),
              lease.installationEpoch());
      rejects("FENCE_REJECTED", () -> ledger.readOrResult(stale, step, call, version, digest));
      var actor =
          new ExecutionLedger.Lease(
              lease.tenant(),
              "carol",
              lease.taskId(),
              lease.attemptId(),
              lease.fence(),
              lease.worker(),
              lease.deadline(),
              lease.goal(),
              lease.capabilities(),
              lease.installationEpoch());
      rejects("INVOCATION_MISMATCH", () -> ledger.readOrResult(actor, step, call, version, digest));
      rejects("PLUGIN_PIN_MISMATCH", () -> ledger.readOrResult(lease, step, call, "9.9.9", digest));
      rejects(
          "NOT_FOUND",
          () ->
              ledger.readOrResult(
                  lease, java.util.UUID.randomUUID().toString(), call, version, digest));
      rejects(
          "NOT_FOUND",
          () ->
              ledger.readOrResult(
                  lease,
                  step,
                  new ExecutionLedger.Call(
                      "9:forged", call.providerId(), call.tool(), call.input()),
                  version,
                  digest));
      rejects(
          "INVOCATION_MISMATCH",
          () ->
              ledger.readOrResult(
                  lease,
                  step,
                  new ExecutionLedger.Call(
                      call.slot(), "forged-provider", call.tool(), call.input()),
                  version,
                  digest));
      rejects(
          "INVOCATION_MISMATCH",
          () ->
              ledger.readOrResult(
                  lease,
                  step,
                  new ExecutionLedger.Call(
                      call.slot(), call.providerId(), call.tool(), call.input() + " "),
                  version,
                  digest));
      db.update(
          "UPDATE task_session_history SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id=?",
          taskId);
      try {
        rejects(
            "SESSION_HISTORY_EXPIRED",
            () -> ledger.readOrResult(lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE task_session_history SET retain_until=? WHERE task_id=?",
            h.get("retain_until"),
            taskId);
      }
      rejects(
          "SESSION_READ_COMMIT_REQUIRED", () -> ledger.admit(lease, step, call, version, digest));
      rejects(
          "SESSION_READ_COMMIT_REQUIRED", () -> ledger.toolSucceeded(lease, s(i, "id"), output));
      rejects("SESSION_READ_COMMIT_REQUIRED", () -> ledger.uncertain(lease, s(i, "id")));
      rejects(
          "SESSION_READ_COMMIT_REQUIRED", () -> ledger.toolFailed(lease, s(i, "id"), "SYNTHETIC"));
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
      try {
        rejects("ACCESS_REVOKED", () -> ledger.readOrResult(lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
      }
      db.update(
          "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_event_read'");
      try {
        rejects(
            "CAPABILITY_REVOKED", () -> ledger.readOrResult(lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='session_event_read'");
      }
      ledger.sessionReadEnabled(false, List.of());
      rejects(
          "SESSION_READ_DISABLED", () -> ledger.readOrResult(lease, step, call, version, digest));
      require(
          ledger
              .sessionHistory(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-a", "alice"),
                  taskId)
              .contains("\"readable\":true"),
          "Flag off preserves owner history");
    }
    require(
        db.queryForObject(
                "SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id=?",
                Long.class,
                taskId)
            == used,
        "Replay/rejections must not consume another tool");
    System.out.println(
        "PASS original bytes, no target requery, pin/actor/fence/revocation/flag and bypass assertions");
  }
}
