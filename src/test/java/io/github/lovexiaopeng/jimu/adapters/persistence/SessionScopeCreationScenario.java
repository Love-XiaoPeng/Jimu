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
import java.sql.Statement;
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

/** Complete session admission with explicit legal source fixtures and private outer rollback. */
public final class SessionScopeCreationScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static Statement statement(Statement actual, String text, int connection) {
    var binds = new LinkedHashMap<String, Object>();
    var setters = new ArrayList<String>();
    var getters = new ArrayList<Map<String, Object>>();
    return (Statement)
        Proxy.newProxyInstance(
            SessionScopeCreationScenario.class.getClassLoader(),
            new Class<?>[] {text == null ? Statement.class : PreparedStatement.class},
            (p, m, a) -> {
              if (m.getName().startsWith("set")
                  && a != null
                  && a.length >= 2
                  && a[0] instanceof Integer) {
                setters.add(m.getName());
                binds.put(a[0].toString(), m.getName().equals("setNull") ? null : a[1]);
              }
              if (measuring && m.getName().startsWith("execute")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("connection", connection);
                row.put("statement", text == null ? "Statement" : "PreparedStatement");
                row.put("execute", m.getName());
                row.put(
                    "sql", (text == null ? a[0].toString() : text).replaceAll("\\s+", " ").strip());
                row.put("binds", new LinkedHashMap<>(binds));
                row.put("setters", new ArrayList<>(setters));
                row.put("getters", getters);
                sql.add(row);
              }
              Object value = invoke(actual, m, a);
              if (value instanceof java.sql.ResultSet result)
                return Proxy.newProxyInstance(
                    SessionScopeCreationScenario.class.getClassLoader(),
                    new Class<?>[] {java.sql.ResultSet.class},
                    (rp, rm, ra) -> {
                      Object item = invoke(result, rm, ra);
                      if (measuring
                          && rm.getName().startsWith("get")
                          && ra != null
                          && ra.length >= 1) {
                        var access = new LinkedHashMap<String, Object>();
                        access.put("method", rm.getName());
                        access.put("column", ra[0].toString());
                        access.put("type", item == null ? "NULL" : item.getClass().getName());
                        access.put("value", evidenceValue(item));
                        access.put(
                            "arguments",
                            java.util.Arrays.stream(ra)
                                .map(
                                    v ->
                                        v instanceof Class<?> type
                                            ? type.getName()
                                            : String.valueOf(v))
                                .toList());
                        getters.add(access);
                      }
                      return item;
                    });
              return value;
            });
  }

  private static Map<String, Object> proof() {
    var out = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "task_session_history",
            "task_session_scope",
            "task_session_scope_target",
            "session_public_event",
            "reservation",
            "event",
            "tenant_budget",
            "task_attempt",
            "worker_process"))
      out.put(table, db.queryForList("SELECT * FROM \u0060" + table + "\u0060 ORDER BY 1,2"));
    return out;
  }

  private static String facts() {
    var out = new LinkedHashMap<String, Object>();
    for (String table : db.queryForList("SHOW TABLES", String.class))
      out.put(
          table,
          db.queryForList("SELECT * FROM `" + table + "`").stream()
              .map(Object::toString)
              .sorted()
              .toList());
    return Payloads.json(out);
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
      return list.stream().map(SessionScopeCreationScenario::evidenceValue).toList();
    return value;
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    var before = proof();
    int prior = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
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
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    int restored = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    out.put("sessionPrior", prior);
    out.put("sessionAfter", restored);
    require(prior == restored, "session restored after " + name);
    out.put("before", before);
    out.put("after", proof());
    out.put("result", result);
    out.put("sql", new ArrayList<>(sql));
    out.put(
        "code",
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName());
    System.out.println("CALL " + Payloads.json(evidenceValue(out)));
    if (primary != null) {
      primary.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(primary)));
    }
    require(
        expected.equals(out.get("code")),
        name + " expected " + expected + " got " + out.get("code"));
  }

  private static List<Map<String, Object>> exceptionFacts(Throwable failure) {
    var queue = new java.util.ArrayDeque<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var facts = new ArrayList<Map<String, Object>>();
    queue.add(failure);
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      if (!seen.add(current)) continue;
      var fact = new LinkedHashMap<String, Object>();
      fact.put("type", current.getClass().getName());
      fact.put("message", current.getMessage());
      if (current instanceof SQLException sqlFailure) {
        fact.put("SQLState", sqlFailure.getSQLState());
        fact.put("vendor", sqlFailure.getErrorCode());
        if (sqlFailure.getNextException() != null) queue.add(sqlFailure.getNextException());
      }
      if (current.getCause() != null) queue.add(current.getCause());
      queue.addAll(java.util.Arrays.asList(current.getSuppressed()));
      facts.add(fact);
    }
    return facts;
  }

  private static String submit(String key, List<String> ids) {
    return ledger
        .submitSessionSearch(
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
            key,
            "会话 " + key,
            ids.stream().map(java.util.UUID::fromString).toList(),
            System.nanoTime() + 60_000_000_000L)
        .taskId();
  }

  private static Map<String, Object> row(String table, String id) {
    return db.queryForMap(
        "SELECT * FROM "
            + table
            + " WHERE tenant_id='tenant-a' AND "
            + (table.equals("task")
                ? "id"
                : table.equals("task_session_scope") ? "caller_task_id" : "task_id")
            + "=?",
        id);
  }

  private static void terminal(String id, int days) {
    db.update("UPDATE tenant_budget SET queued=queued-1 WHERE tenant_id='tenant-a'");
    db.update("UPDATE task SET state='SUCCEEDED' WHERE tenant_id='tenant-a' AND id=?", id);
    db.update(
        "UPDATE reservation SET state='RELEASED' WHERE tenant_id='tenant-a' AND task_id=? AND category<>'SESSION_HISTORY_STORAGE'",
        id);
    db.update(
        "UPDATE task_session_history SET retain_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? DAY) WHERE tenant_id='tenant-a' AND task_id=?",
        days,
        id);
    var scope = row("task_session_scope", id);
    var authority =
        Payloads.read(
            (String) scope.get("authority_snapshot"),
            com.fasterxml.jackson.databind.node.ObjectNode.class);
    String retain =
        ((java.time.LocalDateTime) row("task_session_history", id).get("retain_until"))
            .format(
                java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'"));
    authority.put("retain_until", retain);
    String raw = Payloads.json(authority);
    db.update(
        "UPDATE task_session_scope SET retain_until=?,authority_snapshot=?,authority_digest=?,authority_bytes=? WHERE tenant_id='tenant-a' AND caller_task_id=?",
        row("task_session_history", id).get("retain_until"),
        raw,
        Payloads.hash(raw),
        raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        id);
  }

  private static void check(String id, List<String> ids) {
    boolean wasMeasuring = measuring;
    measuring = false;
    try {
      var task = row("task", id);
      var h = row("task_session_history", id);
      var scope = row("task_session_scope", id);
      var a =
          Payloads.read(
              (String) scope.get("authority_snapshot"),
              com.fasterxml.jackson.databind.JsonNode.class);
      require(a.path("targets").size() == ids.size(), "target count");
      require(
          a.path("caller_session_id").asText().equals(id)
              && a.path("owner_actor_id").asText().equals("alice"),
          "authority identity");
      require(
          scope.get("retain_until").equals(h.get("retain_until")), "caller retain follows scope");
      require(
          Payloads.hash((String) scope.get("authority_snapshot"))
              .equals(scope.get("authority_digest")),
          "authority digest");
      long body = 0;
      for (String field : List.of("goal", "request_snapshot"))
        body += ((String) task.get(field)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      body +=
          ((String) scope.get("authority_snapshot"))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8)
              .length;
      var targets =
          db.queryForList(
              "SELECT * FROM task_session_scope_target WHERE tenant_id='tenant-a' AND caller_task_id=? ORDER BY ordinal",
              id);
      var sorted = ids.stream().sorted().toList();
      for (int i = 0; i < targets.size(); i++) {
        var target = targets.get(i);
        String sid = (String) target.get("source_task_id"),
            raw = (String) target.get("target_snapshot");
        require(sid.equals(sorted.get(i)), "API canonical target order");
        require(Payloads.hash(raw).equals(target.get("target_digest")), "target digest");
        require(
            a.path("targets")
                .get(i)
                .equals(Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class)),
            "target snapshot bytes");
        body +=
            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + sid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      }
      for (var event :
          db.queryForList(
              "SELECT event_snapshot FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=?",
              id))
        body +=
            ((String) event.get("event_snapshot"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length;
      require(
          body == ((Number) h.get("body_bytes")).longValue(),
          "all actual stored UTF8 copies charged");
      require(((Number) scope.get("candidate_count")).intValue() == ids.size(), "scope count");
      if (!ids.isEmpty()) {
        var earliest =
            ids.stream()
                .map(
                    s ->
                        (java.time.LocalDateTime)
                            row("task_session_history", s).get("retain_until"))
                .min(java.time.LocalDateTime::compareTo)
                .orElseThrow();
        require(earliest.equals(scope.get("retain_until")), "earliest source retention");
      }
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND task_id=? AND category='SESSION_HISTORY_STORAGE'",
                  Integer.class,
                  id)
              == 1,
          "original history reservation");
    } finally {
      measuring = wasMeasuring;
    }
  }

  private static void scenario() {
    ledger.sessionReadEnabled(true, List.of("SYNTHETIC_PRIVATE_SENTINEL"));
    ledger.priorSessionSearchEnabled(true);
    var ids = new ArrayList<String>();
    call(
        "empty-create",
        () -> {
          String id = submit("empty", List.of());
          ids.add(id);
          check(id, List.of());
          return id;
        },
        "OK");
    call("empty-replay", () -> submit("empty", List.of()), "OK");
    call(
        "source-two-create",
        () -> {
          String id = submit("source-two", List.of());
          ids.add(id);
          check(id, List.of());
          return id;
        },
        "OK");
    terminal(ids.get(0), 5);
    terminal(ids.get(1), 2);
    call(
        "two-source-create",
        () -> {
          String id = submit("two-sources", List.of(ids.get(1), ids.get(0)));
          ids.add(id);
          check(id, ids.subList(0, 2));
          return id;
        },
        "OK");
    call("two-source-replay", () -> submit("two-sources", List.of(ids.get(0), ids.get(1))), "OK");
    db.update(
        "UPDATE task SET owner_actor_id='carol' WHERE tenant_id='tenant-a' AND id=?",
        ids.subList(0, 2).stream().sorted().toList().getLast());
    call(
        "source-owner-refusal",
        () -> submit("denied-source", List.of(ids.get(0), ids.get(1))),
        "SESSION_SCOPE_UNAVAILABLE");
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-mcp", props));
    var plain =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    var observed =
        new DelegatingDataSource(plain) {
          int sequence;

          public Connection getConnection() throws SQLException {
            var raw = super.getConnection();
            int connection = ++sequence;
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object result = invoke(raw, m, a);
                      if (result instanceof Statement s
                          && (m.getName().equals("prepareStatement")
                              || m.getName().equals("createStatement")))
                        return statement(
                            s,
                            m.getName().equals("prepareStatement") ? a[0].toString() : null,
                            connection);
                      return result;
                    });
          }
        };
    var source =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(observed);
    db = new JdbcTemplate(source);
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);

    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {

      System.out.println(
          "PROVENANCE public paid=false v4 admission plus explicit terminal/quiescent and retention fixtures; no worker/tool/provider execution; outer rollback only");
      scenario();
    } catch (Throwable failure) {
      primary = failure;
      failure.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(failure)));
    } finally {
      try {
        transactions.rollback(tx);
        System.out.println("ROLLBACK complete");
      } catch (Throwable failure) {
        failure.printStackTrace(System.out);
        if (primary == null) primary = failure;
        else primary.addSuppressed(failure);
      }
    }
    String restored = facts();
    System.out.println("FULLAFTER " + restored);
    require(
        initial.equals(restored), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
