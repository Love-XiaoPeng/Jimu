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
public final class SessionHistoryStorageScenario {
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
    if (inject
        && ((mode.equals("create-insert-sql") && sql.startsWith("INSERT INTO task_session_history"))
            || (mode.contains("body-sql")
                && sql.startsWith("UPDATE task_session_history SET body_bytes=body_bytes+")))) {
      injected++;
      throw new SQLException("PRIVATE_HISTORY_STORAGE_FAILURE", "HY000", 1644);
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
            "tool",
            "tool-body-sql",
            "admission",
            "create-insert-sql",
            "create-body-sql",
            "body-zero",
            "body-negative",
            "body-underflow",
            "body-cap",
            "body-overflow",
            "body-long",
            "body-min",
            "body-missing",
            "body-state",
            "body-non-session",
            "body-sql",
            "terminal-fact")) {
      // Full old scope success repeatedly timed out in unchanged metadata/account.
      // Keep that failed attempt OPEN; this scope selection proves changed storage and
      // the actual read's first body SQL fault, without relabeling success as covered.
      if (JdbcSessionScopes.task(task) && trial.equals("tool")) continue;
      mode = trial;
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
              } else if (trial.equals("admission") || trial.startsWith("create-")) {
                inject = trial.endsWith("sql");
                var identity =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", (String) task.get("owner_actor_id"));
                var request =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                        "history storage public 🧱",
                        java.util.Arrays.asList(((String) task.get("capabilities")).split(",")),
                        List.of(),
                        new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(
                            180, 8, 20, null));
                var method =
                    JdbcLedger.class.getDeclaredMethod(
                        "submitBound",
                        io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity.class,
                        String.class,
                        io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request.class,
                        List.class,
                        List.class,
                        String.class);
                method.setAccessible(true);
                boolean scope = JdbcSessionScopes.task(task);
                var sources =
                    scope
                        ? db.queryForList(
                            "SELECT source_task_id FROM task_session_scope_target WHERE tenant_id='tenant-a' AND caller_task_id=? ORDER BY ordinal",
                            String.class,
                            id)
                        : null;
                var view =
                    (io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View)
                        method.invoke(
                            ledger,
                            identity,
                            "history-storage-trial",
                            request,
                            List.of(),
                            sources,
                            "00000000-0000-4000-8000-000000000060");
                require(!inject, "admission storage fault must throw");
                var created =
                    db.queryForMap(
                        "SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", view.taskId());
                var h =
                    db.queryForMap(
                        "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                        view.taskId());
                require(
                    h.get("binding_snapshot").equals(ledger.sessions.contract(created).binding())
                        && h.get("binding_digest")
                            .equals(Payloads.hash((String) h.get("binding_snapshot"))),
                    "admission original binding raw bytes and digest");
                var expiry =
                    ((java.time.LocalDateTime) created.get("created_at")).plusSeconds(604800);
                if (scope) {
                  var sourceExpiry =
                      db.queryForObject(
                          "SELECT MIN(h.retain_until) FROM task_session_scope_target t JOIN task_session_history h ON h.tenant_id=t.tenant_id AND h.task_id=t.source_task_id WHERE t.tenant_id='tenant-a' AND t.caller_task_id=?",
                          java.time.LocalDateTime.class,
                          view.taskId());
                  if (sourceExpiry != null && sourceExpiry.isBefore(expiry)) expiry = sourceExpiry;
                }
                require(
                    expiry.equals(h.get("retain_until")),
                    "DB created_at plus604800 and original optional scope retain cut");
                require(
                    ((Number) h.get("next_seq")).longValue() == 1
                        && h.get("content_state").equals("PRESENT"),
                    "admission defaults and original accepted event cursor retained");
                long expected =
                    io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                            (String) created.get("goal"))
                        + io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts
                            .bytes((String) created.get("request_snapshot"));
                if (scope) {
                  expected +=
                      db.queryForObject(
                          "SELECT authority_bytes FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                          Long.class,
                          view.taskId());
                  for (var target :
                      db.queryForList(
                          "SELECT target_bytes,source_task_id FROM task_session_scope_target WHERE tenant_id='tenant-a' AND caller_task_id=?",
                          view.taskId()))
                    expected +=
                        ((Number) target.get("target_bytes")).longValue()
                            + io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts
                                .bytes((String) target.get("source_task_id"));
                }
                var acceptedEvent =
                    db.queryForMap(
                        "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND fact_key='accepted-user'",
                        view.taskId());
                String acceptedRaw = (String) acceptedEvent.get("event_snapshot");
                require(
                    Payloads.hash(acceptedRaw).equals(acceptedEvent.get("event_digest"))
                        && io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts
                                .bytes(acceptedRaw)
                            == ((Number) acceptedEvent.get("event_bytes")).longValue(),
                    "actual admission accepted event original digest and bytes");
                expected += ((Number) acceptedEvent.get("event_bytes")).longValue();
                System.out.println("ADMISSION " + h + " EVENT " + acceptedEvent);
                require(
                    expected == ((Number) h.get("body_bytes")).longValue(),
                    "goal/request and original scope source plus accepted event body charges");
                require(
                    trace.stream()
                            .filter(v -> v.startsWith("INSERT INTO task_session_history"))
                            .count()
                        == 1,
                    "one atomic history INSERT SELECT");
                int insertAt = index("INSERT INTO task_session_history"),
                    bodyAt = index("UPDATE task_session_history SET body_bytes=body_bytes+"),
                    holdAt =
                        index(
                            "UPDATE tenant_budget SET history_storage_held=history_storage_held+");
                require(
                    insertAt < bodyAt && bodyAt < holdAt,
                    "actual locked/create/optional scope/holdHistory order");
                System.out.println(
                    "RESULT "
                        + trial
                        + " "
                        + Payloads.json(
                            java.util.Map.of(
                                "version",
                                h.get("history_version"),
                                "binding",
                                h.get("binding_snapshot"),
                                "digest",
                                h.get("binding_digest"),
                                "body",
                                expected,
                                "state",
                                view.state())));
              } else if (trial.equals("terminal-fact")) {
                db.update("UPDATE task SET state='FAILED' WHERE tenant_id='tenant-a' AND id=?", id);
                ledger.event(task, "terminal", "task.failed", "MODEL_INTERRUPTED");
                eventProof(id, "terminal", trial);
                System.out.println("RESULT " + trial + " " + Payloads.json("staged-terminal"));
              } else {
                var input = new java.util.HashMap<String, Object>(task);
                long delta = 0;
                if (trial.equals("body-non-session")) {
                  input.clear();
                  input.put("capabilities", "web_fetch");
                } else if (trial.equals("body-missing")) input.put("id", "missing-history-storage");
                else if (trial.equals("body-state"))
                  db.update(
                      "UPDATE task_session_history SET content_state='PURGED',body_bytes=0,cleanup_receipt='fixture',held_bytes=metadata_bytes WHERE tenant_id='tenant-a' AND task_id=?",
                      id);
                else {
                  long initial =
                      trial.equals("body-negative")
                          ? 1
                          : trial.equals("body-cap") || trial.equals("body-overflow") ? 7340032 : 0;
                  db.update(
                      "UPDATE task_session_history SET body_bytes=? WHERE tenant_id='tenant-a' AND task_id=?",
                      initial,
                      id);
                  delta =
                      switch (trial) {
                        case "body-negative", "body-underflow" -> -1L;
                        case "body-overflow" -> 1L;
                        case "body-long" -> 4294967296L;
                        case "body-min" -> Long.MIN_VALUE;
                        default -> 0L;
                      };
                }
                inject = trial.equals("body-sql");
                ledger.sessions.body(input, delta);
                require(!inject, "direct body fault must throw");
                if (trial.equals("body-non-session"))
                  require(trace.isEmpty(), "non-session body skips key and SQL");
                else
                  require(
                      ((Number)
                                  db.queryForMap(
                                          "SELECT body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                                          id)
                                      .get("body_bytes"))
                              .longValue()
                          == (trial.equals("body-cap") ? 7340032 : 0),
                      "zero/negative conditional arithmetic preserved");
                System.out.println("RESULT " + trial + " " + Payloads.json("accounted"));
              }
            } catch (InvocationTargetException failure) {
              Throwable cause = failure.getCause();
              require(
                  trial.startsWith("create-")
                      && injected == 1
                      && cause instanceof org.springframework.dao.DataAccessException,
                  "actual admission single storage SQL fault");
              require(
                  trace.stream().anyMatch(v -> v.startsWith("INSERT INTO task(")),
                  "task inserted before history fault");
              int staged =
                  db.queryForObject(
                      "SELECT COUNT(*) FROM task_session_history h JOIN task t ON t.tenant_id=h.tenant_id AND t.id=h.task_id WHERE t.tenant_id='tenant-a' AND t.idempotency_key='history-storage-trial'",
                      Integer.class);
              require(
                  staged == (trial.equals("create-body-sql") ? 1 : 0),
                  "history INSERT staged only before body fault");
              System.out.println("RESULT " + trial + " " + Payloads.json("DataAccessException"));
            } catch (
                io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed
                    failure) {
              require(
                  trial.equals("tool-body-sql") && injected == 1,
                  "tool body SQL fault original unconfirmed boundary");
              System.out.println(
                  "RESULT " + trial + " " + Payloads.json("SessionCommitUnconfirmed"));
            } catch (org.springframework.dao.DataAccessException failure) {
              require(trial.equals("body-sql") && injected == 1, "single direct body SQL fault");
              System.out.println("RESULT " + trial + " " + Payloads.json("DataAccessException"));
            } catch (TaskFailure failure) {
              require(
                  List.of(
                              "body-underflow",
                              "body-overflow",
                              "body-long",
                              "body-min",
                              "body-missing",
                              "body-state")
                          .contains(trial)
                      && failure.status() == 429
                      && failure.code().equals("SESSION_HISTORY_LIMIT"),
                  "original conditional body rejection: " + trial);
              System.out.println("RESULT " + trial + " " + Payloads.json(failure.code()));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            } finally {
              inject = false;
              status.setRollbackOnly();
            }
            return null;
          });
      require(
          before.equals(facts(id)),
          "all captured history storage facts restored after outer rollback");
      System.out.println("TRACE " + trial + " " + Payloads.json(trace));
    }
  }

  private static int index(String prefix) {
    for (int i = 0; i < trace.size(); i++) if (trace.get(i).startsWith(prefix)) return i;
    throw new AssertionError(prefix);
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
