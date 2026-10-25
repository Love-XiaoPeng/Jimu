package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
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
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 基础资格实际调用／锁链；外层恢复、主错、回滚及证明错误分存。 */
public final class DirectoryAccessScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Map<String, Object> selectedTask;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static String proofSubject;
  private static String phase;
  private static String fault;
  private static boolean emptyInstallation;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease;
  private static int injected;
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
            "task_directory")) {
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

  private static void trial(String name, Runnable setup, Supplier<String> call, String expected) {
    if (!phase.equals("all") && !List.of(phase.split(",")).contains(name)) return;
    proofSubject = null;
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    Throwable rollback = null;
    Throwable proofFailure = null;
    Throwable evidenceFailure = null;
    String raw = null;
    sql.clear();
    injected = 0;
    long started = 0;
    long elapsed = 0;
    try {
      setup.run();
      selectedTask = taskRow();
      measuring = true;
      started = System.nanoTime();
      raw = call.get();
      elapsed = System.nanoTime() - started;
      System.out.println("RETURN " + name + " " + elapsed);
    } catch (Throwable failure) {
      elapsed = started == 0 ? 0 : System.nanoTime() - started;
      primary = failure;
      errors(failure, "production-or-setup-" + name);
      if (name.equals("responded-reject")) {
        try {
          System.out.println(
              "REJECTION_PROOF "
                  + Payloads.json(
                      evidenceValue(
                          Map.of(
                              "dispatch",
                                  db.queryForList(
                                      "SELECT * FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=?",
                                      task),
                              "reservations",
                                  db.queryForList(
                                      "SELECT * FROM reservation WHERE tenant_id='tenant-a' AND task_id=?",
                                      task),
                              "events",
                                  db.queryForList(
                                      "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                                      task)))));
          require(
              db.queryForObject(
                      "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='model.rejected' AND code='DIRECTORY_FEATURE_DISABLED'",
                      Long.class,
                      task)
                  == 1,
              "known rejection recorded before outer rollback");
        } catch (Throwable failureProof) {
          proofFailure = failureProof;
          errors(failureProof, "rejection-proof-" + name);
        }
      }
    } finally {
      measuring = false;
      fault = null;
      ledger.directoryEnabled = true;
      ledger.sessionReadEnabled = true;
      ledger.sessionSearchEnabled = true;
      ledger.sessionTraceEnabled = true;
      ledger.priorSessionSearchEnabled = true;
      emptyInstallation = false;
      if (primary == null && proofSubject != null) {
        try {
          raw = captureProof(proofSubject);
          System.out.println("PROOF " + name + " " + raw);
        } catch (Throwable failure) {
          proofFailure = failure;
          errors(failure, "post-return-proof-" + name);
        }
      }
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      // Persist primary and SQL evidence before any assertion or proof read can fail.
      try {
        System.out.println("SQL " + name + " " + Payloads.json(sql));
      } catch (Throwable failure) {
        evidenceFailure = failure;
        errors(failure, "sql-evidence-" + name);
      }
      System.out.println(
          "OUTCOME "
              + Payloads.json(
                  Map.of(
                      "name",
                      name,
                      "returned",
                      primary == null,
                      "elapsedNanos",
                      elapsed,
                      "primary",
                      primary == null ? "none" : primary.getClass().getName(),
                      "rollback",
                      rollback == null ? "complete" : rollback.getClass().getName())));
    }
    require(
        proofFailure == null, "post-return proof failure distinct from saved production outcome");
    require(rollback == null, "UNKNOWN/rollback failure ends this private instance");
    String after = facts();
    System.out.println(
        "FACTS " + Payloads.json(Map.of("name", name, "before", before, "after", after)));
    require(before.equals(after), "all captured tenant facts restored: " + name);
    System.out.println("RESTORED " + name);
    require(evidenceFailure == null, "SQL evidence failure kept separate from saved primary");
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure failure
                ? failure.getMessage()
                : primary.getClass().getName();
    if (expected.equals("SQL_FAILURE")) {
      boolean original = false;
      for (Throwable cause = primary; cause != null; cause = cause.getCause())
        if (cause instanceof SQLException sqlFailure
            && "HY000".equals(sqlFailure.getSQLState())
            && sqlFailure.getErrorCode() == 1644) original = true;
      require(original, "original single SQL failure retained: " + name);
    } else require(expected.equals(code), name + " expected " + expected + " got " + code);
    if (name.endsWith("-sql")) require(injected == 1, "one SQL fault only");
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name);
    result.put("code", code);
    result.put("raw", raw);
    result.put("injected", injected);
    System.out.println("RESULT " + Payloads.json(result));
  }

  static Object evidenceValue(Object value) {
    if (value instanceof java.time.LocalDateTime) return bindValue(value);
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), evidenceValue(item)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(DirectoryAccessScenario::evidenceValue).toList();
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

  private static io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Step step;
  private static String request;
  private static String context;
  private static String dispatch;
  private static final String CLOCK = "SELECT CAST('2026-10-25 08:40:00.123456' AS DATETIME(6))";

  private static void claim() {
    // Fixture task window, not the query/socket/network/transaction deadline. Rolled back.
    db.update(
        "UPDATE task SET deadline=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
        task);
    ledger.registerWorker(
        "facts-private", "facts-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("facts-private").orElseThrow();
    require(task.equals(lease.taskId()), "exact admitted caller claimed");
    var row = taskRow();
    var projection = ledger.directoryProjection(row);
    context =
        ledger.directories.context(
            (String) projection.get("cwd"), ((Number) projection.get("revision")).longValue());
    var model = ledger.modelSnapshot(lease);
    var messages =
        List.of(
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                "user", (String) row.get("goal"), List.of(), List.of()),
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                "user", context, List.of(), List.of()));
    String body =
        Payloads.json(
            Map.of(
                "messages",
                List.of(
                    Map.of("role", "user", "content", row.get("goal")),
                    Map.of("role", "user", "content", context))));
    request =
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
  }

  private static void prepared() {
    claim();
    step = ledger.prepare(lease, request);
  }

  private static void dispatched() {
    prepared();
    dispatch = ledger.dispatchModel(lease, step.id());
  }

  private static String proof(String subject) {
    proofSubject = subject;
    return "proof-pending";
  }

  private static String captureProof(String subject) {
    var projection =
        db.queryForMap(
            "SELECT * FROM task_directory WHERE tenant_id='tenant-a' AND task_id=?", subject);
    require(
        Payloads.hash((String) projection.get("binding_snapshot"))
            .equals(projection.get("binding_digest")),
        "directory original binding digest");
    return Payloads.json(
        evidenceValue(
            Map.of(
                "directory",
                projection,
                "task",
                db.queryForMap(
                    "SELECT tenant_id,id,state,capabilities,installation_epoch,next_event_seq,models_used,tools_used FROM task WHERE tenant_id='tenant-a' AND id=?",
                    subject),
                "events",
                db.queryForList(
                    "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                    subject))));
  }

  private static String admission() {
    try {
      var row = selectedTask;
      var identity = new Identity("tenant-a", (String) row.get("owner_actor_id"));
      var r =
          new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
              "fact public goal",
              java.util.Arrays.asList(((String) row.get("capabilities")).split(",")),
              List.of(),
              new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(180, 8, 20, null));
      var method =
          JdbcLedger.class.getDeclaredMethod(
              "submitBound",
              Identity.class,
              String.class,
              io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request.class,
              List.class,
              List.class,
              String.class);
      method.setAccessible(true);
      List<String> sources =
          JdbcSessionScopes.task(row)
              ? db.queryForList(
                  "SELECT source_task_id FROM task_session_scope_target WHERE tenant_id='tenant-a' AND caller_task_id=? ORDER BY ordinal",
                  String.class,
                  task)
              : null;
      var view =
          (io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View)
              method.invoke(
                  ledger,
                  identity,
                  "fact-admission",
                  r,
                  List.of(),
                  sources,
                  "00000000-0000-4000-8000-000000000060");
      return proof(view.taskId());
    } catch (ReflectiveOperationException failure) {
      if (failure instanceof InvocationTargetException wrapped
          && wrapped.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException(failure);
    }
  }

  static io.github.lovexiaopeng.jimu.model.api.ModelUsage usage() {
    return new io.github.lovexiaopeng.jimu.model.api.ModelUsage(
        10L, 7L, 17L, null, null, "private-model", "local-byte-accounted-v1", "tool_calls");
  }

  private static String respond() {
    var usage = usage();
    var response =
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response(
            2,
            "response public",
            List.of(
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
                    "read_1", "function", "working_directory", "{}")),
            17,
            usage);
    var call =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
            "0:read_1", "read_1", "working_directory", "{}");
    ledger.modelResponded(
        lease, step.id(), dispatch, Payloads.json(response), List.of(call), 17, usage);
    return proof(task);
  }

  private static void directAccess() {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("directoryAccess", Map.class);
      method.setAccessible(true);
      method.invoke(ledger, selectedTask);
    } catch (ReflectiveOperationException failure) {
      if (failure instanceof InvocationTargetException wrapped
          && wrapped.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException(failure);
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
            Connection connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object[] actual = a;
                      if (m.getName().equals("prepareStatement")
                          && a[0].equals("SELECT CURRENT_TIMESTAMP(6)")) {
                        actual = a.clone();
                        actual[0] = CLOCK;
                      }
                      Object value = invoke(connection, m, actual);
                      if (value instanceof PreparedStatement statement
                          && m.getName().equals("prepareStatement")) {
                        String text = a[0].toString().replaceAll("\\s+", " ");
                        var binds = new java.util.TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index)
                                binds.put(
                                    index,
                                    sm.getName().equals("setNull") ? null : bindValue(sv[1]));
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                sql.add(row);
                                if (fault != null
                                    && text.replaceAll("[\\s()]", "")
                                        .startsWith(fault.replaceAll("[\\s()]", ""))) {
                                  fault = null;
                                  ledger.directoryEnabled = true;
                                  ledger.sessionReadEnabled = true;
                                  ledger.sessionSearchEnabled = true;
                                  ledger.sessionTraceEnabled = true;
                                  ledger.priorSessionSearchEnabled = true;
                                  emptyInstallation = false;
                                  injected++;
                                  throw new SQLException("PRIVATE_METADATA_FAILURE", "HY000", 1644);
                                }
                              }
                              Object result = invoke(statement, sm, sv);
                              if (measuring
                                  && emptyInstallation
                                  && result instanceof java.sql.ResultSet rows
                                  && text.equals(
                                      "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE")) {
                                return Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {java.sql.ResultSet.class},
                                    (rp, rm, ra) ->
                                        rm.getName().equals("next") ? false : invoke(rows, rm, ra));
                              }
                              return result;
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
    phase = args.length > 2 ? args[2] : "all";
    ledger.directoryEnabled(true);
    trial(
        "owner-directory",
        () -> {},
        () -> {
          var view = ledger.getWorkingDirectory(new Identity("tenant-a", "alice"), task);
          System.out.println("OWNER_OUTPUT " + Payloads.json(view));
          return proof(task);
        },
        "OK");
    trial(
        "idempotent-retrieval",
        () -> {},
        () -> {
          admission();
          return admission();
        },
        "OK");
    trial(
        "prepare-fenced",
        DirectoryAccessScenario::claim,
        () -> {
          step = ledger.prepare(lease, request);
          return proof(task);
        },
        "OK");
    trial(
        "responded-success",
        DirectoryAccessScenario::dispatched,
        DirectoryAccessScenario::respond,
        "OK");
    trial(
        "responded-reject",
        DirectoryAccessScenario::dispatched,
        () -> {
          ledger.directoryEnabled = false;
          return respond();
        },
        "DIRECTORY_FEATURE_DISABLED");
    trial(
        "direct-valid",
        () -> {},
        () -> {
          directAccess();
          return proof(task);
        },
        "OK");
    trial(
        "duplicate-order",
        () -> {},
        () -> {
          selectedTask.put("capabilities", "write,read,working_directory,edit,read");
          directAccess();
          return proof(task);
        },
        "OK");
    trial(
        "set-rejected",
        () -> {},
        () -> {
          selectedTask.put("capabilities", "working_directory,read,write");
          directAccess();
          return "unreachable";
        },
        "SNAPSHOT_CORRUPT");
    trial(
        "tools-hash",
        () -> {},
        () -> {
          selectedTask.put("tools_digest", "bad");
          directAccess();
          return "unreachable";
        },
        "SNAPSHOT_CORRUPT");
    trial(
        "tools-null",
        () -> {},
        () -> {
          selectedTask.put("tools_snapshot", null);
          directAccess();
          return "unreachable";
        },
        "PLUGIN_SNAPSHOT_UNAVAILABLE");
    trial(
        "installation-missing",
        () -> db.update("DELETE FROM installation WHERE tenant_id='tenant-a' AND plugin_id='read'"),
        () -> {
          directAccess();
          return "unreachable";
        },
        "NOT_FOUND");
    trial(
        "installation-disabled",
        () ->
            db.update(
                "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='read'"),
        () -> {
          directAccess();
          return proof(task);
        },
        "OK");
    trial(
        "installation-version",
        () ->
            db.update(
                "UPDATE installation SET plugin_version='bad' WHERE tenant_id='tenant-a' AND plugin_id='working_directory'"),
        () -> {
          directAccess();
          return "unreachable";
        },
        "PLUGIN_PIN_MISMATCH");
    trial(
        "installation-digest",
        () ->
            db.update(
                "UPDATE installation SET plugin_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND plugin_id='read'"),
        () -> {
          directAccess();
          return "unreachable";
        },
        "PLUGIN_PIN_MISMATCH");
    trial(
        "installation-epoch",
        () ->
            db.update(
                "UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='read'"),
        () -> {
          directAccess();
          return "unreachable";
        },
        "PLUGIN_PIN_MISMATCH");
    trial(
        "projection-namespace",
        () ->
            db.update(
                "UPDATE task_directory SET namespace_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        () -> {
          directAccess();
          return "unreachable";
        },
        "SNAPSHOT_CORRUPT");
    trial(
        "projection-cwd",
        () ->
            db.update(
                "UPDATE task_directory SET cwd='/bad' WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        () -> {
          directAccess();
          return "unreachable";
        },
        "SNAPSHOT_CORRUPT");
    trial(
        "caps-null",
        () -> {},
        () -> {
          selectedTask.put("capabilities", null);
          directAccess();
          return "unreachable";
        },
        "java.lang.NullPointerException");
    trial(
        "installation-sql",
        () -> {},
        () -> {
          fault = "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE";
          directAccess();
          return "unreachable";
        },
        "SQL_FAILURE");
  }
}
