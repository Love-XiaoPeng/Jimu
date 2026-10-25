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
public final class QuestionAccessScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
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
            "user_question")) {
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

  private static Map<String, Object> directTask;

  private static String direct() {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("questionAccess", Map.class);
      method.setAccessible(true);
      method.invoke(ledger, directTask);
      return "qualified";
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException error) throw error;
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String owner() {
    return Payloads.json(
        ledger.listQuestions(
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
            task));
  }

  private static String replay() {
    var request =
        Payloads.read(
            (String) directTask.get("request_snapshot"),
            io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request.class);
    var view =
        ledger.submit(
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
            (String) directTask.get("idempotency_key"),
            request);
    System.out.println("REPLAY_VIEW " + view);
    return Payloads.json(
        Map.of("taskId", view.taskId(), "state", view.state(), "revision", view.revision()));
  }

  private static void trial(
      String name, Runnable change, java.util.function.Supplier<String> call, String expected) {
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null, rollback = null, evidenceError = null;
    String raw = null;
    sql.clear();
    try {
      change.run();
      directTask = taskRow();
      System.out.println(
          "CHECK_PROOF "
              + Payloads.json(
                  evidenceValue(
                      Map.of(
                          "name",
                          name,
                          "task",
                          directTask,
                          "installation",
                          db.queryForList(
                              "SELECT * FROM installation WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'")))));
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
      return list.stream().map(QuestionAccessScenario::evidenceValue).toList();
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
                              if (measuring
                                  && result instanceof java.sql.ResultSet resultSet
                                  && (text.equals(
                                          "SELECT * FROM installation WHERE tenant_id=? AND plugin_id='ask_user_question'")
                                      || text.equals(
                                          "SELECT * FROM installation WHERE (tenant_id = ? AND plugin_id = ?)"))) {
                                var metadata = resultSet.getMetaData();
                                var columns = new ArrayList<String>();
                                for (int column = 1; column <= metadata.getColumnCount(); column++)
                                  columns.add(metadata.getColumnLabel(column));
                                var readRows = new ArrayList<Map<String, Object>>();
                                var readTypes = new ArrayList<Map<String, String>>();
                                var readMethods = new ArrayList<Map<String, String>>();
                                sql.getLast().put("sourceColumns", columns);
                                sql.getLast().put("sourceReadRows", readRows);
                                sql.getLast().put("sourceReadTypes", readTypes);
                                sql.getLast().put("sourceGetterMethods", readMethods);
                                return Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {java.sql.ResultSet.class},
                                    (rp, rm, ra) -> {
                                      Object field = invoke(resultSet, rm, ra);
                                      if (rm.getName().equals("next")
                                          && Boolean.TRUE.equals(field)) {
                                        readRows.add(new LinkedHashMap<>());
                                        readTypes.add(new LinkedHashMap<>());
                                        readMethods.add(new LinkedHashMap<>());
                                      }
                                      if (List.of("getObject", "getString", "getBoolean", "getLong")
                                              .contains(rm.getName())
                                          && ra != null
                                          && ra.length == 1
                                          && !readRows.isEmpty()) {
                                        String key =
                                            ra[0] instanceof Integer number
                                                ? columns.get(number - 1)
                                                : ra[0].toString();
                                        readRows.getLast().put(key, field);
                                        readMethods.getLast().put(key, rm.getName());
                                        readTypes
                                            .getLast()
                                            .put(
                                                key,
                                                field == null
                                                    ? "null"
                                                    : field.getClass().getName());
                                      }
                                      return field;
                                    });
                              }
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
    ledger.questionsEnabled = true;
    if (args.length > 2 && args[2].equals("reads")) {
      trial("direct-valid", () -> {}, QuestionAccessScenario::direct, "OK");
      trial(
          "direct-disabled",
          () ->
              db.update(
                  "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'"),
          QuestionAccessScenario::direct,
          "OK");
      return;
    }
    trial("direct-valid", () -> {}, QuestionAccessScenario::direct, "OK");
    trial("owner-list", () -> {}, QuestionAccessScenario::owner, "OK");
    trial("existing-replay", () -> {}, QuestionAccessScenario::replay, "OK");
    trial(
        "owner-purged",
        () ->
            db.update(
                "UPDATE task SET question_cleanup_state='PURGED' WHERE tenant_id='tenant-a' AND id=?",
                task),
        QuestionAccessScenario::owner,
        "QUESTION_CONTENT_EXPIRED");
    trial(
        "owner-digest",
        () ->
            db.update(
                "UPDATE task SET question_binding_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND id=?",
                task),
        QuestionAccessScenario::owner,
        "SNAPSHOT_CORRUPT");
    trial(
        "owner-raw-binding",
        () ->
            db.update(
                "UPDATE task SET question_binding=CONCAT(question_binding,' '),question_binding_digest=SHA2(question_binding,256) WHERE tenant_id='tenant-a' AND id=?",
                task),
        QuestionAccessScenario::owner,
        "SNAPSHOT_CORRUPT");
    trial(
        "direct-disabled",
        () ->
            db.update(
                "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'"),
        QuestionAccessScenario::direct,
        "OK");
    trial(
        "public-disabled",
        () ->
            db.update(
                "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'"),
        QuestionAccessScenario::owner,
        "CAPABILITY_REVOKED");
    trial(
        "direct-missing",
        () ->
            db.update(
                "DELETE FROM installation WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'"),
        QuestionAccessScenario::direct,
        "NOT_FOUND");
    trial(
        "public-missing",
        () ->
            db.update(
                "DELETE FROM installation WHERE tenant_id='tenant-a' AND plugin_id='ask_user_question'"),
        QuestionAccessScenario::owner,
        "CAPABILITY_REVOKED");
  }
}
