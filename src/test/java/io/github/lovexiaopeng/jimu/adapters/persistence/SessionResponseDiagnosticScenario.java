package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 私有原生及 scope 实际响应链；已知拒绝与 SQL 故障逐笔外层回滚。 */
public final class SessionResponseDiagnosticScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static String pending;
  private static String originalResponse;
  private static final List<String> trace = new ArrayList<>();
  private static int failAt, inserts, written;
  private static String mode;
  private static int injected;
  private static boolean inCall;
  private static final List<Write> writes = new ArrayList<>();

  private record Write(String sql, Map<Integer, Object> parameters, int count) {}

  private static long timelineOrigin;
  private static long absoluteDeadline;
  private static final List<Map<String, Object>> lifecycle = new ArrayList<>();
  private static final Map<String, Object> outcome = new LinkedHashMap<>();

  private static Map<String, Object> exception(Throwable error) {
    if (error == null) return Map.of();
    var text = new java.io.StringWriter();
    error.printStackTrace(new java.io.PrintWriter(text));
    var sql = new ArrayList<Map<String, Object>>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var pending = new java.util.ArrayDeque<Throwable>();
    pending.add(error);
    while (!pending.isEmpty()) {
      var item = pending.removeFirst();
      if (!seen.add(item)) continue;
      if (item instanceof SQLException jdbc) {
        var row = new LinkedHashMap<String, Object>();
        row.put("class", item.getClass().getName());
        row.put("message", item.getMessage());
        row.put("SQLState", jdbc.getSQLState());
        row.put("vendorCode", jdbc.getErrorCode());
        sql.add(row);
        if (jdbc.getNextException() != null) pending.add(jdbc.getNextException());
      }
      if (item.getCause() != null) pending.add(item.getCause());
      pending.addAll(Arrays.asList(item.getSuppressed()));
    }
    return Map.of(
        "class", error.getClass().getName(), "stack", text.toString(), "sqlExceptions", sql);
  }

  private static void save(String prefix) {
    outcome.put("timelineOriginNanos", timelineOrigin);
    outcome.put("absoluteDeadlineNanos", absoluteDeadline);
    outcome.put("timeline", timeline);
    outcome.put("connectionLifecycle", lifecycle);
    // Product has ended; this observation performs no SQL and never changes any timeout.
    String raw = Payloads.json(outcome);
    System.out.println("DIAGNOSTIC " + raw);
    try {
      Files.writeString(Path.of(prefix + "-outcome.json"), raw);
    } catch (Throwable evidenceError) {
      System.err.println("EVIDENCE_WRITE_FAILURE");
      evidenceError.printStackTrace();
      System.err.println("PRESERVED_DIAGNOSTIC " + raw);
    }
  }

  private static final List<Map<String, Object>> timeline = new ArrayList<>();

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static DataSource observed(DataSource source) {
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        ResponsePhaseAgent.mark("ENTER datasource.getConnection");
        Connection connection;
        try {
          connection = super.getConnection();
          ResponsePhaseAgent.mark("RETURN datasource.getConnection");
        } catch (SQLException error) {
          ResponsePhaseAgent.failed(error, "THROW datasource.getConnection");
          throw error;
        }
        return (Connection)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, m, args) -> {
                  if (m.getName().equals("prepareStatement")
                      && args[0].equals("SELECT CURRENT_TIMESTAMP(6)"))
                    args[0] = "SELECT CAST('2026-10-25 02:45:00.123456' AS DATETIME(6))";
                  Object result;
                  boolean observed =
                      List.of(
                              "rollback",
                              "commit",
                              "close",
                              "setNetworkTimeout",
                              "setAutoCommit",
                              "setReadOnly")
                          .contains(m.getName());
                  long connectionStart = System.nanoTime();
                  Throwable connectionError = null;
                  if (observed) ResponsePhaseAgent.mark("ENTER connection." + m.getName());
                  try {
                    result = invoke(connection, m, args);
                    if (observed) ResponsePhaseAgent.mark("RETURN connection." + m.getName());
                  } catch (Throwable error) {
                    if (observed)
                      ResponsePhaseAgent.failed(error, "THROW connection." + m.getName());
                    connectionError = error;
                    throw error;
                  } finally {
                    if (observed) {
                      var row = new LinkedHashMap<String, Object>();
                      row.put("connection", System.identityHashCode(connection));
                      row.put("method", m.getName());
                      row.put("inProductionCall", inCall);
                      row.put("elapsedMicros", (System.nanoTime() - timelineOrigin) / 1000);
                      row.put("durationMicros", (System.nanoTime() - connectionStart) / 1000);
                      row.put(
                          "arguments",
                          args == null
                              ? List.of()
                              : Arrays.stream(args).map(String::valueOf).toList());
                      row.put("error", exception(connectionError));
                      lifecycle.add(row);
                    }
                  }
                  if (result instanceof java.sql.DatabaseMetaData metadata) {
                    return Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.DatabaseMetaData.class},
                        (mp, mm, ma) -> {
                          boolean productName = mm.getName().equals("getDatabaseProductName");
                          if (productName)
                            ResponsePhaseAgent.mark("ENTER metadata." + mm.getName());
                          try {
                            Object value = invoke(metadata, mm, ma);
                            if (productName)
                              ResponsePhaseAgent.mark("RETURN metadata." + mm.getName());
                            return value;
                          } catch (Throwable error) {
                            ResponsePhaseAgent.failed(error, "THROW metadata." + mm.getName());
                            throw error;
                          }
                        });
                  }
                  if (m.getName().equals("prepareStatement")
                      && result instanceof PreparedStatement statement) {
                    String sql = args[0].toString().replaceAll("\\s+", " ");
                    boolean binding =
                        sql.matches(
                            "(?i)INSERT INTO invocation_session_(read|search|trace|scope_query)\\s*\\(.*");
                    var parameters = new java.util.TreeMap<Integer, Object>();
                    return Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        (sp, sm, sv) -> {
                          if (sm.getName().equals("getConnection")) return p;
                          if (sm.getName().startsWith("set")
                              && sv != null
                              && sv.length >= 2
                              && sv[0] instanceof Integer index)
                            parameters.put(index, sm.getName().equals("setNull") ? null : sv[1]);
                          boolean execute = sm.getName().startsWith("execute");
                          if (execute
                              && "storeFault".equals(mode)
                              && sql.startsWith("UPDATE step SET")
                              && sql.contains("response_snapshot=")) {
                            injected++;
                            throw new SQLException("PRIVATE_RESPONSE_STORE_FAILURE", "HY000", 1644);
                          }
                          if (execute && binding && ++inserts == failAt)
                            throw new SQLException("PRIVATE_BINDING_INSERT_FAILURE", "HY000", 1644);
                          long start = System.nanoTime();
                          boolean account =
                              execute
                                  && sql.startsWith(
                                      "UPDATE task_session_history SET metadata_bytes=");
                          if (account)
                            ResponsePhaseAgent.mark(
                                "ENTER account UPDATE remainingNanos="
                                    + (absoluteDeadline - start));
                          Object value;
                          try {
                            if (account
                                && inCall
                                && Boolean.getBoolean("diagnostic.accountFault")) {
                              injected++;
                              throw new SQLException(
                                  "PRIVATE_ACCOUNT_TRANSLATION_CONTROL",
                                  "08S01",
                                  0,
                                  new java.net.SocketTimeoutException(
                                      "PRIVATE synthetic cause; no socket changed"));
                            }
                            value = invoke(statement, sm, sv);
                            if (account) ResponsePhaseAgent.mark("RETURN account UPDATE");
                          } catch (Throwable error) {
                            if (execute) ResponsePhaseAgent.failed(error, "THROW SQL " + sql);
                            throw error;
                          } finally {
                            if (execute)
                              timeline.add(
                                  Map.of(
                                      "sql",
                                      sql,
                                      "elapsedMicros",
                                      (System.nanoTime() - timelineOrigin) / 1000,
                                      "durationMicros",
                                      (System.nanoTime() - start) / 1000));
                          }
                          if (execute) {
                            if (inCall && (sql.startsWith("INSERT ") || sql.startsWith("UPDATE ")))
                              writes.add(
                                  new Write(
                                      sql,
                                      new java.util.TreeMap<>(parameters),
                                      statement.getUpdateCount()));
                            trace.add(sql);
                            if (binding) written++;
                          }
                          return value;
                        });
                  }
                  return result;
                });
      }
    };
  }

  private static String facts() {
    var facts = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "task_attempt",
            "event",
            "session_public_event",
            "task_session_history",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_scope_query")) {
      facts.put(
          table,
          db.queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a'").stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    facts.put(
        "budget", db.queryForList("SELECT * FROM tenant_budget ORDER BY tenant_id").toString());
    facts.put(
        "evaluation",
        db.queryForList("SELECT * FROM evaluation_budget ORDER BY tenant_id,id").toString());
    return Payloads.json(facts);
  }

  private static JdbcLedger active(boolean scoped) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger)
        method.invoke(
            ledger,
            absoluteDeadline = System.nanoTime() + 2_000_000_000L,
            scoped ? new QueryResources(0, 0, 0) : null);
  }

  private static Map<String, Object> columns(Write write) {
    int start = write.sql().indexOf('('), end = write.sql().indexOf(')', start);
    var result = new LinkedHashMap<String, Object>();
    if (start < 0 || end < 0) return result;
    String[] names = write.sql().substring(start + 1, end).split(",");
    require(
        names.length == write.parameters().size(),
        "captured INSERT parameters match explicit columns");
    for (int i = 0; i < names.length; i++)
      result.put(names[i].trim().replace("`", ""), write.parameters().get(i + 1));
    return result;
  }

  private static void proofs(Response response) {
    int bindings = 0, events = 0;
    var invocations = new LinkedHashMap<Object, Map<String, Object>>();
    for (var write : writes) {
      if (write.sql().startsWith("INSERT INTO invocation ")) {
        require(write.count() == 1, "actual invocation INSERT affected one row");
        var row = columns(write);
        invocations.put(row.get("id"), row);
      }
    }
    for (var write : writes) {
      if (write
          .sql()
          .matches("(?i)INSERT INTO invocation_session_(read|search|trace|scope_query)\\s*\\(.*")) {
        require(write.count() == 1, "actual binding INSERT affected one row");
        var value = columns(write);
        String prefix =
            write.sql().startsWith("INSERT INTO invocation_session_search")
                ? "normalized_query"
                : "normalized";
        if (value.get(prefix + "_snapshot") instanceof String raw) {
          require(
              Payloads.hash(raw).equals(value.get(prefix + "_digest")),
              "normalized original JDBC bytes hash");
          require(
              raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  == ((Number) value.get(prefix + "_bytes")).longValue(),
              "normalized original JDBC length");
          System.out.println("NORMALIZED " + raw);
          value.put(
              prefix + "_snapshot",
              Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
          value.remove(prefix + "_digest");
        }
        var invocation = invocations.get(value.remove("invocation_id"));
        require(invocation != null, "binding matches actual invocation INSERT");
        var expected = response.calls().get(bindings);
        require(
            ((Number) invocation.get("ordinal")).intValue() == bindings + 1
                && (bindings + ":" + expected.id()).equals(invocation.get("slot"))
                && expected.id().equals(invocation.get("provider_id")),
            "original invocation ordinal and slot order");
        value.put("ordinal", invocation.get("ordinal"));
        value.put("slot", invocation.get("slot"));
        value.replaceAll(
            (k, v) -> v instanceof java.time.temporal.TemporalAccessor ? v.toString() : v);
        System.out.println("RESULT binding " + Payloads.json(value));
        bindings++;
      }
      if (write.sql().startsWith("INSERT INTO session_public_event")) {
        var event = columns(write);
        if (!"model.response".equals(event.get("type"))) continue;
        require(write.count() == 1, "actual response event INSERT affected one row");
        String raw = (String) event.get("event_snapshot");
        require(
            Payloads.hash(raw).equals(event.get("event_digest")),
            "response original JDBC event digest");
        require(
            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                == ((Number) event.get("event_bytes")).longValue(),
            "response original JDBC event length");
        require(
            Payloads.hash(Payloads.json(response)).equals(event.get("source_digest")),
            "response original source digest");
        System.out.println(
            "PROOF "
                + Payloads.json(
                    Map.of(
                        "raw",
                        raw,
                        "sha256",
                        event.get("event_digest"),
                        "bytes",
                        event.get("event_bytes"),
                        "proofBoundary",
                        "Original executed INSERT parameters and update count; no additional SELECT readback")));
        System.out.println("RESULT responseEvent " + raw);
        events++;
      }
    }
    require(
        bindings == response.calls().size() && events == 1,
        "all actual binding and response event writes captured");
    require(
        writes.stream()
            .filter(w -> w.sql().startsWith("INSERT INTO event"))
            .map(SessionResponseDiagnosticScenario::columns)
            .anyMatch(row -> "model.responded".equals(row.get("type"))),
        "actual reliable response event executed before rollback");
  }

  private static void exercise(DataSource source, Response response, String trial)
      throws Exception {
    mode = trial;
    injected = 0;
    boolean failure = trial.equals("bindingFault") || trial.equals("storeFault");
    boolean rejection = trial.equals("oversize") || trial.equals("slotMismatch");
    writes.clear();
    inserts = 0;
    written = 0;
    trace.clear();
    timeline.clear();
    failAt = trial.equals("bindingFault") ? Math.min(2, response.calls().size()) : 0;
    String before = facts();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var errors = new Throwable[1];
    outcome.clear();
    lifecycle.clear();
    outcome.put("trial", trial);
    outcome.put("driverStage", "actualModelResponded");
    outcome.put("production", "NOT_ENTERED");
    Throwable transactionError = null;
    try {
      tx.execute(
          status -> {
            // Fixture-only renewal, rolled back with the trial. Product query deadline stays 2
            // seconds.
            db.update(
                "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND,deadline=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND WHERE tenant_id='tenant-a' AND id=?",
                pending);
            var task =
                db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", pending);
            var step =
                db.queryForMap(
                    "SELECT * FROM step WHERE tenant_id='tenant-a' AND task_id=? AND response_snapshot IS NULL ORDER BY ordinal LIMIT 1",
                    pending);
            var lease =
                new Lease(
                    "tenant-a",
                    (String) task.get("owner_actor_id"),
                    pending,
                    (String) task.get("current_attempt_id"),
                    ((Number) task.get("fence")).longValue(),
                    (String) task.get("worker_id"),
                    ((LocalDateTime) task.get("deadline")).toInstant(ZoneOffset.UTC),
                    (String) task.get("goal"),
                    Arrays.asList(((String) task.get("capabilities")).split(",")),
                    ((Number) task.get("installation_epoch")).longValue());
            var calls = new ArrayList<Call>();
            for (int i = 0; i < response.calls().size(); i++) {
              var c = response.calls().get(i);
              calls.add(new Call(i + ":" + c.id(), c.id(), c.name(), c.arguments()));
            }
            if (trial.equals("slotMismatch"))
              calls.set(
                  0,
                  new Call(
                      "wrong",
                      calls.getFirst().providerId(),
                      calls.getFirst().tool(),
                      calls.getFirst().input()));
            try {
              timelineOrigin = System.nanoTime();
              inCall = true;
              var view = active(JdbcSessionScopes.task(task));
              ResponsePhaseAgent.mark("ENTER driver.modelResponded");
              view.modelResponded(
                  lease,
                  (String) step.get("id"),
                  (String) step.get("dispatch_id"),
                  trial.equals("oversize") ? "x".repeat(16385) : originalResponse,
                  calls,
                  response.tokens(),
                  response.usage());
              outcome.put("production", "NORMAL_RETURN");
              ResponsePhaseAgent.mark("RETURN driver.modelResponded");
            } catch (Throwable caught) {
              ResponsePhaseAgent.failed(caught, "CATCH driver.modelResponded");
              errors[0] = caught;
              outcome.put(
                  "production",
                  caught instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure
                      ? "BUSINESS_REJECTION"
                      : "EXCEPTION");
            } finally {
              long ended = System.nanoTime();
              inCall = false;
              outcome.put("productionEndMicros", (ended - timelineOrigin) / 1000);
              outcome.put("deadlineRemainingMicrosAtEnd", (absoluteDeadline - ended) / 1000);
              // No proof SELECT or output before rollback: leave the product's socket/deadline
              // settings untouched and collect proofs from already executed JDBC parameters.
              status.setRollbackOnly();
              ResponsePhaseAgent.mark("RETURN outer transaction callback rollbackOnly");
            }
            return null;
          });
      outcome.put("outerTransaction", "ROLLBACK_RETURNED");
    } catch (Throwable rollbackError) {
      transactionError = rollbackError;
      outcome.put("outerTransaction", "EXCEPTION");
      outcome.put("transactionException", exception(rollbackError));
    } finally {
      outcome.put("primary", exception(errors[0]));
      outcome.put("syntheticAccountFaults", injected);
      save(System.getProperty("diagnostic.prefix") + "-" + trial);
    }
    if (transactionError != null) {
      if (errors[0] != null && errors[0] != transactionError)
        transactionError.addSuppressed(errors[0]);
      throw new IllegalStateException(
          "PRIVATE_DIAGNOSTIC_OUTER_TRANSACTION_FAILED_NO_RECOVERY", transactionError);
    }
    String after = facts();
    require(
        before.equals(after),
        "outer rollback restores exact response/dispatch/slots/bindings/events/reservations/budgets/history/scope facts");
    System.out.println(
        "ROLLBACK " + Payloads.json(Map.of("trial", trial, "before", before, "after", after)));
    if (trial.equals("success")) {
      if (Boolean.getBoolean("diagnostic.accountFault")) {
        require(
            injected == 1, "one private account translation control; original SQL not executed");
        require(
            errors[0] instanceof DataAccessException,
            "actual MyBatis translates control SQL failure");
        require(
            exception(errors[0]).toString().contains("PRIVATE_ACCOUNT_TRANSLATION_CONTROL"),
            "original synthetic primary preserved");
      } else {
        require(errors[0] == null, "actual response completed before immediate outer rollback");
        proofs(response);
      }
    } else if (rejection) {
      require(
          errors[0] instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure,
          "original known refusal type");
      String code = trial.equals("oversize") ? "SESSION_HISTORY_LIMIT" : "TOOL_INPUT_INVALID";
      require(
          ((io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure) errors[0]).code().equals(code),
          "original known refusal code");
      require(
          writes.stream().anyMatch(w -> w.sql().startsWith("UPDATE reservation SET")),
          "known usage settled before refusal");
      require(
          writes.stream().noneMatch(w -> w.sql().startsWith("INSERT INTO invocation ")),
          "known refusal creates no slots");
      require(
          writes.stream()
              .filter(w -> w.sql().startsWith("INSERT INTO event"))
              .map(SessionResponseDiagnosticScenario::columns)
              .anyMatch(
                  row -> "model.rejected".equals(row.get("type")) && code.equals(row.get("code"))),
          "actual reliable rejection event with original code");
      require(
          writes.stream()
              .anyMatch(
                  w ->
                      w.sql().startsWith("UPDATE model_dispatch SET")
                          && w.parameters().containsValue(code)),
          "actual dispatch rejection stored before outside-inner-transaction error");
    } else if (failure) {
      require(errors[0] instanceof DataAccessException, "original SQL failure category");
      Throwable cause = errors[0];
      while (cause.getCause() != null) cause = cause.getCause();
      require(
          cause instanceof SQLException
              && (cause.getMessage().contains("PRIVATE_BINDING_INSERT_FAILURE")
                  || cause.getMessage().contains("PRIVATE_RESPONSE_STORE_FAILURE")),
          "exact injected SQL cause");
      if (trial.equals("storeFault"))
        require(injected == 1 && written == 0, "single store fault before slots");
      else {
        require(
            inserts == failAt && written == failAt - 1,
            "single binding fault and prior insert count");
        require(
            writes.stream()
                .anyMatch(
                    w ->
                        w.sql().startsWith("UPDATE step SET")
                            && w.sql().contains("response_snapshot=")),
            "response written before binding fault");
        require(
            writes.stream().anyMatch(w -> w.sql().startsWith("INSERT INTO invocation ")),
            "invocation written before binding fault");
        require(
            writes.stream().anyMatch(w -> w.sql().startsWith("UPDATE reservation SET")),
            "tokens settled before binding fault");
      }
    }
    System.out.println(
        "RESULT "
            + trial
            + " "
            + Payloads.json(
                Map.of("attempted", inserts, "written", written, "allFactsRestored", true)));
  }

  public static void main(String[] args) throws Exception {
    timelineOrigin = System.nanoTime();
    try {
      run(args);
    } catch (Throwable primary) {
      outcome.put("driverException", exception(primary));
      save(args[3] + "-driver");
      throw primary;
    }
  }

  private static void run(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        observed(
            new DriverManagerDataSource(
                env.getRequiredProperty("jimu.db.url"),
                env.getRequiredProperty("jimu.db.user"),
                env.getRequiredProperty("jimu.db.password")));
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    pending = args[1];
    originalResponse = Files.readString(Path.of(args[2]));
    var response = Payloads.read(originalResponse, Response.class);
    System.out.println(
        "RESPONSE_SOURCE "
            + Payloads.json(
                Map.of(
                    "sha256",
                    Payloads.hash(originalResponse),
                    "bytes",
                    originalResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
    if (Boolean.getBoolean("diagnostic.cold")) {
      exercise(shared, response, "success");
      return;
    }
    // A bounded read-only preflight warmup is separate from actual response trials.
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", pending);
    var calls = new ArrayList<Call>();
    for (int i = 0; i < response.calls().size(); i++) {
      var c = response.calls().get(i);
      calls.add(new Call(i + ":" + c.id(), c.id(), c.name(), c.arguments()));
    }
    var tx = new TransactionTemplate(new DataSourceTransactionManager(shared));
    outcome.put("driverStage", "preflightWarmup");
    tx.execute(
        status -> {
          try {
            var view = active(JdbcSessionScopes.task(task));
            view.sessions.response(task, originalResponse, calls);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            status.setRollbackOnly();
          }
          return null;
        });
    System.out.println("PASS separate preflight warmup rolled back");
    String beforeWarm = facts();
    outcome.put("driverStage", "accountWarmup");
    timelineOrigin = System.nanoTime();
    tx.execute(
        status -> {
          try {
            active(JdbcSessionScopes.task(task)).sessions.account(task, false);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            status.setRollbackOnly();
          }
          return null;
        });
    require(beforeWarm.equals(facts()), "separate accounting warmup captured facts restored");
    if (JdbcSessionScopes.task(task)) {
      outcome.put("driverStage", "normalizedWarmup");
      var step =
          db.queryForMap(
              "SELECT * FROM step WHERE tenant_id='tenant-a' AND task_id=? AND response_snapshot IS NULL ORDER BY ordinal LIMIT 1",
              pending);
      for (var call : calls) {
        tx.execute(
            status -> {
              try {
                active(true).sessions.normalizedScope(task, step, call.tool(), call.input());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              } finally {
                status.setRollbackOnly();
              }
              return null;
            });
      }
      require(
          beforeWarm.equals(facts()),
          "separate scope normalization warmup captured facts restored");
    }
    Files.writeString(Path.of(args[3] + "-warmup-timing.json"), Payloads.json(timeline));
    for (String trial : System.getProperty("diagnostic.trials", "success").lines().toList()) {
      Throwable primary = null;
      try {
        exercise(shared, response, trial);
      } catch (Throwable error) {
        primary = error;
        throw error;
      } finally {
        try {
          Files.writeString(Path.of(args[3] + "-" + trial + ".json"), Payloads.json(trace));
          Files.writeString(
              Path.of(args[3] + "-" + trial + "-timing.json"), Payloads.json(timeline));
        } catch (Throwable evidenceError) {
          if (primary != null) primary.addSuppressed(evidenceError);
          else throw evidenceError;
          System.err.println("ADDITIONAL_EVIDENCE_WRITE_FAILURE");
          evidenceError.printStackTrace();
        }
      }
    }
  }
}
