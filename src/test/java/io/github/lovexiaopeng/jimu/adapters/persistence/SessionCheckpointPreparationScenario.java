package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 私有受理任务的真实 prepare/checkpoint 与故障外层回滚，不恢复历史全矩阵。 */
public final class SessionCheckpointPreparationScenario {
  private static JdbcTemplate db;
  private static int bodyCalls;
  private static boolean inject;
  private static int injected;
  private static String mode;
  private static final String CLOCK = "SELECT CAST('2026-10-25 00:25:00.123456' AS DATETIME(6))";
  private static final List<String> trace = new ArrayList<>();

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String facts(String task) {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "invocation_session_read",
            "invocation_session_trace",
            "invocation_session_search",
            "invocation_session_scope_query",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "model_dispatch",
            "task_attempt",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event")) {
      String column =
          table.equals("task")
              ? "id"
              : List.of(
                          "invocation_session_scope_query",
                          "task_session_scope",
                          "task_session_scope_target",
                          "step_session_scope")
                      .contains(table)
                  ? "caller_task_id"
                  : "task_id";
      result.put(
          table,
          db
              .queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a' ORDER BY 1,2,3")
              .stream()
              .map(row -> row.toString())
              .sorted()
              .toList());
    }
    result.put(
        "budget",
        db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'").toString());
    return Payloads.json(result);
  }

  private static String fixed(String sql) {
    return sql.equals("SELECT CURRENT_TIMESTAMP(6)") ? CLOCK : sql;
  }

  private static void executed(String sql) throws SQLException {
    trace.add(sql.replaceAll("\\s+", " "));
    String compact = sql.replaceAll("\\s+", "");
    if (compact.startsWith("UPDATEtask_session_historySETbody_bytes=body_bytes+")) bodyCalls++;
    if (inject
        && ((mode.equals("step-sql") && compact.startsWith("UPDATEstepSETsession_upper_seq="))
            || (mode.equals("scope-sql") && compact.startsWith("INSERTINTOstep_session_scope"))
            || (mode.equals("context-body-sql")
                && bodyCalls == 1
                && compact.startsWith("UPDATEtask_session_historySETbody_bytes=body_bytes+"))
            || (mode.equals("request-body-sql")
                && bodyCalls == (scopeMode ? 2 : 1)
                && compact.startsWith("UPDATEtask_session_historySETbody_bytes=body_bytes+")))) {
      injected++;
      throw new SQLException("PRIVATE_PREPARE_FAILURE", "HY000", 1644);
    }
  }

  private static boolean scopeMode;

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var plain =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var source =
        new DelegatingDataSource(plain) {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      if (m.getName().equals("prepareStatement")) a[0] = fixed(a[0].toString());
                      Object result = invoke(connection, m, a);
                      if (result instanceof java.sql.Statement statement
                          && (m.getName().equals("prepareStatement")
                              || m.getName().equals("createStatement"))) {
                        String prepared =
                            m.getName().equals("prepareStatement") ? a[0].toString() : null;
                        Class<?> api =
                            prepared == null ? java.sql.Statement.class : PreparedStatement.class;
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {api},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("execute")) {
                                String sql = prepared;
                                if (sql == null) {
                                  sv[0] = fixed(sv[0].toString());
                                  sql = sv[0].toString();
                                }
                                executed(sql.replaceAll("\\s+", " "));
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return result;
                    });
          }
        };
    db = new JdbcTemplate(source);
    var ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    String id = args[1];
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var initial = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    scopeMode = JdbcSessionScopes.task(initial);
    String before = facts(id);
    for (String trial :
        List.of(
            "prepared",
            "step-sql",
            "scope-sql",
            "context-body-sql",
            "request-body-sql",
            "body-cap",
            "empty",
            "role",
            "mismatch",
            "null-content",
            "request-limit",
            "request-body-limit")) {
      if (!scopeMode && (trial.equals("scope-sql") || trial.equals("context-body-sql"))) continue;
      mode = trial;
      inject = false;
      injected = 0;
      bodyCalls = 0;
      trace.clear();
      tx.execute(
          status -> {
            try {
              ledger.registerWorker(
                  "prepare-private",
                  "prepare-private",
                  ProcessHandle.current().pid(),
                  1,
                  "private",
                  "private");
              var lease = ledger.claim("prepare-private").orElseThrow();
              require(id.equals(lease.taskId()), "exact queued task claimed");
              var row =
                  db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
              String context = ledger.sessions.context(row);
              var model = ledger.modelSnapshot(lease);
              var messages =
                  List.of(
                      new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                          "user", (String) row.get("goal"), List.of(), List.of()),
                      new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                          "user", context, List.of(), List.of()));
              String body =
                  Payloads.json(
                      java.util.Map.of(
                          "messages",
                          List.of(
                              java.util.Map.of("role", "user", "content", row.get("goal")),
                              java.util.Map.of("role", "user", "content", context))));
              String request =
                  Payloads.json(
                      new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
                          2,
                          model.model(),
                          messages,
                          lease.capabilities(),
                          lease.installationEpoch(),
                          model.stream(),
                          ModelProfiles.digest(model),
                          body,
                          Payloads.hash(body)));
              var wire =
                  Payloads.read(request, com.fasterxml.jackson.databind.node.ObjectNode.class);
              if (trial.equals("empty"))
                wire.set(
                    "messages",
                    Payloads.read("[]", com.fasterxml.jackson.databind.node.ArrayNode.class));
              if (trial.equals("role"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) wire.withArray("messages").get(1))
                    .put("role", "assistant");
              if (trial.equals("mismatch"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) wire.withArray("messages").get(1))
                    .put("content", "different");
              if (trial.equals("null-content"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) wire.withArray("messages").get(1))
                    .putNull("content");
              if (trial.equals("request-limit")) wire.put("model", "a".repeat(131073));
              if (trial.equals("request-body-limit")) wire.put("requestBody", "a".repeat(65537));
              if (List.of(
                      "empty",
                      "role",
                      "mismatch",
                      "null-content",
                      "request-limit",
                      "request-body-limit")
                  .contains(trial)) {
                int start = trace.size();
                ledger.sessions.prepare(row, Payloads.json(wire), "no-insert-expected");
                throw new AssertionError("expected prepare boundary rejection at " + start);
              }
              long previousBody =
                  db.queryForObject(
                      "SELECT body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                      Long.class,
                      id);
              if (trial.equals("body-cap"))
                db.update(
                    "UPDATE task_session_history SET body_bytes=7340032 WHERE tenant_id='tenant-a' AND task_id=?",
                    id);
              trace.clear();
              bodyCalls = 0;
              inject = trial.endsWith("sql");
              var step = ledger.prepare(lease, request);
              require(!inject && !trial.equals("body-cap"), "expected storage rejection");
              var st =
                  db.queryForMap(
                      "SELECT * FROM step WHERE tenant_id='tenant-a' AND id=? AND task_id=?",
                      step.id(),
                      id);
              var h =
                  db.queryForMap(
                      "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                      id);
              require(
                  st.get("request_snapshot").equals(request)
                      && st.get("request_digest").equals(Payloads.hash(request)),
                  "request original bytes and hash");
              require(
                  ((Number) st.get("session_upper_seq")).longValue()
                          == Payloads.read(context, com.fasterxml.jackson.databind.JsonNode.class)
                              .path("upper_seq")
                              .asLong()
                      && ((Number) h.get("next_seq")).longValue()
                          == ((Number) st.get("session_upper_seq")).longValue() + 2
                      && st.get("session_context_digest").equals(Payloads.hash(context))
                      && st.get("session_binding_digest").equals(h.get("binding_digest")),
                  "upper/context/binding fields exact");
              long expected =
                  previousBody + request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
              var started =
                  db.queryForMap(
                      "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND fact_key=?",
                      id,
                      "step:" + step.id());
              String eventRaw = (String) started.get("event_snapshot");
              require(
                  Payloads.hash(eventRaw).equals(started.get("event_digest"))
                      && eventRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                          == ((Number) started.get("event_bytes")).intValue()
                      && Payloads.hash(request).equals(started.get("source_digest")),
                  "actual step.started original raw digest/length and request source proof");
              expected += ((Number) started.get("event_bytes")).longValue();
              System.out.println(
                  "EVENT "
                      + Payloads.json(
                          java.util.Map.of(
                              "raw",
                              eventRaw,
                              "sha256",
                              started.get("event_digest"),
                              "bytes",
                              started.get("event_bytes"),
                              "sourceDigest",
                              started.get("source_digest"),
                              "step",
                              step.id(),
                              "attempt",
                              started.get("committed_attempt_id"))));
              var proof = new LinkedHashMap<String, Object>();
              proof.put("upper", st.get("session_upper_seq"));
              proof.put(
                  "context", Payloads.read(context, com.fasterxml.jackson.databind.JsonNode.class));
              proof.put("binding", st.get("session_binding_digest"));
              if (scopeMode) {
                var scope =
                    db.queryForMap(
                        "SELECT * FROM step_session_scope WHERE tenant_id='tenant-a' AND step_id=? AND caller_task_id=?",
                        step.id(),
                        id);
                require(
                    context.equals(scope.get("context_snapshot"))
                        && Payloads.hash(context).equals(scope.get("context_digest"))
                        && context.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            == ((Number) scope.get("context_bytes")).intValue(),
                    "original scope context raw digest/bytes");
                var authority =
                    db.queryForObject(
                        "SELECT authority_digest FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                        String.class,
                        id);
                require(authority.equals(scope.get("authority_digest")), "exact authority");
                expected += context.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                proof.put("authority", authority);
                System.out.println(
                    "SCOPE "
                        + Payloads.json(
                            java.util.Map.of(
                                "raw",
                                context,
                                "sha256",
                                scope.get("context_digest"),
                                "bytes",
                                scope.get("context_bytes"))));
              }
              require(
                  expected == ((Number) h.get("body_bytes")).longValue(),
                  "scope context then request body charges");
              int mutations =
                  (int)
                      trace.stream()
                          .filter(
                              sql ->
                                  sql.replaceAll("\\s+", "")
                                      .startsWith("UPDATEstepSETsession_upper_seq="))
                          .count();
              require(mutations == 1, "one original step binding update");
              require(
                  ledger.checkpoint(lease).orElseThrow().id().equals(step.id()),
                  "actual checkpoint original request");
              require(
                  ledger.prepare(lease, request).id().equals(step.id()),
                  "actual prepared same request replay");
              require(
                  trace.stream()
                          .filter(
                              sql ->
                                  sql.replaceAll("\\s+", "")
                                      .startsWith("UPDATEstepSETsession_upper_seq="))
                          .count()
                      == 1,
                  "replay never repeats session prepare");
              require(
                  db.queryForObject(
                          "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='model.prepared'",
                          Integer.class,
                          id)
                      == 1,
                  "prepared reliable event once");
              System.out.println(
                  "PROOF "
                      + Payloads.json(
                          java.util.Map.of(
                              "request",
                              request,
                              "context",
                              context,
                              "contextDigest",
                              st.get("session_context_digest"))));
              System.out.println("RESULT " + trial + " " + Payloads.json(proof));
            } catch (org.springframework.dao.DataAccessException failure) {
              require(inject && injected == 1, "one injected prepared storage SQL failure");
              require(
                  trace.stream().anyMatch(sql -> sql.startsWith("INSERT INTO step")),
                  "step INSERT precedes projection fault");
              System.out.println("RESULT " + trial + " " + Payloads.json("DataAccessException"));
            } catch (TaskFailure failure) {
              String expected =
                  List.of("empty", "role", "mismatch").contains(trial)
                      ? "SESSION_BOUNDARY_CONFLICT"
                      : "SESSION_HISTORY_LIMIT";
              require(
                  List.of(
                              "body-cap",
                              "empty",
                              "role",
                              "mismatch",
                              "request-limit",
                              "request-body-limit")
                          .contains(trial)
                      && failure.code().equals(expected),
                  "original prepare rejection " + trial + " " + failure.code());
              System.out.println("RESULT " + trial + " " + Payloads.json(failure.code()));
            } catch (NullPointerException failure) {
              require(trial.equals("null-content"), "original null content failure");
              System.out.println("RESULT " + trial + " " + Payloads.json("NullPointerException"));
            } finally {
              inject = false;
              status.setRollbackOnly();
            }
            return null;
          });
      require(before.equals(facts(id)), "all captured prepare facts restored after outer rollback");
      System.out.println("TRACE " + trial + " " + Payloads.json(trace));
    }
  }
}
