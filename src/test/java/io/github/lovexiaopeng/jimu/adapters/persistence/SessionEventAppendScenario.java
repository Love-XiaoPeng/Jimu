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

/** 同一实际PENDING search槽的工具/owner和故障外层回滚，不恢复历史全矩阵。 */
public final class SessionEventAppendScenario {
  private static JdbcTemplate db;
  private static JdbcLedger active;
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
              .queryForList(
                  "SELECT * FROM "
                      + table
                      + " WHERE tenant_id='tenant-a' AND "
                      + column
                      + "=? ORDER BY 1,2,3",
                  task)
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
    if (inject
        && ((mode.equals("insert-sql") && sql.startsWith("INSERT INTO session_public_event"))
            || (mode.equals("advance-sql")
                && sql.startsWith("UPDATE task_session_history SET next_seq=next_seq+1")))) {
      injected++;
      throw new SQLException("PRIVATE_APPEND_FAILURE", "HY000", 1644);
    }
  }

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
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    var slot =
        db.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND tool='session_event_read' ORDER BY ordinal LIMIT 1",
            id);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    String before = facts(id);
    // Load the original owner/window path under its own unchanged 2s deadline. No tool is
    // invoked or retried, and this separate fixture transaction is checked fully rolled back.
    tx.execute(
        status -> {
          try {
            String output =
                ledger.sessionEvent(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", (String) task.get("owner_actor_id")),
                    id,
                    0,
                    0,
                    0);
            System.out.println("OWNERWARM " + output);
          } finally {
            status.setRollbackOnly();
          }
          return null;
        });
    require(before.equals(facts(id)), "owner window warmup restored all captured facts");
    // Exercise query plumbing/accounting once before timed tool trials; this is a separate
    // rolled-back fixture warmup, not a retry of a failed tool or a cold-path acceptance.
    tx.execute(
        status -> {
          try {
            var method =
                JdbcLedger.class.getDeclaredMethod(
                    "queryView",
                    long.class,
                    io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                        .QueryResources.class);
            method.setAccessible(true);
            long end = System.nanoTime() + 2_000_000_000L;
            var warm = (JdbcLedger) method.invoke(ledger, end, null);
            warm.sessionTransaction(
                () -> {
                  warm.sessions.account(task, false);
                  return null;
                },
                end);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          } finally {
            status.setRollbackOnly();
          }
          return null;
        });
    require(before.equals(facts(id)), "query/account warmup restored all captured facts");
    if (JdbcSessionScopes.task(task)) {
      String normalized =
          db.queryForObject(
              "SELECT normalized_snapshot FROM invocation_session_scope_query WHERE tenant_id='tenant-a' AND caller_task_id=? AND invocation_id=?",
              String.class,
              id,
              slot.get("id"));
      tx.execute(
          status -> {
            try {
              var method =
                  JdbcLedger.class.getDeclaredMethod(
                      "queryView",
                      long.class,
                      io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                          .QueryResources.class);
              method.setAccessible(true);
              long end = System.nanoTime() + 2_000_000_000L;
              var warm =
                  (JdbcLedger)
                      method.invoke(
                          ledger,
                          end,
                          new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                              .QueryResources(0, 0, 0));
              String output =
                  warm.sessionTransaction(
                      () ->
                          warm.sessions
                              .scopeRead(
                                  task, normalized, () -> warm.queryContext().remainingMillis())
                              .output(),
                      end);
              System.out.println("SCOPEWARM " + output);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            } finally {
              status.setRollbackOnly();
            }
            return null;
          });
      require(before.equals(facts(id)), "scope execution warmup restored all captured facts");
    }
    for (String trial :
        List.of(
            "accepted-duplicate",
            "step-duplicate",
            "model-duplicate",
            "fresh",
            "duplicate",
            "ordinary125",
            "ordinary126",
            "terminal126",
            "terminal127",
            "terminal128",
            "body-limit",
            "insert-sql",
            "advance-sql",
            "tool",
            "tool-insert-sql",
            "tool-advance-sql",
            "terminal-fact")) {
      mode = trial.replace("tool-", "");
      inject = false;
      injected = 0;
      trace.clear();
      tx.execute(
          status -> {
            try {
              if (trial.startsWith("tool")) {
                db.update(
                    "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND,deadline=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND WHERE tenant_id='tenant-a' AND id=?",
                    id);
                var row =
                    db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
                var lease =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
                        "tenant-a",
                        (String) row.get("owner_actor_id"),
                        id,
                        (String) row.get("current_attempt_id"),
                        ((Number) row.get("fence")).longValue(),
                        (String) row.get("worker_id"),
                        ((java.time.LocalDateTime) row.get("deadline"))
                            .toInstant(java.time.ZoneOffset.UTC),
                        (String) row.get("goal"),
                        java.util.Arrays.asList(((String) row.get("capabilities")).split(",")),
                        ((Number) row.get("installation_epoch")).longValue());
                var method =
                    JdbcLedger.class.getDeclaredMethod(
                        "queryView",
                        io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
                        boolean.class);
                method.setAccessible(true);
                boolean scope = JdbcSessionScopes.task(row);
                active = (JdbcLedger) method.invoke(ledger, lease, scope);
                inject = trial.contains("sql");
                var call =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                        (String) slot.get("slot"),
                        (String) slot.get("provider_id"),
                        (String) slot.get("tool"),
                        (String) slot.get("input_snapshot"));
                String output =
                    scope
                        ? active.scopedOrResult(
                            lease,
                            (String) slot.get("step_id"),
                            call,
                            (String) slot.get("plugin_version"),
                            (String) slot.get("plugin_digest"))
                        : active.readOrResult(
                            lease,
                            (String) slot.get("step_id"),
                            call,
                            (String) slot.get("plugin_version"),
                            (String) slot.get("plugin_digest"));
                require(!inject, "tool append fault must throw");
                require(
                    "SUCCEEDED"
                        .equals(
                            db.queryForObject(
                                "SELECT state FROM invocation WHERE tenant_id='tenant-a' AND id=?",
                                String.class,
                                slot.get("id"))),
                    "real read settlement staged");
                eventProof(id, "read:" + slot.get("id"), trial);
                System.out.println("RESULT " + trial + " " + output);
              } else if (trial.endsWith("-duplicate")) {
                String fact =
                    trial.startsWith("accepted")
                        ? "accepted"
                        : (trial.startsWith("step") ? "prepared:" : "response:")
                            + slot.get("step_id");
                var method =
                    JdbcLedger.class.getDeclaredMethod(
                        "sessionFact", java.util.Map.class, String.class);
                method.setAccessible(true);
                method.invoke(ledger, task, fact);
                require(
                    trace.stream()
                        .noneMatch(
                            s ->
                                s.equals(CLOCK)
                                    || s.startsWith("INSERT INTO session_public_event")),
                    "real sessionFact duplicate skips clock and append");
                System.out.println("RESULT " + trial + " " + Payloads.json("idempotent"));
              } else if (trial.equals("terminal-fact")) {
                db.update("UPDATE task SET state='FAILED' WHERE tenant_id='tenant-a' AND id=?", id);
                ledger.event(task, "terminal", "task.failed", "MODEL_INTERRUPTED");
                eventProof(id, "terminal", trial);
                System.out.println("RESULT " + trial + " " + Payloads.json("staged-terminal"));
              } else {
                boolean terminal = trial.startsWith("terminal");
                if (trial.matches("(ordinary|terminal)[0-9]+"))
                  db.update(
                      "UPDATE task_session_history SET next_seq=? WHERE tenant_id='tenant-a' AND task_id=?",
                      Long.parseLong(trial.replaceAll("[^0-9]", "")),
                      id);
                if (trial.equals("body-limit"))
                  db.update(
                      "UPDATE task_session_history SET body_bytes=7340032 WHERE tenant_id='tenant-a' AND task_id=?",
                      id);
                String fact = "append-test:" + trial;
                var data = new LinkedHashMap<String, Object>();
                if (terminal) {
                  data.put("state", "FAILED");
                  data.put("code", "MODEL_INTERRUPTED");
                } else data.put("content", "append public 🧱");
                inject = trial.endsWith("sql");
                long seq =
                    ledger.sessionAppend(
                        task,
                        fact,
                        terminal ? "turn.ended" : "user.message",
                        ledger.sessions.source(terminal ? "task" : "request", null, null),
                        data,
                        false,
                        Payloads.hash("source"),
                        null);
                require(!inject, "direct append fault must throw");
                eventProof(id, fact, trial);
                if (trial.equals("duplicate")) {
                  var old =
                      db.queryForMap(
                          "SELECT next_seq,body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                          id);
                  trace.clear();
                  long again =
                      ledger.sessionAppend(
                          task, fact, "user.message", null, null, false, null, null);
                  require(again == seq, "duplicate returns first sequence");
                  require(
                      old.equals(
                          db.queryForMap(
                              "SELECT next_seq,body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                              id)),
                      "duplicate no body/cursor change");
                  require(
                      trace.stream()
                          .noneMatch(
                              s ->
                                  s.equals(CLOCK)
                                      || s.startsWith("INSERT INTO session_public_event")),
                      "duplicate skips clock/refs/body/insert");
                  require(
                      trace.stream().anyMatch(s -> s.contains("metadata_bytes")),
                      "caller account still executes");
                }
                System.out.println("RESULT " + trial + " " + Payloads.json(seq));
              }
            } catch (
                io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed
                    failure) {
              require(
                  trial.startsWith("tool-") && injected == 1,
                  "tool SQL fault original unconfirmed boundary");
              stagedFault(id, trial);
              System.out.println(
                  "RESULT " + trial + " " + Payloads.json("SessionCommitUnconfirmed"));
            } catch (org.springframework.dao.DataAccessException failure) {
              require(!trial.startsWith("tool") && injected == 1, "single direct append SQL fault");
              stagedFault(id, trial);
              System.out.println("RESULT " + trial + " " + Payloads.json("DataAccessException"));
            } catch (TaskFailure failure) {
              require(
                  List.of("ordinary126", "terminal128", "body-limit").contains(trial)
                      && failure.code().equals("SESSION_HISTORY_LIMIT"),
                  "original append 429 boundary: " + trial + "/" + failure.code());
              System.out.println("RESULT " + trial + " " + Payloads.json(failure.code()));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            } finally {
              inject = false;
              status.setRollbackOnly();
            }
            return null;
          });
      require(before.equals(facts(id)), "all captured append facts restored after outer rollback");
      System.out.println("TRACE " + trial + " " + Payloads.json(trace));
    }
  }

  private static void stagedFault(String id, String trial) {
    if (trial.contains("advance"))
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND (fact_key LIKE 'append-test:%' OR fact_key LIKE 'read:%')",
                  Integer.class, id)
              > 0,
          "event INSERT staged before cursor fault");
    require(
        trace.stream()
            .anyMatch(s -> s.startsWith("UPDATE task_session_history SET body_bytes=body_bytes+")),
        "body increment attempted before append fault");
  }

  private static void eventProof(String id, String fact, String trial) {
    var row =
        db.queryForMap(
            "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND fact_key=?",
            id,
            fact);
    String raw = (String) row.get("event_snapshot");
    require(
        Payloads.hash(raw).equals(row.get("event_digest"))
            && raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                == ((Number) row.get("event_bytes")).intValue(),
        "original frozen event digest and bytes");
    require(
        raw.contains("2026-10-25T00:25:00.123456Z"), "database microsecond timestamp preserved");
    if (!trial.equals("tool"))
      require(
          row.get("committed_attempt_id") == null && row.get("committed_fence") == null,
          "null lease explicit NULL attempt/fence");
    var proof = new LinkedHashMap<String, Object>();
    for (var entry : row.entrySet())
      proof.put(
          entry.getKey(),
          entry.getValue() instanceof java.time.LocalDateTime t ? t.toString() : entry.getValue());
    System.out.println("EVENT " + trial + " " + Payloads.json(proof));
  }
}
