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
public final class PendingQuestionMessagesScenario {
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
    measuredResult = "none";
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    Throwable rollback = null;
    Throwable proofFailure = null;
    Throwable evidenceFailure = null;
    String raw = null;
    sql.clear();
    settings.clear();
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
      if (name.equals("authorize-missing")) {
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
        System.out.println("SETTINGS " + name + " " + Payloads.json(settings));
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
    if (value instanceof java.sql.Timestamp time)
      return Map.of(
          "javaType", value.getClass().getName(), "isoValue", time.toLocalDateTime().toString());
    if (value instanceof java.time.Instant instant)
      return Map.of(
          "isoValue",
          instant.toString(),
          "epochSecond",
          instant.getEpochSecond(),
          "nano",
          instant.getNano());
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), evidenceValue(item)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(PendingQuestionMessagesScenario::evidenceValue).toList();
    return value;
  }

  private static Object bindValue(Object value) {
    // Evidence representation only; invoke(statement, method, args) receives unchanged JDBC args.
    if (value instanceof java.sql.Timestamp stamp)
      return Map.of(
          "javaType",
          value.getClass().getName(),
          "isoValue",
          stamp.toInstant().toString(),
          "nano",
          stamp.getNanos());
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
    var result = new LinkedHashMap<String, Object>();
    result.put("result", evidenceValue(measuredResult));
    result.put("task", taskRow());
    for (String table :
        List.of(
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "task_attempt",
            "event",
            "user_question"))
      result.put(
          table,
          db.queryForList(
              "SELECT * FROM "
                  + table
                  + " WHERE tenant_id='tenant-a' AND task_id=?"
                  + (table.equals("event")
                      ? " ORDER BY seq"
                      : table.equals("user_question") ? " ORDER BY ordinal" : ""),
              subject));
    result.put("budget", db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'"));
    return Payloads.json(evidenceValue(result));
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
                    "read_1", "function", "ask_user_question", QUESTION_INPUT),
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
                    "read_2", "function", "ask_user_question", QUESTION_INPUT)),
            17,
            usage);
    var call =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
            "0:read_1", "read_1", "ask_user_question", QUESTION_INPUT);
    ledger.modelResponded(
        lease,
        step.id(),
        dispatch,
        Payloads.json(response),
        List.of(
            call,
            new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                "0:read_2", "read_2", "ask_user_question", QUESTION_INPUT)),
        17,
        usage);
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
          private int connectionSequence;

          @Override
          public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            int identity = ++connectionSequence;
            var own = new java.util.concurrent.atomic.AtomicReference<Connection>();
            Connection traced =
                (Connection)
                    Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (p, m, a) -> {
                          if (measuring && m.getName().equals("setNetworkTimeout"))
                            settings.add(
                                Map.of(
                                    "connection", identity, "method", m.getName(), "millis", a[1]));
                          Object value = invoke(connection, m, a);
                          if (value instanceof java.sql.Statement statement
                              && (m.getName().equals("prepareStatement")
                                  || m.getName().equals("createStatement")))
                            return observe(
                                statement,
                                m.getName().equals("prepareStatement") ? a[0].toString() : null,
                                identity,
                                own);
                          return value;
                        });
            own.set(traced);
            return traced;
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
        phase,
        () -> {
          if (phase.equals("pending")) pendingFixture();
          else claim();
          activeProof();
        },
        () -> {
          measuredResult = ledger.pendingQuestionMessages(lease);
          require(
              ((List<?>) measuredResult).size() == (phase.equals("pending") ? 2 : 0),
              "actual public list");
          try {
            ((List<String>) measuredResult).add("must fail");
            throw new AssertionError("modifiable list");
          } catch (UnsupportedOperationException expected) {
          }
          return proof(task);
        },
        "OK");
  }

  private static void pendingFixture() {
    dispatched();
    respond();
    proofSubject = null;
    var row = taskRow();
    var pin = ledger.tools(row).pins().get("ask_user_question");
    require("0.2.0".equals(pin.version()), "timed pin");
    ledger.questions.input(pin.version(), QUESTION_INPUT);
    var now = db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.time.LocalDateTime.class);
    for (int ordinal = 1; ordinal <= 2; ordinal++) {
      String provider = "read_" + ordinal;
      String invocation =
          db.queryForObject(
              "SELECT id FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND provider_id=?",
              String.class,
              task,
              provider);
      String output = ledger.questions.pending(pin.version(), provider);
      ledger.questions.output(pin.version(), output);
      db.update(
          "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=? WHERE tenant_id='tenant-a' AND id=?",
          output,
          Payloads.hash(output),
          invocation);
      db.update(
          "INSERT INTO reservation(tenant_id,id,task_id,dispatch_id,category,amount,actual,usage_quality,state) VALUES('tenant-a',?,?,?,'TOOL',1,1,'KNOWN','SETTLED')",
          invocation,
          task,
          invocation);
      String qid = String.format("00000000-0000-0000-0000-%012d", ordinal);
      String aid = String.format("00000000-0000-0000-0000-%012d", ordinal + 10);
      var answers =
          List.of(
              new io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer(
                  "q1", List.of(), "已选🙂" + ordinal));
      String answer = Payloads.json(answers);
      String requestDigest =
          Payloads.hash(Payloads.json(new StoredAnswerRequest(qid, 1, 2, answers)));
      String receipt =
          Payloads.json(
              Map.of(
                  "version",
                  1,
                  "requestId",
                  qid,
                  "answerId",
                  aid,
                  "questionVersion",
                  1,
                  "acceptedRevision",
                  3L,
                  "answeredBy",
                  "alice",
                  "answeredAt",
                  now.toInstant(java.time.ZoneOffset.UTC).toString(),
                  "delivery",
                  "USER_MESSAGE"));
      db.update(
          "INSERT INTO user_question(tenant_id,id,task_id,owner_actor_id,invocation_id,ordinal,revision,binding_digest,mode,effective_timeout,phase,request_snapshot,request_digest,foreground_due_at,answer_expires_at,retain_until,answer_id,answered_actor_id,answered_at,accepted_seq,answer_snapshot,answer_digest,answer_key,answer_request_digest,answer_source_revision,receipt_snapshot,delivery_state) VALUES('tenant-a',?,?,'alice',?,?,3,?,'timed',120,'ANSWERED',?,?,TIMESTAMPADD(SECOND,-1,?),?,TIMESTAMPADD(SECOND,604800,?),?,'alice',?,?,?,?,?,?,2,?,'PENDING')",
          qid,
          task,
          invocation,
          ordinal,
          row.get("question_binding_digest"),
          QUESTION_INPUT,
          Payloads.hash(QUESTION_INPUT),
          now,
          row.get("deadline"),
          row.get("created_at"),
          aid,
          now,
          3 - ordinal,
          answer,
          Payloads.hash(answer),
          "pending-key-" + ordinal,
          requestDigest,
          receipt);
    }
    db.update("UPDATE task SET tools_used=2 WHERE tenant_id='tenant-a' AND id=?", task);
    db.update("UPDATE tenant_budget SET tool_used=tool_used+2 WHERE tenant_id='tenant-a'");
  }

  private static void activeProof() {
    var row = taskRow();
    var attempt =
        db.queryForMap(
            "SELECT * FROM task_attempt WHERE tenant_id='tenant-a' AND id=?", lease.attemptId());
    require(
        "RUNNING".equals(row.get("state")) && "ACTIVE".equals(attempt.get("state")),
        "actual RUNNING task/ACTIVE attempt");
    require(
        lease.attemptId().equals(row.get("current_attempt_id"))
            && lease.worker().equals(row.get("worker_id"))
            && lease.fence() == ((Number) row.get("fence")).longValue(),
        "complete lease keys");
    require(
        db.queryForObject(
            "SELECT lease_until>CURRENT_TIMESTAMP(6) FROM task WHERE tenant_id='tenant-a' AND id=?",
            Boolean.class,
            task),
        "live original lease before public entry");
    System.out.println(
        "ACTIVE_LEASE_PROOF "
            + Payloads.json(
                evidenceValue(
                    Map.of(
                        "lease",
                        Map.of(
                            "tenant",
                            lease.tenant(),
                            "actor",
                            lease.actor(),
                            "taskId",
                            lease.taskId(),
                            "attemptId",
                            lease.attemptId(),
                            "fence",
                            lease.fence(),
                            "worker",
                            lease.worker(),
                            "deadline",
                            lease.deadline().toString()),
                        "task",
                        row,
                        "attempt",
                        attempt))));
  }

  private record StoredAnswerRequest(
      String requestId,
      int questionVersion,
      int expectedRevision,
      java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer> answers) {}

  private static final List<Map<String, Object>> settings = new ArrayList<>();

  private static JdbcLedger queryLedger(
      long end,
      io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources prior) {
    try {
      var method =
          JdbcLedger.class.getDeclaredMethod(
              "queryView",
              long.class,
              io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources
                  .class);
      method.setAccessible(true);
      return (JdbcLedger) method.invoke(ledger, end, prior);
    } catch (InvocationTargetException failure) {
      return rethrow(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static <T> T rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
    throw new AssertionError(failure);
  }

  private static java.time.Instant clockCall(JdbcLedger target) {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("questionNow");
      method.setAccessible(true);
      return (java.time.Instant) method.invoke(target);
    } catch (InvocationTargetException failure) {
      return rethrow(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Object observe(
      java.sql.Statement statement,
      String preparedSql,
      int connection,
      java.util.concurrent.atomic.AtomicReference<Connection> owner) {
    var binds = new java.util.TreeMap<Integer, Object>();
    var setters = new java.util.TreeMap<Integer, String>();
    var last = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    var wrapped = new java.util.IdentityHashMap<java.sql.ResultSet, java.sql.ResultSet>();
    var resultRow = new java.util.concurrent.atomic.AtomicInteger();
    return Proxy.newProxyInstance(
        PendingQuestionMessagesScenario.class.getClassLoader(),
        new Class<?>[] {preparedSql == null ? java.sql.Statement.class : PreparedStatement.class},
        (sp, sm, sv) -> {
          if (sm.getName().equals("getConnection")) return owner.get();
          if (sm.getName().startsWith("set")
              && sv != null
              && sv.length > 1
              && sv[0] instanceof Integer index) {
            binds.put(index, sm.getName().equals("setNull") ? null : bindValue(sv[1]));
            setters.put(index, sm.getName());
          }
          if (measuring && sm.getName().equals("setQueryTimeout"))
            settings.add(
                Map.of("connection", connection, "method", sm.getName(), "seconds", sv[0]));
          if (measuring && sm.getName().startsWith("execute")) {
            String text =
                (preparedSql == null ? sv[0].toString() : preparedSql).replaceAll("\\s+", " ");
            var row = new LinkedHashMap<String, Object>();
            row.put("text", text);
            row.put("binds", new LinkedHashMap<>(binds));
            row.put("setters", new LinkedHashMap<>(setters));
            row.put("api", sm.getName());
            row.put("statement", preparedSql == null ? "Statement" : "PreparedStatement");
            row.put("connection", connection);
            row.put(
                "questionNow",
                java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(
                        v ->
                            v.getClassName().equals(JdbcLedger.class.getName())
                                && v.getMethodName().equals("questionNow")));
            row.put("getters", new ArrayList<Map<String, Object>>());
            row.put("resultApis", new ArrayList<String>());
            last.set(row);
            sql.add(row);
          }
          Object value = invoke(statement, sm, sv);
          var row = last.get();
          if (measuring
              && row != null
              && (sm.getName().equals("executeUpdate") || sm.getName().equals("getUpdateCount")))
            row.put("updateCount", value);
          if (measuring && row != null && value instanceof java.sql.ResultSet result) {
            @SuppressWarnings("unchecked")
            var apis = (List<String>) row.get("resultApis");
            apis.add(sm.getName());
            if (!wrapped.containsKey(result))
              wrapped.put(
                  result,
                  (java.sql.ResultSet)
                      Proxy.newProxyInstance(
                          PendingQuestionMessagesScenario.class.getClassLoader(),
                          new Class<?>[] {java.sql.ResultSet.class},
                          (rp, rm, ra) -> {
                            Object read = invoke(result, rm, ra);
                            if (rm.getName().equals("wasNull")) {
                              @SuppressWarnings("unchecked")
                              var prior = (List<Map<String, Object>>) row.get("getters");
                              if (!prior.isEmpty()) prior.getLast().put("wasNull", read);
                            }
                            if (rm.getName().equals("next") && Boolean.TRUE.equals(read))
                              resultRow.incrementAndGet();
                            if (rm.getName().equals("getMetaData")
                                && read instanceof java.sql.ResultSetMetaData metadata) {
                              return Proxy.newProxyInstance(
                                  PendingQuestionMessagesScenario.class.getClassLoader(),
                                  new Class<?>[] {java.sql.ResultSetMetaData.class},
                                  (mp, mm, ma) -> {
                                    Object mv = invoke(metadata, mm, ma);
                                    if (ma != null && ma.length == 1 && ma[0] instanceof Integer) {
                                      @SuppressWarnings("unchecked")
                                      var meta =
                                          (List<Map<String, Object>>)
                                              row.computeIfAbsent(
                                                  "metadata",
                                                  ignored -> new ArrayList<Map<String, Object>>());
                                      meta.add(
                                          Map.of(
                                              "method",
                                              mm.getName(),
                                              "column",
                                              ma[0],
                                              "value",
                                              mv));
                                    }
                                    return mv;
                                  });
                            }
                            if (rm.getName().startsWith("get")
                                && ra != null
                                && ra.length >= 1
                                && (ra[0] instanceof String || ra[0] instanceof Integer)) {
                              var getter = new LinkedHashMap<String, Object>();
                              getter.put("row", resultRow.get());
                              getter.put("method", rm.getName());
                              getter.put("column", ra[0]);
                              getter.put(
                                  "requestedType",
                                  ra.length == 2 && ra[1] instanceof Class<?> type
                                      ? type.getName()
                                      : null);
                              getter.put("value", evidenceValue(read));
                              getter.put("type", read == null ? "NULL" : read.getClass().getName());
                              @SuppressWarnings("unchecked")
                              var getters = (List<Map<String, Object>>) row.get("getters");
                              getters.add(getter);
                            }
                            return read;
                          }));
            return wrapped.get(result);
          }
          return value;
        });
  }

  private static int expectedRevision = 2;
  private static Object measuredResult = "none";
  private static final String QUESTION_ID = "00000000-0000-0000-0000-000000000001";

  private static io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity owner() {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");
  }

  private static void fixtureProof(String name) {
    System.out.println("FIXTURE_PROOF " + name + " " + captureProof(task));
  }

  private static String QUESTION_INPUT =
      "{\"questions\":[{\"id\":\"q1\",\"question\":\"请选择🙂\"}],\"timeout\":120}";
}
