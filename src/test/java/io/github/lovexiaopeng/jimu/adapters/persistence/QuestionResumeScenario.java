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
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 基础资格实际调用／锁链；外层恢复、主错、回滚及证明错误分存。 */
public final class QuestionResumeScenario {
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
            "task_todo_projection",
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
      fixtureProof(name);
      measuring = true;
      started = System.nanoTime();
      raw = call.get();
      elapsed = System.nanoTime() - started;
      System.out.println("RETURN " + name + " " + elapsed);
    } catch (Throwable failure) {
      elapsed = started == 0 ? 0 : System.nanoTime() - started;
      primary = failure;
      errors(failure, "production-or-setup-" + name);
      if (name.equals("direct-wrong")) {
        try {
          boolean priorMeasuring = measuring;
          measuring = false;
          try {
            System.out.println("REJECTION_PROOF " + name + " " + captureProof(task));
          } finally {
            measuring = priorMeasuring;
          }

        } catch (Throwable failureProof) {
          proofFailure = failureProof;
          errors(failureProof, "rejection-proof-" + name);
        }
      }
    } finally {
      measuring = false;
      fault = null;
      ledger.todosEnabled = true;
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
      return list.stream().map(QuestionResumeScenario::evidenceValue).toList();
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

  private static void claim() {
    // Fixture task window, not the query/socket/network/transaction deadline. Rolled back.
    db.update(
        "UPDATE task SET deadline=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
        task);
    ledger.registerWorker(
        "facts-private", "facts-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("facts-private").orElseThrow();
    require(task.equals(lease.taskId()), "exact admitted caller claimed");
    System.out.println(
        "LEASE_PROOF "
            + Payloads.json(
                Map.of(
                    "tenant",
                    lease.tenant(),
                    "task",
                    lease.taskId(),
                    "attempt",
                    lease.attemptId(),
                    "actor",
                    lease.actor(),
                    "worker",
                    lease.worker(),
                    "fence",
                    lease.fence())));
    var row = taskRow();
    var model = ledger.modelSnapshot(lease);
    var messages =
        List.of(
            new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
                "user", (String) row.get("goal"), List.of(), List.of()));
    String body =
        Payloads.json(
            Map.of("messages", List.of(Map.of("role", "user", "content", row.get("goal")))));
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
    var row = taskRow();
    return Payloads.json(
        evidenceValue(
            Map.of(
                "result", measuredResult,
                "invocations",
                    db.queryForList(
                        "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=?",
                        subject),
                "task", row,
                "steps",
                    db.queryForList(
                        "SELECT * FROM step WHERE tenant_id='tenant-a' AND task_id=? ORDER BY ordinal",
                        subject),
                "questions",
                    db.queryForList(
                        "SELECT * FROM user_question WHERE tenant_id='tenant-a' AND task_id=? ORDER BY ordinal",
                        subject),
                "dispatch",
                    db.queryForList(
                        "SELECT * FROM model_dispatch WHERE tenant_id='tenant-a' AND task_id=?",
                        subject),
                "reservations",
                    db.queryForList(
                        "SELECT * FROM reservation WHERE tenant_id='tenant-a' AND task_id=?",
                        subject),
                "attempts",
                    db.queryForList(
                        "SELECT * FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=?",
                        subject),
                "events",
                    db.queryForList(
                        "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                        subject))));
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
                    "read_1", "function", "ask_user_question", QUESTION_INPUT)),
            17,
            usage);
    var call =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
            "0:read_1", "read_1", "ask_user_question", QUESTION_INPUT);
    ledger.modelResponded(
        lease, step.id(), dispatch, Payloads.json(response), List.of(call), 17, usage);
    return proof(task);
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
                        Map<String, Object>[] last = new Map[] {null};
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
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index)
                                setters.put(index, sm.getName());
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                if (text.startsWith(
                                    "UPDATE task SET state='RESUME_READY',waiting_question_id=NULL")) {
                                  row.put("setters", new LinkedHashMap<>(setters));
                                  row.put("api", sm.getName());
                                  last[0] = row;
                                }
                                sql.add(row);
                                if (fault != null
                                    && text.replaceAll("[\\s()]", "")
                                        .startsWith(fault.replaceAll("[\\s()]", ""))) {
                                  fault = null;
                                  ledger.todosEnabled = true;
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
                                  && last[0] != null
                                  && (sm.getName().equals("executeUpdate")
                                      || sm.getName().equals("getUpdateCount")))
                                last[0].put("updateCount", result);
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
    ledger.todosEnabled = true;
    ledger.questionsEnabled(true);
    trial(
        "direct-match",
        QuestionResumeScenario::fixture,
        () -> {
          resume(QUESTION_ID);
          measuredResult = "updated";
          return proof(task);
        },
        "OK");
    trial(
        "direct-wrong",
        QuestionResumeScenario::fixture,
        () -> {
          resume("00000000-0000-0000-0000-000000000099");
          return proof(task);
        },
        "QUESTION_BINDING_MISMATCH");
    trial(
        "foreground-answer",
        QuestionResumeScenario::fixture,
        () -> {
          measuredResult = answer();
          return proof(task);
        },
        "OK");
    trial(
        "due-continue",
        () -> {
          fixture();
          expectedRevision = 3;
          db.update(
              "UPDATE user_question SET foreground_due_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
              QUESTION_ID);
        },
        () -> {
          measuredResult = answer();
          return proof(task);
        },
        "OK");
  }

  private static void resume(String requestId) {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("questionResume", Map.class, String.class);
      method.setAccessible(true);
      method.invoke(ledger, selectedTask, requestId);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failure.getCause() instanceof Error error) throw error;
      throw new AssertionError(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static int expectedRevision = 2;
  private static Object measuredResult = "none";
  private static final String QUESTION_ID = "00000000-0000-0000-0000-000000000001";

  private static io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity owner() {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Submission
      answer() {
    return ledger.answer(
        owner(),
        task,
        QUESTION_ID,
        "input-answer-key",
        new io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Command(
            1,
            expectedRevision,
            List.of(
                new io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer(
                    "q1", List.of(), "选择🙂"))));
  }

  private static void fixture() {
    measuredResult = "none";
    dispatched();
    respond();
    proofSubject = null;
    var row = taskRow();
    var pin = ledger.tools(row).pins().get("ask_user_question");
    require("0.2.0".equals(pin.version()), "timed pin");
    require(
        ledger.questions.binding(pin.version()).equals(row.get("question_binding")),
        "exact original timed binding");
    ledger.questions.input(pin.version(), QUESTION_INPUT);
    String invocation =
        db.queryForObject(
            "SELECT id FROM invocation WHERE tenant_id='tenant-a' AND task_id=?",
            String.class,
            task);
    db.update(
        "UPDATE invocation SET state='WAITING_USER' WHERE tenant_id='tenant-a' AND id=?",
        invocation);
    db.update(
        "INSERT INTO user_question(tenant_id,id,task_id,owner_actor_id,invocation_id,ordinal,revision,binding_digest,mode,effective_timeout,phase,request_snapshot,request_digest,foreground_due_at,answer_expires_at,retain_until) VALUES('tenant-a',?,?,'alice',?,1,2,?,'timed',120,'OPEN',?,?,TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(SECOND,150,CURRENT_TIMESTAMP(6)),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)))",
        QUESTION_ID,
        task,
        invocation,
        row.get("question_binding_digest"),
        QUESTION_INPUT,
        Payloads.hash(QUESTION_INPUT));
    db.update(
        "UPDATE task SET state='WAITING_USER',waiting_question_id=? WHERE tenant_id='tenant-a' AND id=?",
        QUESTION_ID,
        task);
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,dispatch_id,category,amount,usage_quality,state) VALUES('tenant-a',?,?,?,'TOOL',1,'KNOWN','RESERVED')",
        invocation,
        task,
        invocation);
    db.update(
        "UPDATE task SET tools_used=1,fence=fence+1,current_attempt_id=NULL,worker_id=NULL,lease_until=NULL WHERE tenant_id='tenant-a' AND id=?",
        task);
    db.update(
        "UPDATE task_attempt SET state='PAUSED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND id=?",
        lease.attemptId());
    db.update("UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id='tenant-a'");
    expectedRevision = 2;
  }

  private static void corrupt(boolean codec) {
    String raw = codec ? "{}" : QUESTION_INPUT;
    db.update(
        "UPDATE user_question SET request_snapshot=?,request_digest=? WHERE tenant_id='tenant-a' AND id=?",
        raw,
        codec ? Payloads.hash(raw) : "0".repeat(64),
        QUESTION_ID);
  }

  private static void fixtureProof(String name) {
    System.out.println("FIXTURE_PROOF " + name + " " + captureProof(task));
  }

  private static final String QUESTION_INPUT =
      "{\"questions\":[{\"id\":\"q1\",\"question\":\"请选择🙂\"}],\"timeout\":120}";
}
