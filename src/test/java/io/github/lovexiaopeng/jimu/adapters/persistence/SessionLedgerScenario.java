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

  private static String invoke(
      JdbcLedger ledger,
      ExecutionLedger.Lease lease,
      String step,
      ExecutionLedger.Call call,
      String version,
      String digest) {
    return call.tool().equals("session_event_trace")
        ? ledger.traceOrResult(lease, step, call, version, digest)
        : call.tool().equals("session_event_search")
            ? ledger.searchOrResult(lease, step, call, version, digest)
            : ledger.readOrResult(lease, step, call, version, digest);
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
    var ledgerSource =
        args.length == 4
            ? new DriverManagerDataSource(
                args[3],
                env.getRequiredProperty("jimu.db.user"),
                env.getRequiredProperty("jimu.db.password"))
            : source;
    var faultySource =
        new org.springframework.jdbc.datasource.DelegatingDataSource(ledgerSource) {
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
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    String taskId = java.util.UUID.fromString(args[1]).toString();
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", taskId);
    var records = new JdbcSessionRecords(db);
    long body = 0;
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "model_dispatch",
            "session_public_event",
            "invocation_session_search",
            "invocation_session_read")) {
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
                "event_snapshot",
                "normalized_query_snapshot",
                "read_sources_snapshot"))
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
    long actualMetadata = records.metadata(task, true);
    require(
        actualMetadata == n(h, "metadata_bytes"),
        "All stored metadata must be charged exactly: actual="
            + actualMetadata
            + " recorded="
            + n(h, "metadata_bytes"));
    if (s(h, "content_state").equals("PURGED")) {
      var storage =
          db.queryForMap(
              "SELECT state,actual FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='SESSION_HISTORY_STORAGE'",
              taskId);
      require(
          body == 0
              && n(h, "held_bytes") == actualMetadata
              && s(storage, "state").equals("SETTLED")
              && n(storage, "actual") == actualMetadata,
          "Purged body/held/reservation must equal the actual stored metadata");
    }
    if (args[2].equals("accounting")) {
      System.out.println("PASS exact body and metadata accounting");
      return;
    }
    if (args[2].equals("purge-rollback")) {
      String before =
          Payloads.json(
              db.queryForList(
                  "SELECT read_sources_snapshot,read_sources_digest,content_state FROM invocation_session_read WHERE task_id=? ORDER BY invocation_id",
                  taskId));
      var transaction =
          new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
      transaction.execute(
          status -> {
            records.purge(task);
            records.release(task);
            status.setRollbackOnly();
            return null;
          });
      require(
          before.equals(
              Payloads.json(
                  db.queryForList(
                      "SELECT read_sources_snapshot,read_sources_digest,content_state FROM invocation_session_read WHERE task_id=? ORDER BY invocation_id",
                      taskId))),
          "Rollback restores all source bodies and tombstones");
      require(
          body
              == db.queryForObject(
                  "SELECT body_bytes FROM task_session_history WHERE task_id=?",
                  Long.class,
                  taskId),
          "Rollback restores quota/body accounting");
      System.out.println("PASS purge/release transaction rollback restores sources and accounting");
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
            java.util.Arrays.asList(s(task, "capabilities").split(",")),
            n(task, "installation_epoch"));
    var call =
        new ExecutionLedger.Call(
            s(i, "slot"), s(i, "provider_id"), s(i, "tool"), s(i, "input_snapshot"));
    String version = s(i, "plugin_version"),
        digest = s(i, "plugin_digest"),
        step = s(i, "step_id"),
        output = s(i, "output_snapshot");
    long used = n(task, "tools_used");
    if (args[2].equals("search-failed-replay") || args[2].equals("trace-failed-replay")) {
      String original =
          db.queryForObject(
              "SELECT event_digest FROM session_public_event WHERE task_id=? AND seq=0",
              String.class,
              taskId);
      db.update(
          "UPDATE session_public_event SET event_digest=? WHERE task_id=? AND seq=0",
          "f".repeat(64),
          taskId);
      try {
        rejects(
            "SESSION_HISTORY_CORRUPT", () -> invoke(ledger, lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE session_public_event SET event_digest=? WHERE task_id=? AND seq=0",
            original,
            taskId);
      }
      rejects("SESSION_HISTORY_CORRUPT", () -> invoke(ledger, lease, step, call, version, digest));
      require(
          db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, taskId)
              == used + 1,
          "Failed original slot consumes once even after source repaired");
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM "
                      + (call.tool().equals("session_event_trace")
                          ? "invocation_session_trace"
                          : "invocation_session_search")
                      + " WHERE task_id=? AND outcome='FAILED' AND failure_code='SESSION_HISTORY_CORRUPT' AND output_bytes=0",
                  Long.class,
                  taskId)
              == 1,
          "Failure binding retains safe code and no output");
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM event WHERE task_id=? AND type='tool.failed' AND code='SESSION_HISTORY_CORRUPT'",
                  Long.class,
                  taskId)
              == 1,
          "Failure has one canonical reliable receipt");
      if (call.tool().equals("session_event_trace")) {
        ledger.sessionTraceEnabled(false);
        rejects("SESSION_TRACE_DISABLED", () -> invoke(ledger, lease, step, call, version, digest));
      } else {
        ledger.sessionSearchEnabled(false);
        rejects(
            "SESSION_SEARCH_DISABLED", () -> invoke(ledger, lease, step, call, version, digest));
      }
      System.out.println(
          "PASS failed search replay, no rescan after repair, one charge/receipt, current flag rechecked");
      return;
    }
    if (args[2].equals("connection-timeout")) {
      long started = System.nanoTime();
      String code;
      try {
        invoke(ledger, lease, step, call, version, digest);
        throw new AssertionError("Expected connection/transaction deadline");
      } catch (TaskFailure failure) {
        require(
            failure.status() == 504 && failure.code().equals("SESSION_QUERY_TIMEOUT"),
            "Safe deadline error");
        code = failure.code();
      } catch (
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed failure) {
        code = "SESSION_COMMIT_UNCONFIRMED";
      }
      long millis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
      require(
          millis >= 1800 && millis < 2700, "Whole connection/transaction budget elapsed=" + millis);
      System.out.println("PASS connection deadline elapsed_ms=" + millis + " code=" + code);
      return;
    }
    if (args[2].startsWith("uncertain-")) {
      String point = args[2].substring("uncertain-".length());
      fault.set(point);
      try {
        invoke(ledger, lease, step, call, version, digest);
        throw new AssertionError("Expected dedicated commit-unconfirmed");
      } catch (
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed expected) {
        require(
            db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, taskId)
                == (point.equals("before") ? 0L : 1L),
            "Commit fault must preserve actual commit side");
      }
      String recovered = invoke(ledger, lease, step, call, version, digest);
      require(
          recovered.equals(invoke(ledger, lease, step, call, version, digest)),
          "Uncertain recovery returns identical output bytes");
      require(
          db.queryForObject(
                  !call.tool().equals("session_event_read")
                      ? "SELECT COUNT(*) FROM event WHERE task_id=? AND type='tool.completed'"
                      : "SELECT COUNT(*) FROM session_public_event WHERE task_id=? AND type='session.read'",
                  Long.class,
                  taskId)
              == 1,
          "Uncertain recovery appends one receipt");
      System.out.println(
          "PASS commit-unconfirmed " + point + ", original binding and one result/charge/receipt");
      return;
    }
    if (args[2].equals("replay")) {
      // The checkpoint read itself advances task revision. At a decimal-width boundary that
      // metadata byte must be accounted in the same transaction, even with no subsequent event.
      long revision = db.queryForObject("SELECT revision FROM task WHERE id=?", Long.class, taskId);
      require(revision >= 10 && revision < 100, "Fixture starts in the two-digit revision range");
      db.update("UPDATE task SET revision=99 WHERE id=?", taskId);
      ledger.checkpoint(lease);
      require(
          records.metadata(task, true)
              == db.queryForObject(
                  "SELECT metadata_bytes FROM task_session_history WHERE task_id=?",
                  Long.class,
                  taskId),
          "Read-only checkpoint accounts 99-to-100 metadata width immediately");
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
            output.equals(invoke(ledger, lease, step, call, version, digest)),
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
          output.equals(invoke(ledger, lease, step, call, version, digest)),
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
      rejects("FENCE_REJECTED", () -> invoke(ledger, stale, step, call, version, digest));
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
      rejects("INVOCATION_MISMATCH", () -> invoke(ledger, actor, step, call, version, digest));
      rejects("PLUGIN_PIN_MISMATCH", () -> invoke(ledger, lease, step, call, "9.9.9", digest));
      rejects(
          "NOT_FOUND",
          () ->
              invoke(ledger, lease, java.util.UUID.randomUUID().toString(), call, version, digest));
      rejects(
          "NOT_FOUND",
          () ->
              invoke(
                  ledger,
                  lease,
                  step,
                  new ExecutionLedger.Call(
                      "9:forged", call.providerId(), call.tool(), call.input()),
                  version,
                  digest));
      rejects(
          "INVOCATION_MISMATCH",
          () ->
              invoke(
                  ledger,
                  lease,
                  step,
                  new ExecutionLedger.Call(
                      call.slot(), "forged-provider", call.tool(), call.input()),
                  version,
                  digest));
      rejects(
          "INVOCATION_MISMATCH",
          () ->
              invoke(
                  ledger,
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
            "SESSION_HISTORY_EXPIRED", () -> invoke(ledger, lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE task_session_history SET retain_until=? WHERE task_id=?",
            h.get("retain_until"),
            taskId);
      }
      rejects(
          call.tool().equals("session_event_trace")
              ? "SESSION_TRACE_COMMIT_REQUIRED"
              : call.tool().equals("session_event_search")
                  ? "SESSION_SEARCH_COMMIT_REQUIRED"
                  : "SESSION_READ_COMMIT_REQUIRED",
          () -> ledger.admit(lease, step, call, version, digest));
      rejects(
          call.tool().equals("session_event_trace")
              ? "SESSION_TRACE_COMMIT_REQUIRED"
              : call.tool().equals("session_event_search")
                  ? "SESSION_SEARCH_COMMIT_REQUIRED"
                  : "SESSION_READ_COMMIT_REQUIRED",
          () -> ledger.toolSucceeded(lease, s(i, "id"), output));
      rejects(
          call.tool().equals("session_event_trace")
              ? "SESSION_TRACE_COMMIT_REQUIRED"
              : call.tool().equals("session_event_search")
                  ? "SESSION_SEARCH_COMMIT_REQUIRED"
                  : "SESSION_READ_COMMIT_REQUIRED",
          () -> ledger.uncertain(lease, s(i, "id")));
      rejects(
          call.tool().equals("session_event_trace")
              ? "SESSION_TRACE_COMMIT_REQUIRED"
              : call.tool().equals("session_event_search")
                  ? "SESSION_SEARCH_COMMIT_REQUIRED"
                  : "SESSION_READ_COMMIT_REQUIRED",
          () -> ledger.toolFailed(lease, s(i, "id"), "SYNTHETIC"));
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
      try {
        rejects("ACCESS_REVOKED", () -> invoke(ledger, lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
      }
      db.update(
          "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_event_read'");
      try {
        rejects("CAPABILITY_REVOKED", () -> invoke(ledger, lease, step, call, version, digest));
      } finally {
        db.update(
            "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='session_event_read'");
      }
      ledger.sessionReadEnabled(false, List.of());
      rejects("SESSION_READ_DISABLED", () -> invoke(ledger, lease, step, call, version, digest));
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
