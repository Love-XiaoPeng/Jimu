package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real original-slot recovery and exact new-body accounting, using only private fixture rows. */
public final class PriorSessionLedgerScenario {
  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("Expected " + code);
    } catch (TaskFailure failure) {
      require(code.equals(failure.code()), "Expected " + code + " got " + failure.code());
    }
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var fault = new java.util.concurrent.atomic.AtomicReference<String>();
    var faulty =
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
                        String at = fault.getAndSet(null);
                        if (at != null) {
                          if (at.equals("before")) connection.rollback();
                          else connection.commit();
                          throw new java.sql.SQLException(
                              "Synthetic lost acknowledgement", "08006");
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
    var ledger = new JdbcLedger(faulty, new ModelProfiles(env));
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.priorSessionSearchEnabled(true);
    String id = java.util.UUID.fromString(args[1]).toString();
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    var records = new JdbcSessionRecords(db);
    if (args[2].equals("account")) {
      long body = 0;
      for (String table :
          List.of(
              "task",
              "step",
              "invocation",
              "model_dispatch",
              "session_public_event",
              "task_session_scope",
              "task_session_scope_target",
              "step_session_scope",
              "invocation_session_scope_query")) {
        String key =
            table.equals("task") ? "id" : table.contains("scope") ? "caller_task_id" : "task_id";
        for (var row :
            db.queryForList(
                "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?", id))
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
                  "authority_snapshot",
                  "target_snapshot",
                  "source_task_id",
                  "context_snapshot",
                  "normalized_snapshot",
                  "read_refs_snapshot"))
            if (row.get(field) != null)
              body += s(row, field).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      }
      require(
          body
              == db.queryForObject(
                  "SELECT body_bytes FROM task_session_history WHERE task_id=?", Long.class, id),
          "Every stored body copy is charged exactly");
      require(
          records.metadata(task, true)
              == db.queryForObject(
                  "SELECT metadata_bytes FROM task_session_history WHERE task_id=?",
                  Long.class,
                  id),
          "All new metadata is charged");
      System.out.println(
          "PASS exact v4 body=" + body + " metadata=" + records.metadata(task, true));
      return;
    }
    var invocation =
        args.length == 5
            ? db.queryForMap(
                "SELECT * FROM invocation WHERE task_id=? AND id=? AND step_id=?",
                id,
                java.util.UUID.fromString(args[3]).toString(),
                java.util.UUID.fromString(args[4]).toString())
            : db.queryForMap(
                "SELECT * FROM invocation WHERE task_id=? ORDER BY ordinal LIMIT 1", id);
    if (args[2].equals("read-fault"))
      require(
          args.length == 5 && s(invocation, "tool").equals("session_event_read"),
          "READ recovery binds the exact original invocation and step");
    var lease =
        new ExecutionLedger.Lease(
            "tenant-a",
            s(task, "owner_actor_id"),
            id,
            s(task, "current_attempt_id"),
            n(task, "fence"),
            s(task, "worker_id"),
            ((LocalDateTime) task.get("deadline")).toInstant(ZoneOffset.UTC),
            s(task, "goal"),
            Arrays.asList(s(task, "capabilities").split(",")),
            n(task, "installation_epoch"));
    var call =
        new ExecutionLedger.Call(
            s(invocation, "slot"),
            s(invocation, "provider_id"),
            s(invocation, "tool"),
            s(invocation, "input_snapshot"));
    String step = s(invocation, "step_id"),
        version = s(invocation, "plugin_version"),
        digest = s(invocation, "plugin_digest");
    java.util.function.Supplier<String> invoke =
        () -> ledger.scopedOrResult(lease, step, call, version, digest);
    long used = n(task, "tools_used");
    if (args[2].equals("fault") || args[2].equals("read-fault")) {
      require(
          s(invocation, "state").equals("PENDING"), "Private barrier holds original pending slot");
      for (String at : List.of("before", "after")) {
        fault.set(at);
        try {
          invoke.get();
          throw new AssertionError("Commit uncertainty expected");
        } catch (SessionCommitUnconfirmed expected) {
        }
        require(
            db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, id)
                == used + (at.equals("after") ? 1 : 0),
            "Lost acknowledgement does not double consume");
      }
      used++;
    }
    String output = invoke.get();
    require(output.equals(invoke.get()), "Successful replay is original bytes");
    require(
        db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, id) == used,
        "Replay consumes zero");
    rejects(
        "INVOCATION_MISMATCH",
        () ->
            ledger.scopedOrResult(
                lease,
                step,
                new ExecutionLedger.Call(
                    call.slot(), call.providerId(), call.tool(), "{\"query\":\"changed\"}"),
                version,
                digest));
    ledger.priorSessionSearchEnabled(false);
    rejects("SESSION_SEARCH_DISABLED", invoke::get);
    ledger.priorSessionSearchEnabled(true);
    require(output.equals(invoke.get()), "Flag rollback preserves exact original bytes");
    if (args[2].equals("revoke")) {
      var identity =
          new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
              "tenant-a", s(task, "owner_actor_id"));
      ledger.revokeSessionSearchScope(identity, id, true, System.nanoTime() + 2_000_000_000L);
      rejects("SESSION_SCOPE_UNAVAILABLE", invoke::get);
      require(ledger.get(identity, id).summary() == null, "Revoked caller summary is hidden");
    }
    System.out.println(
        "PASS original v4 slot "
            + args[2]
            + " invocation="
            + s(invocation, "id")
            + " step="
            + step
            + " replay/mismatch/flag/consumption");
  }
}
