package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
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
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 私有真实MySQL：每个实际写点/末端读点失败后核对全租户行；不靠外层事务掩盖漏提交。 */
public final class SessionQueryBudgetScenario {
  private static boolean measuring, registered;
  private static int failAt, injected;
  private static final List<String> statements = new ArrayList<>();
  private static JdbcTemplate db;
  private static JdbcLedger ledger;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String snapshot() {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "task_attempt",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "task_session_lineage",
            "invocation_session_lineage_trace",
            "invocation_session_read",
            "invocation_session_scope_query",
            "invocation_session_search",
            "invocation_session_trace",
            "model_dispatch",
            "tenant_budget")) {
      result.put(
          table,
          db.queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a'").stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    return Payloads.json(result);
  }

  private static String response(List<Call> calls, ModelUsage usage) {
    return Payloads.json(
        new DurableAgent.Response(
            2,
            "controlled",
            calls.stream()
                .map(
                    call ->
                        new DurableAgent.ToolCall(
                            call.providerId(), "function", call.tool(), call.input()))
                .toList(),
            150,
            usage));
  }

  private static List<String> trial(String name, int target, Runnable action) {
    String before = snapshot();
    statements.clear();
    registered = false;
    injected = 0;
    failAt = target;
    measuring = true;
    Throwable caught = null;
    try {
      action.run();
    } catch (RuntimeException failure) {
      caught = failure;
    } finally {
      measuring = false;
    }
    check(caught != null, "Fault reached: " + name);
    if (target == -1)
      check(
          caught instanceof TaskFailure f && f.code().equals("PRIVATE_BEFORE_COMMIT"),
          "owning boundary reached: " + caught);
    else {
      boolean original = false;
      for (Throwable cause = caught; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql
            && sql.getErrorCode() == 1644
            && "HY000".equals(sql.getSQLState())) original = true;
      }
      check(original && injected == 1, "One original injected SQL failure: " + caught);
    }
    check(
        before.equals(snapshot()),
        "No partial row, event, balance, binding or counter committed: " + name);
    System.out.println(
        "FAULT "
            + Payloads.json(
                Map.of(
                    "name",
                    name,
                    "target",
                    target,
                    "injected",
                    injected,
                    "rollbackExact",
                    true,
                    "statements",
                    List.copyOf(statements))));
    return List.copyOf(statements);
  }

  private static void failures(String phase, Runnable renew, Runnable action) {
    renew.run();
    var plan = trial(phase + "-beforeCommit", -1, action);
    int writes = 0;
    for (int index = 0; index < plan.size(); index++) {
      String sql = plan.get(index);
      if (sql.startsWith("INSERT ")
          || sql.startsWith("UPDATE ")
          || sql.startsWith("DELETE ")
          || sql.contains("AS database_time")
          || sql.contains("AS reservation_state")) {
        renew.run();
        trial(phase + "-" + (index + 1), index + 1, action);
        writes++;
      }
    }
    check(writes >= 8, "Actual affected write inventory: " + phase);
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var plain =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    db = new JdbcTemplate(plain);
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
                      Object result = invoke(connection, m, a);
                      if (m.getName().equals("prepareStatement")
                          && result instanceof PreparedStatement statement) {
                        String sql = a[0].toString().replaceAll("\\s+", " ").trim();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sa) -> {
                              if (measuring && sm.getName().startsWith("execute")) {
                                statements.add(sql);
                                if (!registered
                                    && TransactionSynchronizationManager
                                        .isSynchronizationActive()) {
                                  registered = true;
                                  TransactionSynchronizationManager.registerSynchronization(
                                      new TransactionSynchronization() {
                                        public void beforeCommit(boolean readOnly) {
                                          if (failAt == -1)
                                            throw new TaskFailure(409, "PRIVATE_BEFORE_COMMIT");
                                        }
                                      });
                                }
                                if (failAt == statements.size()) {
                                  injected++;
                                  throw new SQLException(
                                      "PRIVATE_QUERY_BUDGET_FAILURE", "HY000", 1644);
                                }
                              }
                              return invoke(statement, sm, sa);
                            });
                      }
                      return result;
                    });
          }
        };
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    ledger.sessionLineageEnabled(true);
    if (args.length > 2 && args[2].equals("admission")) {
      var ids = java.util.Arrays.asList(args[1].split(","));
      String raw =
          Payloads.json(
              Map.of(
                  "goal",
                  "repair-admission-fault",
                  "history_session_ids",
                  ids,
                  "parent_session_id",
                  args[3]));
      failures(
          "admission",
          () -> {},
          () ->
              ledger.submitSessionLineage(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-a", "alice"),
                  "repair-admission-fault",
                  end -> raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  System.nanoTime() + 2_000_000_000L));
      return;
    }
    String worker = "query-budget-private";
    ledger.registerWorker(worker, worker, ProcessHandle.current().pid(), 1, "private", "private");
    var lease = ledger.claim(worker).orElseThrow();
    check(lease.taskId().equals(args[1]), "Exact queued caller");
    String context = ledger.sessionContext(lease);
    var model = ledger.modelSnapshot(lease);
    var messages =
        List.of(
            new DurableAgent.WireMessage("user", lease.goal(), List.of(), List.of()),
            new DurableAgent.WireMessage("user", context, List.of(), List.of()));
    String body =
        Payloads.json(
            Map.of(
                "messages",
                List.of(
                    Map.of("role", "user", "content", lease.goal()),
                    Map.of("role", "user", "content", context))));
    String request =
        Payloads.json(
            new DurableAgent.Checkpoint(
                2,
                model.model(),
                messages,
                lease.capabilities(),
                lease.installationEpoch(),
                model.stream(),
                ModelProfiles.digest(model),
                body,
                Payloads.hash(body)));
    Runnable renew = () -> check(ledger.renew(lease), "Still owns exact attempt/fence");
    failures("prepare", renew, () -> ledger.prepare(lease, request));
    renew.run();
    var step = ledger.prepare(lease, request);
    failures("M1", renew, () -> ledger.dispatchModel(lease, step.id()));
    renew.run();
    String dispatch = ledger.dispatchModel(lease, step.id());
    var calls =
        List.of(
            new Call("0:budget0", "budget0", "session_trace", "{}"),
            new Call("1:budget1", "budget1", "session_trace", "{}"),
            new Call("2:budget2", "budget2", "session_trace", "{}"));
    var usage = new ModelUsage(100L, 50L, 150L, null, null, null, null, null);
    failures(
        "M2",
        renew,
        () ->
            ledger.modelResponded(
                lease, step.id(), dispatch, response(calls, usage), calls, 150, usage));
    renew.run();
    var refused = List.of(new Call("0:bad", "bad", "session_trace", "{\"session_id\":\"BAD\"}"));
    try {
      ledger.modelResponded(
          lease, step.id(), dispatch, response(refused, usage), refused, 150, usage);
      throw new AssertionError("Payload refusal required");
    } catch (TaskFailure refusal) {
      check(
          db.queryForObject(
                  "SELECT actual FROM reservation WHERE tenant_id='tenant-a' AND id=?",
                  Long.class,
                  dispatch)
              == 150,
          "Refused payload keeps committed actual usage");
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM invocation WHERE task_id=?", Integer.class, lease.taskId())
              == 0,
          "No refused slots");
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM event WHERE task_id=? AND type='model.rejected'",
                  Integer.class,
                  lease.taskId())
              == 1,
          "One committed refusal audit");
      System.out.println(
          "REFUSAL "
              + Payloads.json(
                  Map.of("code", refusal.code(), "usageCommitted", true, "auditCommitted", true)));
    }
    var row =
        db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", lease.taskId());
    long exact = ledger.sessions.metadata(row, true);
    check(
        db.queryForObject(
                "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                Long.class,
                lease.taskId())
            == exact,
        "Independent original metadata scan equals final stored bytes");
    System.out.println("RECOUNT " + exact);
    ledger.stop(lease, "PRIVATE_PROBE_COMPLETE");
    ledger.quiescent(lease);
    ledger.processStopped(worker);
    System.out.println("COMPLETE exact real write-point rollback probes");
  }
}
