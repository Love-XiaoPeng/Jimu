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

/** 实际 admission/prepare/response/terminal/公共 append；主错与回滚分开，证明在返回后。 */
public final class SessionFactScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Map<String, Object> selectedTask;
  private static DataSourceTransactionManager transactions;
  private static String task;
  private static boolean measuring;
  private static String proofSubject;
  private static String phase;
  private static String fault;
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
            "installation")) {
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
    if (phase.equals("reads") && !name.equals("read")) return;
    if ((phase.equals("main") || phase.equals("remaining")) && name.equals("read")) return;
    if (phase.equals("remaining") && List.of("admission", "prepared").contains(name)) return;
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
    } finally {
      measuring = false;
      fault = null;
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
      return list.stream().map(SessionFactScenario::evidenceValue).toList();
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
    context = ledger.sessions.context(row);
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

  private static void event(String fact, String type) {
    ledger.event(selectedTask, fact, type, null);
  }

  private static long append(String fact, String type) {
    return ledger.sessionAppend(
        selectedTask,
        fact,
        type,
        ledger.sessions.source("request", null, null),
        Map.of("content", "private"),
        false,
        "a".repeat(64),
        null);
  }

  private static String proof(String subject) {
    proofSubject = subject;
    return "proof-pending";
  }

  private static String captureProof(String subject) {
    var h =
        db.queryForMap(
            "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", subject);
    var events =
        db.queryForList(
            "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
            subject);
    for (var row : events) {
      String raw = (String) row.get("event_snapshot");
      require(Payloads.hash(raw).equals(row.get("event_digest")), "original event SHA256");
      require(
          raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              == ((Number) row.get("event_bytes")).intValue(),
          "original event bytes");
      var wire = Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class);
      require(
          wire.path("seq").asLong() == ((Number) row.get("seq")).longValue(), "stored sequence");
      require(
          db.queryForObject(
                      "SELECT seq FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id=?",
                      Long.class,
                      subject,
                      "history:" + row.get("fact_key"))
                  .longValue()
              == ((Number) row.get("reliable_event_seq")).longValue(),
          "reliable history seq");
      String type = (String) row.get("type");
      if (type.equals("session.read")) {
        var invocation =
            db.queryForMap(
                "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND id=? AND task_id=?",
                row.get("source_invocation_id"),
                subject);
        String original = (String) invocation.get("output_snapshot");
        String digest = Payloads.hash(original);
        require(
            digest.equals(invocation.get("output_digest")), "invocation original output digest");
        require(
            invocation.get("input_digest").equals(row.get("source_digest")),
            "read original input source digest");
        require(
            wire.path("data").path("output_sha256").asText().equals(digest),
            "event receipt carries original output digest");
        require(
            wire.path("data").path("output_bytes").asLong()
                == original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "event receipt original bytes");
        String table =
            JdbcSessionScopes.task(taskRow())
                ? "invocation_session_scope_query"
                : "invocation_session_read";
        var binding =
            db.queryForMap(
                "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND invocation_id=?",
                row.get("source_invocation_id"));
        require(digest.equals(binding.get("output_digest")), "binding original output digest");
        System.out.println(
            "READ_ORIGINAL "
                + Payloads.json(
                    evidenceValue(Map.of("raw", original, "digest", digest, "binding", binding))));
      }

      if (type.equals("user.message"))
        require(
            db.queryForObject(
                    "SELECT request_digest FROM task WHERE tenant_id='tenant-a' AND id=?",
                    String.class,
                    subject)
                .equals(row.get("source_digest")),
            "accepted request digest");
      if (type.equals("step.started") || type.equals("model.response")) {
        var st =
            db.queryForMap(
                "SELECT * FROM step WHERE tenant_id='tenant-a' AND id=? AND task_id=?",
                row.get("source_step_id"),
                subject);
        String original =
            (String) st.get(type.equals("step.started") ? "request_snapshot" : "response_snapshot");
        require(
            Payloads.hash(original)
                .equals(st.get(type.equals("step.started") ? "request_digest" : "response_digest")),
            "step original source bytes");
        require(
            st.get(type.equals("step.started") ? "request_digest" : "response_digest")
                .equals(row.get("source_digest")),
            "source digest exact");
        require(
            wire.path("source").path("step_id").asText().equals(row.get("source_step_id")),
            "step source id exact");
      }
      if (row.get("fact_key").equals("terminal")) {
        var terminal =
            db.queryForMap(
                "SELECT event_id,type,status,code FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id='terminal'",
                subject);
        require(
            Payloads.hash(Payloads.json(terminal)).equals(row.get("source_digest")),
            "terminal original four-column map digest");
        System.out.println("TERMINAL_ORIGINAL " + Payloads.json(terminal));
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("history", h);
    result.put("events", events);
    result.put(
        "reliable",
        db.queryForList(
            "SELECT * FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq", subject));
    return Payloads.json(evidenceValue(result));
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

  private static void fact(String value) {
    try {
      var method = JdbcLedger.class.getDeclaredMethod("sessionFact", Map.class, String.class);
      method.setAccessible(true);
      method.invoke(ledger, selectedTask, value);
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
                    "read_1", "function", "session_event_read", "{\"seq\":0}")),
            17,
            usage);
    var call =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
            "0:read_1", "read_1", "session_event_read", "{\"seq\":0}");
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
                                  injected++;
                                  throw new SQLException("PRIVATE_METADATA_FAILURE", "HY000", 1644);
                                }
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
    phase = args.length > 2 ? args[2] : "all";
    trial(
        "read",
        () -> {
          dispatched();
          respond();
          proofSubject = null;
        },
        () -> {
          var call =
              new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                  "0:read_1", "read_1", "session_event_read", "{\"seq\":0}");
          var pin = ledger.toolSnapshot(lease).pins().get("session_event_read");
          String raw =
              JdbcSessionScopes.task(selectedTask)
                  ? ledger.scopedOrResult(lease, step.id(), call, pin.version(), pin.digest())
                  : ledger.readOrResult(lease, step.id(), call, pin.version(), pin.digest());
          System.out.println("READ_OUTPUT " + raw);
          return proof(task);
        },
        "OK");
    trial("admission", () -> {}, SessionFactScenario::admission, "OK");
    trial(
        "prepared",
        SessionFactScenario::claim,
        () -> {
          step = ledger.prepare(lease, request);
          return proof(task);
        },
        "OK");
    trial("responded", SessionFactScenario::dispatched, SessionFactScenario::respond, "OK");
    trial(
        "terminal",
        () -> {},
        () -> {
          ledger.cancel(new Identity("tenant-a", "alice"), task);
          return proof(task);
        },
        "OK");
    trial(
        "event-duplicate",
        () -> {},
        () -> {
          event("accepted", "task.accepted");
          require(sql.size() == 1, "duplicate event returns before account");
          return proof(task);
        },
        "OK");
    trial(
        "append-duplicate",
        () -> {},
        () -> {
          long seq = append("accepted-user", "user.message");
          require(seq == 0, "idempotent first seq");
          require(
              sql.stream()
                  .noneMatch(r -> r.get("text").toString().startsWith("SELECT CURRENT_TIMESTAMP")),
              "no append clock on replay");
          require(
              sql.stream()
                  .anyMatch(r -> r.get("text").toString().startsWith("SELECT metadata_bytes")),
              "account after idempotent append");
          return proof(task);
        },
        "OK");
    trial(
        "unknown",
        () -> {},
        () -> {
          event("unknown", "task.started");
          return proof(task);
        },
        "OK");
    trial(
        "not-responded",
        SessionFactScenario::prepared,
        () -> {
          event("response:" + step.id(), "model.responded");
          require(
              sql.stream()
                  .noneMatch(
                      r -> r.get("text").toString().startsWith("SELECT * FROM task_attempt")),
              "nonresponded no attempt query");
          return proof(task);
        },
        "OK");
    trial(
        "missing-step",
        () -> {},
        () -> {
          fact("prepared:missing");
          return "unreachable";
        },
        "NOT_FOUND");
    trial(
        "missing-attempt",
        SessionFactScenario::prepared,
        () -> {
          selectedTask.put("current_attempt_id", "missing");
          fact("prepared:" + step.id());
          return "unreachable";
        },
        "NOT_FOUND");
    trial(
        "missing-terminal",
        () -> {},
        () -> {
          fact("terminal");
          return "unreachable";
        },
        "NOT_FOUND");
    trial(
        "null-non-session",
        () -> {},
        () -> {
          selectedTask.put("capabilities", "web_fetch");
          fact(null);
          return "short-circuit";
        },
        "OK");
    trial(
        "null-fact",
        () -> {},
        () -> {
          fact(null);
          return "unreachable";
        },
        "java.lang.NullPointerException");
    trial(
        "event-cap",
        () ->
            db.update(
                "UPDATE task SET next_event_seq=254 WHERE tenant_id='tenant-a' AND id=?", task),
        () -> {
          event("unknown", "task.started");
          return "unreachable";
        },
        "SESSION_HISTORY_LIMIT");
    trial(
        "terminal-event-cap",
        () ->
            db.update(
                "UPDATE task SET next_event_seq=256 WHERE tenant_id='tenant-a' AND id=?", task),
        () -> {
          event("terminal", "task.cancelled");
          return "unreachable";
        },
        "SESSION_HISTORY_LIMIT");
    trial(
        "append-cap",
        () ->
            db.update(
                "UPDATE task_session_history SET next_seq=126 WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        () -> Long.toString(append("cap", "user.message")),
        "SESSION_HISTORY_LIMIT");
    trial(
        "terminal-reserve",
        () ->
            db.update(
                "UPDATE task_session_history SET next_seq=127 WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        () -> {
          ledger.cancel(new Identity("tenant-a", "alice"), task);
          return proof(task);
        },
        "OK");
    trial(
        "terminal-cap",
        () ->
            db.update(
                "UPDATE task_session_history SET next_seq=128 WHERE tenant_id='tenant-a' AND task_id=?",
                task),
        () -> {
          ledger.cancel(new Identity("tenant-a", "alice"), task);
          return "unreachable";
        },
        "SESSION_HISTORY_LIMIT");
    trial(
        "select-sql",
        SessionFactScenario::claim,
        () -> {
          fault = "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?";
          ledger.prepare(lease, request);
          return "unreachable";
        },
        "SQL_FAILURE");
    for (String[] spec :
        List.of(
            new String[] {"event-sql", "INSERT INTO event"},
            new String[] {"append-sql", "INSERT INTO session_public_event"},
            new String[] {"account-sql", "UPDATE task_session_history SET metadata_bytes="}))
      trial(
          spec[0],
          () -> {},
          () -> {
            fault = spec[1];
            ledger.cancel(new Identity("tenant-a", "alice"), task);
            return "unreachable";
          },
          "SQL_FAILURE");
  }
}
