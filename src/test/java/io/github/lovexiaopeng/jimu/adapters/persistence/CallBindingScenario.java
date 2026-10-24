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

/** Exact old/new JARs: actual worker proofs and actual modelResponded failure/success rollback. */
public final class CallBindingScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static String pending;
  private static final List<String> trace = new ArrayList<>();
  private static int failAt, inserts, written;

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
        var connection = super.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, m, args) -> {
                  Object result = invoke(connection, m, args);
                  if (m.getName().equals("prepareStatement")
                      && result instanceof PreparedStatement statement) {
                    String sql = args[0].toString().replaceAll("\\s+", " ");
                    boolean binding =
                        sql.matches(
                            "(?i)INSERT INTO invocation_session_(read|search|trace|scope_query)\\s*\\(.*");
                    return Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        (sp, sm, sv) -> {
                          boolean execute = sm.getName().startsWith("execute");
                          if (execute && binding && ++inserts == failAt)
                            throw new SQLException("PRIVATE_BINDING_INSERT_FAILURE", "HY000", 1644);
                          Object value = invoke(statement, sm, sv);
                          if (execute) {
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
      String key =
          table.equals("task")
              ? "id"
              : List.of(
                          "task_session_scope",
                          "task_session_scope_target",
                          "step_session_scope",
                          "invocation_session_scope_query")
                      .contains(table)
                  ? "caller_task_id"
                  : "task_id";
      facts.put(
          table,
          db.queryForList(
                  "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?",
                  pending)
              .toString());
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
            System.nanoTime() + 2_000_000_000L,
            scoped ? new QueryResources(0, 0, 0) : null);
  }

  private static void bindings(String task) {
    for (String table :
        List.of(
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "invocation_session_scope_query")) {
      String key = table.equals("invocation_session_scope_query") ? "caller_task_id" : "task_id";
      for (var stored :
          db.queryForList(
              "SELECT b.*,i.ordinal,i.slot FROM "
                  + table
                  + " b JOIN invocation i ON i.tenant_id=b.tenant_id AND i.id=b.invocation_id WHERE b.tenant_id='tenant-a' AND b."
                  + key
                  + "=? ORDER BY i.ordinal",
              task)) {
        var value = new LinkedHashMap<>(stored);
        value.remove("invocation_id");
        value.remove("created_at");
        value.replaceAll(
            (fieldName, v) -> v instanceof java.time.temporal.TemporalAccessor ? v.toString() : v);
        String prefix =
            table.equals("invocation_session_search") ? "normalized_query" : "normalized";
        if (value.get(prefix + "_snapshot") instanceof String raw) {
          require(
              Payloads.hash(raw).equals(value.get(prefix + "_digest")), "normalized raw digest");
          require(
              raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  == ((Number) value.get(prefix + "_bytes")).longValue(),
              "normalized raw byte length");
          // Raw original Map.of order is separately logged; JSON semantic comparison only.
          System.out.println("NORMALIZED " + value.get("ordinal") + " " + raw);
          value.put(
              prefix + "_snapshot",
              Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
          value.remove(prefix + "_digest");
        }
        System.out.println(
            "RESULT binding:" + table + ":" + value.get("ordinal") + " " + Payloads.json(value));
      }
    }
  }

  private static void exercise(DataSource source, Response response, boolean failure)
      throws Exception {
    inserts = 0;
    written = 0;
    trace.clear();
    failAt = failure ? Math.min(2, response.calls().size()) : 0;
    String before = facts();
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
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
          try {
            var view = active(JdbcSessionScopes.task(task));
            view.modelResponded(
                lease,
                (String) step.get("id"),
                (String) step.get("dispatch_id"),
                Payloads.json(response),
                calls,
                response.tokens(),
                response.usage());
            require(!failure, "injected INSERT must fail");
            require(written == calls.size(), "all actual bindings inserted in ordinal iteration");
            bindings(pending);
            require(
                db.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND type='model.responded'",
                        Long.class,
                        pending)
                    > 0,
                "actual response event written");
          } catch (DataAccessException expected) {
            if (!failure) throw expected;
            Throwable cause = expected;
            while (cause.getCause() != null) cause = cause.getCause();
            require(
                cause instanceof SQLException
                    && cause.getMessage().contains("PRIVATE_BINDING_INSERT_FAILURE"),
                "injected actual binding failure");
            require(
                inserts == failAt && written == failAt - 1,
                "earlier bindings executed before failure");
            require(
                trace.stream()
                    .anyMatch(
                        s -> s.startsWith("UPDATE step SET") && s.contains("response_snapshot=")),
                "response stored before binding fault");
            require(
                trace.stream().anyMatch(s -> s.startsWith("INSERT INTO invocation ")),
                "actual invocation inserted before binding fault");
            require(
                trace.stream().anyMatch(s -> s.startsWith("UPDATE reservation SET")),
                "token settlement executed before binding fault");
          } catch (Exception unexpected) {
            throw new IllegalStateException(unexpected);
          } finally {
            status.setRollbackOnly();
          }
          return null;
        });
    require(
        before.equals(facts()),
        "outer rollback restores exact response/dispatch/slots/bindings/events/reservations/budgets/history/scope facts");
    System.out.println(
        "RESULT "
            + (failure ? "failure" : "success")
            + " "
            + Payloads.json(
                Map.of("attempted", inserts, "written", written, "allFactsRestored", true)));
  }

  public static void main(String[] args) throws Exception {
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
    String completed = args[1];
    pending = args[2];
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", completed);
    for (var event :
        db.queryForList(
            "SELECT * FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
            completed)) {
      var view = active(JdbcSessionScopes.task(task));
      String raw =
          view.sessionTransaction(
              () -> Payloads.json(view.sessions.verify(task, event)),
              System.nanoTime() + 2_000_000_000L);
      require(raw.equals(event.get("event_snapshot")), "actual worker frozen event proof");
      System.out.println(
          "RESULT event:" + event.get("seq") + " " + Payloads.json(Payloads.hash(raw)));
    }
    bindings(completed);
    var response = Payloads.read(Files.readString(Path.of(args[3])), Response.class);
    exercise(shared, response, true);
    Files.writeString(Path.of(args[4] + "-failure.json"), Payloads.json(trace));
    exercise(shared, response, false);
    Files.writeString(Path.of(args[4] + "-success.json"), Payloads.json(trace));
  }
}
