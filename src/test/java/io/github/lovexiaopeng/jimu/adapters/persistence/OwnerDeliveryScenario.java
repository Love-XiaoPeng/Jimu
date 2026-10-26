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
public final class OwnerDeliveryScenario {
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
    for (String table : db.queryForList("SHOW TABLES", String.class))
      result.put(
          table,
          db.queryForList("SELECT * FROM \u0060" + table + "\u0060").stream()
              .map(Object::toString)
              .sorted()
              .toList());
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

  private static Object evidenceValue(Object value) {
    if (value instanceof java.util.Optional<?> optional)
      return evidenceValue(optional.orElse(null));
    if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    if (value instanceof Record record) {
      var converted = new LinkedHashMap<String, Object>();
      for (var component : record.getClass().getRecordComponents()) {
        try {
          converted.put(component.getName(), evidenceValue(component.getAccessor().invoke(record)));
        } catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("record evidence conversion", failure);
        }
      }
      return converted;
    }
    if (value instanceof Map<?, ?> map) {
      var converted = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> converted.put(key.toString(), evidenceValue(item)));
      return converted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(OwnerDeliveryScenario::evidenceValue).toList();
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
    ledger =
        args.length == 4
            ? new JdbcLedger(
                shared,
                new ModelProfiles(env),
                transactions,
                new io.github.lovexiaopeng.jimu.adapters.persistence.transaction
                    .SpringLedgerUnitOfWork(transactions),
                null,
                io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
                    .create(shared))
            : new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    task = args[1];
    String ownerTask = args[2];
    ledger.questionsEnabled(true);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    if (args.length == 4) {
      require("query-view".equals(args[3]), "explicit bounded fixture mode");
      queryViews(ownerTask, initial);
      return;
    }
    for (boolean conflict : List.of(false, true)) {
      var tx = transactions.getTransaction(new DefaultTransactionDefinition());
      Throwable primary = null;
      try {
        // Scheduling-only fixture keeps the owner source out of the synthetic claim.
        db.update(
            "UPDATE task SET ready_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)) WHERE tenant_id='tenant-a' AND id=?",
            ownerTask);
        if (!conflict) {
          call("owner", () -> ledger.sessionHistory(owner(), ownerTask), "OK");
          call(
              "cross-owner",
              () ->
                  ledger.sessionHistory(
                      new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                          "tenant-a", "carol"),
                      ownerTask),
              "SESSION_EVENT_NOT_FOUND");
          var view =
              queryLedger(
                  System.nanoTime() + 2_000_000_000L,
                  new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                      .QueryResources(2, 12, 4));
          System.out.println("METER_BEFORE " + Payloads.json(view.queryContext().resources()));
          call("owner-metered", () -> view.sessionHistory(owner(), ownerTask), "OK");
          System.out.println("METER_AFTER " + Payloads.json(view.queryContext().resources()));
        }
        pendingFixture();
        activeProof();
        var pending = ledger.questionMessages(taskRow());
        System.out.println("PENDING_STRINGS " + Payloads.json(pending));
        var wire =
            Payloads.read(
                request,
                io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
        var messages = new ArrayList<>(wire.messages());
        pending
            .values()
            .forEach(
                value ->
                    messages.add(
                        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent
                            .WireMessage("user", value, List.of(), List.of())));
        String body = Payloads.json(Map.of("messages", messages));
        String next =
            Payloads.json(
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
                    wire.version(),
                    wire.model(),
                    messages,
                    wire.capabilities(),
                    wire.epoch(),
                    wire.stream(),
                    wire.configDigest(),
                    body,
                    Payloads.hash(body)));
        db.update(
            "UPDATE task SET question_continuation_step_id=? WHERE tenant_id='tenant-a' AND id=?",
            step.id(),
            task);
        conflictQuestion =
            conflict ? pending.keySet().stream().skip(1).findFirst().orElseThrow() : null;
        call(
            conflict ? "prepare-conflict" : "prepare-new",
            () -> ledger.prepare(lease, next),
            conflict ? "QUESTION_DELIVERY_CONFLICT" : "OK");
        if (!conflict) {
          call("prepare-exact-replay", () -> ledger.prepare(lease, next), "OK");
          call("checkpoint-read", () -> ledger.checkpoint(lease).orElseThrow(), "OK");
          call(
              "wrong-fence",
              () ->
                  ledger.checkpoint(
                      new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
                          lease.tenant(),
                          lease.actor(),
                          lease.taskId(),
                          lease.attemptId(),
                          lease.fence() + 1,
                          lease.worker(),
                          lease.deadline(),
                          lease.goal(),
                          lease.capabilities(),
                          lease.installationEpoch())),
              "FENCE_REJECTED");
        }
      } catch (Throwable failure) {
        primary = failure;
        errors(failure, "scenario");
      } finally {
        measuring = false;
        conflictQuestion = null;
        try {
          transactions.rollback(tx);
          System.out.println("ROLLBACK " + conflict);
        } catch (Throwable failure) {
          errors(failure, "rollback");
          if (primary == null) primary = failure;
          else primary.addSuppressed(failure);
        }
      }
      String restored = facts();
      System.out.println(
          "RESTORATION "
              + Payloads.json(
                  Map.of(
                      "conflict",
                      conflict,
                      "before",
                      initial,
                      "after",
                      restored,
                      "equal",
                      initial.equals(restored))));
      require(initial.equals(restored), "all private tables restored including worker_process");
      if (primary != null) throw new AssertionError("original failure retained", primary);
    }
    System.out.println("FULLAFTER " + facts());
    System.out.println("RESTORED all private tables including worker_process");
  }

  private static String conflictQuestion;

  private static void queryViews(String ownerTask, String initial) {
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {
      call("owner", () -> ledger.sessionHistory(owner(), ownerTask), "OK");
      var view =
          queryLedger(
              System.nanoTime() + 2_000_000_000L,
              new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                  .QueryResources(2, 12, 4));
      System.out.println("METER_BEFORE " + Payloads.json(view.queryContext().resources()));
      call("owner-metered", () -> view.sessionHistory(owner(), ownerTask), "OK");
      System.out.println("METER_MIDDLE " + Payloads.json(view.queryContext().resources()));
      call("owner-metered-again", () -> view.sessionHistory(owner(), ownerTask), "OK");
      System.out.println("METER_AFTER " + Payloads.json(view.queryContext().resources()));
      var plain = queryLedger(System.nanoTime() + 2_000_000_000L, null);
      call("owner-unmetered", () -> plain.sessionHistory(owner(), ownerTask), "OK");
      System.out.println("UNMETERED " + Payloads.json(plain.queryContext().resources()));
      call(
          "cross-owner",
          () ->
              ledger.sessionHistory(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-a", "carol"),
                  ownerTask),
          "SESSION_EVENT_NOT_FOUND");
      call(
          "cross-tenant",
          () ->
              ledger.sessionHistory(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                      "tenant-b", "alice"),
                  ownerTask),
          "SESSION_EVENT_NOT_FOUND");
    } catch (Throwable failure) {
      primary = failure;
      errors(failure, "scenario");
    } finally {
      measuring = false;
      try {
        transactions.rollback(tx);
      } catch (Throwable failure) {
        errors(failure, "rollback");
        if (primary == null) primary = failure;
        else primary.addSuppressed(failure);
      }
    }
    String restored = facts();
    System.out.println(
        "RESTORATION "
            + Payloads.json(
                Map.of("before", initial, "after", restored, "equal", initial.equals(restored))));
    require(initial.equals(restored), "all private tables restored including worker_process");
    if (primary != null) throw new AssertionError("original failure retained", primary);
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    settings.clear();
    var before = Payloads.read(captureProof(task), Map.class);
    int prior = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    int priorMax = db.queryForObject("SELECT @@SESSION.max_execution_time", Integer.class);
    Object result = null;
    Throwable primary = null;
    measuring = true;
    try {
      result = work.get();
    } catch (Throwable failure) {
      primary = failure;
    } finally {
      measuring = false;
    }
    int after = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    int afterMax = db.queryForObject("SELECT @@SESSION.max_execution_time", Integer.class);
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    out.put("before", before);
    out.put("after", Payloads.read(captureProof(task), Map.class));
    out.put("result", evidenceValue(result));
    out.put("sql", new ArrayList<>(sql));
    out.put("settings", new ArrayList<>(settings));
    out.put("sessionPrior", prior);
    out.put("sessionAfter", after);
    out.put("maxExecutionPrior", priorMax);
    out.put("maxExecutionAfter", afterMax);
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName();
    out.put("code", code);
    System.out.println("CALL " + Payloads.json(evidenceValue(out)));
    if (primary != null) errors(primary, "call-" + name);
    require(prior == after, "session restored");
    require(priorMax == afterMax, "max execution restored");
    require(code.equals(expected), name + " expected " + expected + " got " + code);
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
        OwnerDeliveryScenario.class.getClassLoader(),
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
          if (measuring
              && conflictQuestion != null
              && sm.getName().startsWith("execute")
              && preparedSql != null
              && preparedSql.startsWith("UPDATE user_question SET delivery_state='CHECKPOINTED'")
              && conflictQuestion.equals(binds.get(3))) {
            String question = conflictQuestion;
            conflictQuestion = null;
            measuring = false;
            try {
              db.update(
                  "UPDATE user_question SET delivery_state='CHECKPOINTED',delivery_step_id=? WHERE tenant_id='tenant-a' AND id=?",
                  step.id(),
                  question);
            } finally {
              measuring = true;
            }
            System.out.println(
                "INTERLEAVING "
                    + Payloads.json(
                        Map.of(
                            "question",
                            question,
                            "boundary",
                            "private same-connection fixture changes second row after pending snapshot; original CAS returns zero; not external concurrency")));
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
                          OwnerDeliveryScenario.class.getClassLoader(),
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
                                  OwnerDeliveryScenario.class.getClassLoader(),
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
