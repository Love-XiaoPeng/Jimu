package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 旧新准确JAR在相同真实终态事实中证明所有来源；不重放worker/provider。 */
public final class StepHistoryProofScenario {
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static String taskId;
  private static boolean scoped;

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static String verify(
      Map<String, Object> task, Map<String, Object> event, boolean expired) {
    try {
      var view = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      view.setAccessible(true);
      var active =
          (JdbcLedger)
              view.invoke(
                  ledger,
                  System.nanoTime() + (expired ? 1 : 2_000_000_000L),
                  scoped ? new QueryResources(0, 0, 0) : null);
      String result =
          active.sessionTransaction(
              () -> Payloads.json(active.sessions.verify(task, event)),
              System.nanoTime() + 2_000_000_000L);
      System.out.println(
          "METER " + event.get("seq") + " " + Payloads.json(active.queryContext().resources()));
      return result;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String outcome(
      Map<String, Object> task, Map<String, Object> event, boolean expired) {
    try {
      return "OK:" + Payloads.hash(verify(task, event, expired));
    } catch (TaskFailure failure) {
      return failure.status() + ":" + failure.code();
    } catch (NullPointerException originalBoundary) {
      return "NPE";
    }
  }

  private static String facts() {
    var facts = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "invocation",
            "step",
            "task_attempt",
            "reservation",
            "event",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_scope_query",
            "step_session_scope",
            "task_session_scope",
            "task_session_history")) {
      String key =
          table.equals("task")
              ? "id"
              : List.of(
                          "invocation_session_scope_query",
                          "step_session_scope",
                          "task_session_scope")
                      .contains(table)
                  ? "caller_task_id"
                  : "task_id";
      facts.put(
          table,
          db.queryForList(
                  "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?",
                  taskId)
              .toString());
    }
    facts.put(
        "budget",
        db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'").toString());
    return Payloads.json(facts);
  }

  private static String window(Map<String, Object> task, long upper) throws Exception {
    var view = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    view.setAccessible(true);
    var active =
        (JdbcLedger)
            view.invoke(
                ledger,
                System.nanoTime() + 2_000_000_000L,
                scoped ? new QueryResources(0, 0, 0) : null);
    String output =
        active.sessionTransaction(
            () ->
                active
                    .sessions
                    .read(
                        task,
                        new io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort.Input(
                            3, null, 3, 3),
                        upper,
                        "owner-snapshot",
                        () -> active.queryContext().remainingMillis())
                    .output(),
            System.nanoTime() + 2_000_000_000L);
    System.out.println("METER window " + Payloads.json(active.queryContext().resources()));
    return output;
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
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    taskId = args[1];
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", taskId);
    scoped = JdbcSessionScopes.task(task);
    var events =
        db.queryForList(
            "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
            taskId);
    require(
        events.stream().map(e -> e.get("type")).distinct().count() == 5,
        "all five actual source types");
    String before = facts();
    // Warm the exact readonly proof, then snapshot all measured rejections/results separately.
    for (int attempt = 0; ; attempt++) {
      String result = outcome(task, events.getFirst(), false);
      if (result.startsWith("OK:")) break;
      require(
          attempt < 2 && result.equals("504:SESSION_QUERY_TIMEOUT") && before.equals(facts()),
          "bounded same-event cold retry preserves all facts");
    }
    for (var event : events) {
      String result = outcome(task, event, false);
      require(result.startsWith("OK:"), "actual source proof " + event.get("type") + " " + result);
      System.out.println("RESULT valid:" + event.get("seq") + " " + result);
      require(
          verify(task, event, false).equals(event.get("event_snapshot")),
          "exact frozen event bytes");
    }
    System.out.println("WINDOW " + window(task, events.size() - 1L));
    var read =
        events.stream().filter(e -> "session.read".equals(e.get("type"))).findFirst().orElseThrow();
    var step =
        db.queryForMap(
            "SELECT * FROM step WHERE tenant_id='tenant-a' AND task_id=? AND id=?",
            taskId,
            read.get("source_step_id"));
    result("history-readable", query(a -> history(a, task, true)), "OK:");
    result("history-metadata", query(a -> history(a, task, false)), "OK:");
    for (String key : List.of("tenant_id", "id")) {
      var wrong = new HashMap<>(task);
      wrong.put(key, "other");
      result(
          "history-key:" + key,
          query(a -> history(a, wrong, true)),
          "409:SESSION_HISTORY_UNAVAILABLE");
    }
    result("step-valid", query(a -> validate(a, task, step)), "OK");
    for (String field :
        List.of(
            "record_version",
            "task_id",
            "session_upper_seq",
            "request_digest",
            "session_context_digest",
            "session_binding_digest")) {
      var changed = new HashMap<>(step);
      changed.put(
          field,
          field.equals("record_version") ? 2L : field.equals("session_upper_seq") ? null : "bad");
      result(
          "step-field:" + field,
          query(a -> validate(a, task, changed)),
          List.of("record_version", "task_id", "session_upper_seq").contains(field)
              ? "409:SESSION_HISTORY_VERSION_UNSUPPORTED"
              : "409:SESSION_HISTORY_CORRUPT");
    }
    for (String change :
        List.of(
            "malformed",
            "version-before-null-messages",
            "null-messages",
            "empty-messages",
            "last-role")) {
      String request;
      if (change.equals("malformed")) request = "{";
      else {
        var wire =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                Payloads.read(
                    step.get("request_snapshot").toString(),
                    com.fasterxml.jackson.databind.JsonNode.class);
        if (change.equals("version-before-null-messages")) {
          wire.put("version", 3);
          wire.putNull("messages");
        }
        if (change.equals("null-messages")) wire.putNull("messages");
        if (change.equals("empty-messages")) wire.putArray("messages");
        if (change.equals("last-role"))
          ((com.fasterxml.jackson.databind.node.ObjectNode)
                  wire.path("messages").get(wire.path("messages").size() - 1))
              .put("role", "assistant");
        request = Payloads.json(wire);
      }
      var changed = new HashMap<>(step);
      changed.put("request_snapshot", request);
      changed.put("request_digest", Payloads.hash(request));
      result(
          "checkpoint:" + change,
          query(a -> validate(a, task, changed)),
          change.equals("malformed")
              ? "IAE"
              : change.equals("null-messages") ? "NPE" : "409:SESSION_HISTORY_CORRUPT");
    }
    require(before.equals(facts()), "all direct helper refusals leave original facts unchanged");
    for (boolean badBinding : List.of(false, true)) {
      var tx =
          new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                  db.getDataSource()));
      tx.executeWithoutResult(
          status -> {
            status.setRollbackOnly();
            String sql =
                badBinding
                    ? "UPDATE task_session_history SET retain_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND),binding_digest=SHA2('bad-binding',256) WHERE tenant_id='tenant-a' AND task_id=?"
                    : "UPDATE task_session_history SET retain_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_id='tenant-a' AND task_id=?";
            require(db.update(sql, taskId) == 1, "one exact private history fixture UPDATE");
            if (!badBinding) result("expired-metadata", query(a -> history(a, task, false)), "OK:");
            result(
                badBinding ? "binding-before-expiry" : "database-time-expired",
                query(a -> history(a, task, true)),
                badBinding
                    ? "409:SESSION_HISTORY_VERSION_UNSUPPORTED"
                    : "410:SESSION_HISTORY_EXPIRED");
          });
      require(
          before.equals(facts()),
          "history fixture rolled back and original accounting/event/history facts restored");
    }
  }

  private static String history(JdbcLedger active, Map<String, Object> task, boolean readable) {
    var row = active.sessions.history(task, readable);
    return "OK:" + row.get("binding_digest") + ":" + row.get("next_seq");
  }

  private static String validate(
      JdbcLedger active, Map<String, Object> task, Map<String, Object> step) {
    active.sessions.validateStep(task, step);
    return "OK";
  }

  private static void result(String label, String actual, String expected) {
    require(
        expected.endsWith(":") ? actual.startsWith(expected) : actual.equals(expected),
        label + " " + actual);
    System.out.println("RESULT " + label + " " + actual);
  }

  private static String query(java.util.function.Function<JdbcLedger, String> action) {
    try {
      var view = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      view.setAccessible(true);
      long deadline = System.nanoTime() + 2_000_000_000L;
      var active =
          (JdbcLedger) view.invoke(ledger, deadline, scoped ? new QueryResources(0, 0, 0) : null);
      return active.sessionTransaction(() -> action.apply(active), deadline);
    } catch (TaskFailure failure) {
      return failure.status() + ":" + failure.code();
    } catch (NullPointerException original) {
      return "NPE";
    } catch (IllegalArgumentException original) {
      return "IAE";
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
