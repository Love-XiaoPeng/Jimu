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
public final class ReceiptProofScenario {
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
    var row =
        db.queryForMap(
            "SELECT * FROM "
                + (scoped ? "invocation_session_scope_query" : "invocation_session_read")
                + " WHERE tenant_id='tenant-a' AND "
                + (scoped ? "caller_task_id" : "task_id")
                + "=? AND invocation_id=?",
            taskId,
            read.get("source_invocation_id"));
    String receipt =
        Payloads.json(scoped ? ledger.sessions.scopeReceipt(row) : ledger.sessions.receipt(row));
    var snapshot =
        Payloads.read(
            read.get("event_snapshot").toString(), com.fasterxml.jackson.databind.JsonNode.class);
    require(
        Payloads.read(receipt, com.fasterxml.jackson.databind.JsonNode.class)
            .equals(snapshot.get("data")),
        "actual constructed receipt equals frozen event read value");
    System.out.println("RESULT receipt-bytes " + Payloads.hash(receipt));
    if (scoped) {
      String raw = row.get("normalized_snapshot").toString();
      require(
          Payloads.hash(raw).equals(row.get("normalized_digest")),
          "original normalized raw digest");
      require(
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(raw)
              == ((Number) row.get("normalized_bytes")).longValue(),
          "original normalized raw byte proof");
      System.out.println(
          "RESULT normalized-slot " + Payloads.hash(raw) + ":" + row.get("normalized_bytes"));
    }
    System.out.println("RESULT receipt-nulls " + receipt.contains("\"code\":null"));
    var projected = scoped ? ledger.sessions.scopeReceipt(row) : ledger.sessions.receipt(row);
    System.out.println("RESULT receipt-keys " + projected.keySet());
    System.out.println(
        "RESULT receipt-types "
            + projected.values().stream()
                .map(v -> v == null ? "NULL" : v.getClass().getSimpleName())
                .toList());
    for (String field : List.of("failure_code", "outcome", "output_digest")) {
      var changed = new HashMap<>(row);
      changed.put(field, null);
      var value = scoped ? ledger.sessions.scopeReceipt(changed) : ledger.sessions.receipt(changed);
      System.out.println("RESULT nullable:" + field + " " + Payloads.hash(Payloads.json(value)));
    }
    var missing = new HashMap<>(row);
    missing.put("output_bytes", null);
    try {
      if (scoped) ledger.sessions.scopeReceipt(missing);
      else ledger.sessions.receipt(missing);
      throw new AssertionError("original NULL output byte boundary");
    } catch (NullPointerException expected) {
      System.out.println("RESULT null-output-bytes NPE");
    }
    require(
        before.equals(facts()),
        "receipt probes leave complete task/event/history/accounting facts unchanged");
  }
}
