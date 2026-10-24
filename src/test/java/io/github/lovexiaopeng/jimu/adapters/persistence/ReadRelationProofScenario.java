package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
public final class ReadRelationProofScenario {
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
    var original = events.getFirst();
    for (String field :
        List.of(
            "tenant_id",
            "task_id",
            "record_version",
            "event_version",
            "content_state",
            "event_digest",
            "event_bytes",
            "occurred_at",
            "source_step_id",
            "source_digest",
            "reliable_event_seq",
            "committed_attempt_id")) {
      var event = new HashMap<>(original);
      Object value =
          switch (field) {
            case "record_version", "event_version", "event_bytes" -> 99L;
            case "occurred_at" -> ((LocalDateTime) original.get(field)).plusNanos(1000);
            case "reliable_event_seq" -> 0L;
            default -> "other";
          };
      event.put(field, value);
      if (field.equals("committed_attempt_id")) event.put("committed_fence", 1L);
      String result = outcome(task, event, false);
      String expected =
          field.equals("record_version") || field.equals("event_version")
              ? "409:SESSION_HISTORY_VERSION_UNSUPPORTED"
              : field.equals("content_state")
                  ? "410:SESSION_HISTORY_EXPIRED"
                  : "409:SESSION_HISTORY_CORRUPT";
      require(result.equals(expected), "stored refusal " + field + " " + result);
      System.out.println("RESULT " + field + " " + result);
    }
    var purged = new HashMap<>(original);
    purged.put("content_state", "PURGED");
    purged.put("event_snapshot", null);
    require(
        outcome(task, purged, false).equals("410:SESSION_HISTORY_EXPIRED"),
        "expired NULL body before decode");
    var malformed = new HashMap<>(original);
    malformed.put("event_snapshot", "{");
    require(
        outcome(task, malformed, false).equals("409:SESSION_HISTORY_CORRUPT"),
        "invalid body maps to corrupt");
    var noAttempt = new HashMap<>(original);
    noAttempt.put("committed_attempt_id", null);
    noAttempt.put("committed_fence", null);
    require(
        outcome(task, noAttempt, false).startsWith("OK:"),
        "original NULL attempt skips only last lookup");
    var danglingAttempt = new HashMap<>(original);
    danglingAttempt.put("committed_attempt_id", "missing");
    danglingAttempt.put("committed_fence", null);
    require(
        outcome(task, danglingAttempt, false).equals("NPE"),
        "original inconsistent nonnull attempt plus NULL fence retains unchecked boundary");
    System.out.println("RESULT dangling-attempt-null-fence NPE");
    require(
        outcome(task, original, true).equals("504:SESSION_QUERY_TIMEOUT"),
        "absolute deadline refusal");
    String afterProofs = facts();
    if (!before.equals(afterProofs)) {
      System.out.println("FACTS before " + before);
      System.out.println("FACTS after " + afterProofs);
    }
    require(
        before.equals(afterProofs),
        "proofs and all refusals leave original task event binding and accounting facts unchanged");
    System.out.println("RESULT additional expired-null/malformed/null-attempt/deadline/no-writes");
    var reads = events.stream().filter(e -> "session.read".equals(e.get("type"))).toList();
    require(reads.size() == 2, "two real reads include an earlier read endpoint without recursion");
    var read = reads.getFirst();
    storedCorruption(
        task,
        read,
        "UPDATE invocation_session_read SET read_sources_digest=SHA2('private-proof-corruption',256) WHERE tenant_id='tenant-a' AND task_id=? AND invocation_id=?",
        taskId,
        read.get("source_invocation_id"));
    System.out.println("RESULT relation:read_sources_digest 409:SESSION_HISTORY_CORRUPT");
    String duplicate =
        "[{\"seq\":0,\"event_sha256\":\""
            + "a".repeat(64)
            + "\"},{\"seq\":0,\"event_sha256\":\""
            + "a".repeat(64)
            + "\"}]";
    storedCorruption(
        task,
        read,
        "UPDATE invocation_session_read SET read_sources_snapshot=?,read_sources_bytes=?,read_sources_digest=? WHERE tenant_id='tenant-a' AND task_id=? AND invocation_id=?",
        duplicate,
        duplicate.length(),
        Payloads.hash(duplicate),
        taskId,
        read.get("source_invocation_id"));
    System.out.println("RESULT relation:duplicate-seqs-valid-digest 409:SESSION_HISTORY_CORRUPT");
    for (String field : List.of("slot", "plugin_digest")) {
      storedCorruption(
          task,
          read,
          "UPDATE invocation SET "
              + field
              + "='private-proof-corruption' WHERE tenant_id='tenant-a' AND task_id=? AND id=?",
          taskId,
          read.get("source_invocation_id"));
      System.out.println("RESULT original-invocation:" + field + " 409:SESSION_HISTORY_CORRUPT");
    }
    storedCorruption(
        task,
        read,
        "UPDATE step SET response_digest=SHA2('private-proof-corruption',256) WHERE tenant_id='tenant-a' AND task_id=? AND id=?",
        taskId,
        read.get("source_step_id"));
    System.out.println("RESULT original-step:response_digest 409:SESSION_HISTORY_CORRUPT");
    storedCorruption(
        task,
        read,
        "UPDATE session_public_event SET event_digest=SHA2('private-proof-corruption',256) WHERE tenant_id='tenant-a' AND task_id=? AND seq=?",
        taskId,
        0L);
    System.out.println("RESULT direct-endpoint:event_digest 409:SESSION_HISTORY_CORRUPT");
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                db.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          require(
              db.update(
                      "UPDATE invocation_session_read SET read_sources_digest=SHA2('private-proof-corruption',256) WHERE tenant_id='tenant-a' AND task_id=? AND invocation_id=?",
                      taskId,
                      read.get("source_invocation_id"))
                  == 1,
              "one nested original binding corrupted");
          require(
              outcome(task, read, false).equals("409:SESSION_HISTORY_CORRUPT"),
              "corrupted original read itself refuses");
          require(
              outcome(task, reads.getLast(), false).startsWith("OK:"),
              "later direct endpoint proof does not recursively verify earlier read");
        });
    System.out.println("RESULT flat-direct-endpoint nested-invalid-original-still-proves");
    require(
        before.equals(facts()),
        "all stored mutations roll back without changing task or accounting facts");
  }

  private static void storedCorruption(
      Map<String, Object> task, Map<String, Object> read, String sql, Object... args) {
    String before = facts();
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                db.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          require(db.update(sql, args) == 1, "one exact private fixture row mutated");
          String result = outcome(task, read, false);
          require(
              result.equals("409:SESSION_HISTORY_CORRUPT"),
              "stored scope proof corruption rejected " + result);
        });
    require(before.equals(facts()), "original scope proof facts restored after rollback");
  }
}
