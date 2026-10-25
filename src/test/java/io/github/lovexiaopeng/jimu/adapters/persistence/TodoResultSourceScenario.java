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
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 基础资格实际调用／锁链；外层恢复、主错、回滚及证明错误分存。 */
public final class TodoResultSourceScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease;
  private static final List<Map<String, Object>> sql = new ArrayList<>();

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static String facts() {
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
            "session_public_event",
            "tenant_budget",
            "actor_access",
            "installation",
            "task_todo_projection")) {
      result.put(
          table,
          db
              .queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a' ORDER BY 1,2,3")
              .stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    return Payloads.json(evidenceValue(result));
  }

  private static void errors(Throwable failure, String role) {
    if (failure == null) return;
    System.out.println("ERROR_ROLE " + role);
    failure.printStackTrace(System.out);
    errorChain(failure, role, new java.util.IdentityHashMap<>());
  }

  private static void errorChain(
      Throwable failure, String role, java.util.IdentityHashMap<Throwable, Boolean> seen) {
    if (failure == null || seen.put(failure, Boolean.TRUE) != null) return;
    if (failure instanceof SQLException sqlFailure) {
      System.out.println(
          "SQLERROR "
              + Payloads.json(
                  Map.of(
                      "role",
                      role,
                      "class",
                      failure.getClass().getName(),
                      "state",
                      String.valueOf(sqlFailure.getSQLState()),
                      "vendor",
                      sqlFailure.getErrorCode())));
      errorChain(sqlFailure.getNextException(), role + ".next", seen);
    }
    errorChain(failure.getCause(), role + ".cause", seen);
    for (var suppressed : failure.getSuppressed())
      errorChain(suppressed, role + ".suppressed", seen);
  }

  private static final String STEP = "00000000-0000-0000-0000-000000000101";
  private static final String INVOCATION = "00000000-0000-0000-0000-000000000102";
  private static final String INPUT = "{\"todos\":[]}";
  private static String output;

  private static void stored() {
    db.update(
        "UPDATE task SET deadline=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
        task);
    ledger.registerWorker(
        "source-private", "source-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("source-private").orElseThrow();
    require(task.equals(lease.taskId()), "exact admitted todo task claimed without worker loop");
    output = Payloads.json(ledger.todos.input(INPUT));
    var response =
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response(
            2,
            "stored fixture",
            List.of(
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
                    "stored_1", "function", "todo_write", INPUT)),
            17,
            null);
    String responseBody = Payloads.json(response);
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest) VALUES('tenant-a',?,?,?,0,'RESPONDED','{}',?,?,?)",
        STEP,
        task,
        lease.attemptId(),
        Payloads.hash("{}"),
        responseBody,
        Payloads.hash(responseBody));
    long seq = ((Number) taskRow().get("next_event_seq")).longValue() + 1;
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest,todo_expected_revision,todo_applied_revision,todo_committed_attempt_id,todo_committed_fence,todo_event_seq,todo_committed_at) VALUES('tenant-a',?,?,?,?,0,'0:stored_1','stored_1','00000000-0000-0000-0000-000000000103','todo_write',?,?,?,?,?,'SUCCEEDED',?,?,0,1,?,?,?,'2026-10-25 18:00:00.123456')",
        INVOCATION,
        task,
        STEP,
        lease.attemptId(),
        ledger.todos.descriptor().version(),
        ledger.todos.descriptor().outputSha256(),
        lease.installationEpoch(),
        INPUT,
        Payloads.hash(INPUT),
        output,
        Payloads.hash(output),
        lease.attemptId(),
        lease.fence(),
        seq);
    db.update(
        "INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,status) VALUES('tenant-a',?,?,?,'result-source-fixture','tool.completed','SUCCEEDED')",
        task,
        seq,
        task + ":" + seq);
    db.update("UPDATE task SET next_event_seq=? WHERE tenant_id='tenant-a' AND id=?", seq, task);
    db.update(
        "UPDATE task_todo_projection SET revision=1,content_state='PRESENT',source_invocation_id=?,source_step_id=?,committed_attempt_id=?,committed_fence=?,updated_seq=?,output_digest=?,updated_at='2026-10-25 18:00:00.123456' WHERE tenant_id='tenant-a' AND task_id=?",
        INVOCATION,
        STEP,
        lease.attemptId(),
        lease.fence(),
        seq,
        Payloads.hash(output),
        task);
    System.out.println(
        "FIXTURE_PROOF "
            + Payloads.json(
                evidenceValue(
                    Map.of(
                        "invocation",
                        invocation(),
                        "projection",
                        db.queryForMap(
                            "SELECT * FROM task_todo_projection WHERE tenant_id='tenant-a' AND task_id=?",
                            task),
                        "attempt",
                        db.queryForMap(
                            "SELECT * FROM task_attempt WHERE tenant_id='tenant-a' AND id=?",
                            lease.attemptId()),
                        "step",
                        db.queryForMap(
                            "SELECT * FROM step WHERE tenant_id='tenant-a' AND id=?", STEP),
                        "events",
                        db.queryForList(
                            "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                            task)))));
  }

  private static Map<String, Object> invocation() {
    return db.queryForMap(
        "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND id=?", INVOCATION);
  }

  private static Map<String, Object> directTask, directInvocation;

  private static String direct() {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("todoResultSource", Map.class, Map.class);
      method.setAccessible(true);
      method.invoke(ledger, directTask, directInvocation);
      return "qualified";
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException error) throw error;
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void trial(
      String name, Runnable change, java.util.function.Supplier<String> call, String expected) {
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null, rollback = null, evidenceError = null;
    String raw = null;
    sql.clear();
    try {
      stored();
      change.run();
      directTask = taskRow();
      directInvocation = invocation();
      measuring = true;
      raw = call.get();
    } catch (Throwable failure) {
      primary = failure;
      errors(failure, "production-or-setup-" + name);
    } finally {
      measuring = false;
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      try {
        System.out.println("SQL " + name + " " + Payloads.json(sql));
      } catch (Throwable failure) {
        evidenceError = failure;
        errors(failure, "evidence-" + name);
      }
    }
    require(rollback == null, "UNKNOWN rollback: no replay");
    String after = facts();
    System.out.println(
        "FACTS " + Payloads.json(Map.of("name", name, "before", before, "after", after)));
    require(before.equals(after), "tenant-a twenty captured tables restored");
    System.out.println("RESTORED " + name);
    require(evidenceError == null, "separate evidence error");
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure failure
                ? failure.code()
                : primary.getClass().getName();
    require(expected.equals(code), name + " expected " + expected + " got " + code);
    var row = new LinkedHashMap<String, Object>();
    row.put("name", name);
    row.put("code", code);
    row.put("raw", raw);
    System.out.println("RESULT " + Payloads.json(row));
  }

  static Object evidenceValue(Object value) {
    if (value instanceof java.time.LocalDateTime) return bindValue(value);
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), evidenceValue(item)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(TodoResultSourceScenario::evidenceValue).toList();
    return value;
  }

  private static Object bindValue(Object value) {
    // Evidence representation only; invoke(statement, method, args) receives unchanged JDBC args.
    if (value instanceof java.time.LocalDateTime time)
      return Map.of("javaType", value.getClass().getName(), "isoValue", time.toString());
    return value;
  }

  private static Map<String, Object> taskRow() {
    return db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
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
            Connection connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object[] actual = a;
                      Object value = invoke(connection, m, actual);
                      if (value instanceof PreparedStatement statement
                          && m.getName().equals("prepareStatement")) {
                        String text = a[0].toString().replaceAll("\\s+", " ");
                        var binds = new java.util.TreeMap<Integer, Object>();
                        var setters = new java.util.TreeMap<Integer, String>();
                        var types = new java.util.TreeMap<Integer, String>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index) {
                                setters.put(index, sm.getName());
                                types.put(
                                    index, sv[1] == null ? "null" : sv[1].getClass().getName());
                                binds.put(
                                    index,
                                    sm.getName().equals("setNull") ? null : bindValue(sv[1]));
                              }
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                row.put("setters", new LinkedHashMap<>(setters));
                                row.put("bindJavaTypes", new LinkedHashMap<>(types));
                                sql.add(row);
                              }
                              Object result = invoke(statement, sm, sv);
                              if (measuring
                                  && sm.getName().equals("executeUpdate")
                                  && !sql.isEmpty()) sql.getLast().put("updateCount", result);
                              if (measuring
                                  && sm.getName().equals("getUpdateCount")
                                  && !sql.isEmpty()) sql.getLast().put("updateCount", result);
                              return result;
                            });
                      }
                      if (value instanceof java.sql.Statement statement
                          && !(value instanceof PreparedStatement)
                          && m.getName().equals("createStatement")) {
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {java.sql.Statement.class},
                            (sp, sm, sv) -> {
                              if (measuring
                                  && sm.getName().startsWith("execute")
                                  && sv != null
                                  && sv.length > 0
                                  && sv[0] instanceof String text) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text.replaceAll("\\s+", " "));
                                row.put("binds", Map.of());
                                sql.add(row);
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return value;
                    });
          }
        };
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    transactions = new DataSourceTransactionManager(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    task = args[1];
    ledger.todosEnabled = true;
    trial("direct-valid", () -> {}, TodoResultSourceScenario::direct, "OK");
    trial(
        "owner-present",
        () -> {},
        () ->
            Payloads.json(
                ledger.getTodos(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", "alice"),
                    task)),
        "OK");
    trial(
        "owner-purged",
        () -> {
          db.update(
              "UPDATE task_todo_projection SET content_state='PURGED' WHERE tenant_id='tenant-a' AND task_id=?",
              task);
          db.update(
              "UPDATE invocation SET output_snapshot=NULL WHERE tenant_id='tenant-a' AND id=?",
              INVOCATION);
        },
        () ->
            Payloads.json(
                ledger.getTodos(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", "alice"),
                    task)),
        "OK");
    trial(
        "owner-digest",
        () -> {
          db.update(
              "UPDATE task_todo_projection SET output_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND task_id=?",
              task);
          db.update(
              "UPDATE invocation SET output_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND id=?",
              INVOCATION);
        },
        () ->
            Payloads.json(
                ledger.getTodos(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", "alice"),
                    task)),
        "SNAPSHOT_CORRUPT");
    trial(
        "direct-size-zero",
        () ->
            db.update(
                "UPDATE invocation SET todo_committed_fence=todo_committed_fence+1 WHERE tenant_id='tenant-a' AND id=?",
                INVOCATION),
        TodoResultSourceScenario::direct,
        "SNAPSHOT_CORRUPT");
    trial(
        "wrong-owner",
        () -> {},
        () ->
            Payloads.json(
                ledger.getTodos(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", "carol"),
                    task)),
        "NOT_FOUND");
    trial(
        "stored-replay",
        () -> {},
        () ->
            Payloads.json(
                ledger.replaceOrResult(
                    lease,
                    STEP,
                    new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                        "0:stored_1", "stored_1", "todo_write", INPUT),
                    ledger.todos.descriptor().version(),
                    ledger.todos.descriptor().outputSha256())),
        "OK");
  }
}
