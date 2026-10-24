package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 同任务旧新制品：上下文字节、当前资格拒绝、metadata固定点和最后截止回滚。 */
public final class SessionContextScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static JdbcLedger active;
  private static String task;
  private static final AtomicReference<String> fault = new AtomicReference<>();

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String facts() {
    return db.queryForMap(
                "SELECT revision,next_event_seq,tools_used,models_used FROM task WHERE tenant_id='tenant-a' AND id=?",
                task)
            .toString()
        + db.queryForMap(
                "SELECT next_seq,body_bytes,metadata_bytes,held_bytes,content_state FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                task)
            .toString()
        + db.queryForMap(
                "SELECT tool_used,model_used,history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            .toString();
  }

  private static long referenceMetadata() {
    var excluded =
        java.util.Set.of(
            "request_snapshot",
            "goal",
            "summary",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "event_snapshot",
            "normalized_query_snapshot",
            "read_sources_snapshot",
            "authority_snapshot",
            "target_snapshot",
            "source_task_id",
            "context_snapshot",
            "normalized_snapshot",
            "read_refs_snapshot");
    long bytes = 0;
    for (String table :
        List.of(
            "task",
            "task_session_history",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_scope_query",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "event")) {
      var columns =
          db
              .queryForList(
                  "SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                  String.class,
                  table)
              .stream()
              .filter(c -> !excluded.contains(c))
              .toList();
      String key =
          table.equals("task") ? "id" : table.contains("scope") ? "caller_task_id" : "task_id";
      for (var row :
          db.queryForList(
              "SELECT "
                  + String.join(",", columns)
                  + " FROM "
                  + table
                  + " WHERE tenant_id='tenant-a' AND "
                  + key
                  + "=?",
              task)) {
        bytes += 32;
        for (Object value : row.values())
          bytes +=
              8
                  + (value == null
                      ? 1
                      : io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                          value instanceof LocalDateTime time
                              ? java.time.format.DateTimeFormatter.ofPattern(
                                      "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                                  .format(time)
                              : value.toString()));
      }
    }
    return bytes;
  }

  private static String call(Lease lease) {
    try {
      var method =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      method.setAccessible(true);
      active =
          (JdbcLedger)
              method.invoke(
                  ledger, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
    return active.sessionContext(lease);
  }

  private static void rejection(String name, Lease lease, String expected) {
    String before = facts();
    try {
      call(lease);
      throw new AssertionError("Expected refusal " + name);
    } catch (TaskFailure failure) {
      require(expected.equals(failure.code()), name + " " + failure.code());
      require(failure.getCause() == null, "sanitized refusal " + name);
      require(before.equals(facts()), "rollback " + name);
      System.out.println("RESULT " + name + " " + failure.code());
    }
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    db = new JdbcTemplate(source);
    task = args[1];
    var faulty =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, values) -> {
                      Object result = invoke(connection, m, values);
                      if (m.getName().equals("prepareStatement")
                          && result instanceof PreparedStatement statement) {
                        String sql = values[0].toString().replaceAll("\\s+", " ");
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("execute")
                                  && sql.startsWith(
                                      "UPDATE task_session_history SET metadata_bytes")) {
                                if (fault.compareAndSet("sql", null))
                                  throw new SQLException(
                                      "Private synthetic metadata fault", "HY000");
                                if (fault.compareAndSet("deadline", null)) {
                                  var query = active.queryContext();
                                  TransactionSynchronizationManager.registerSynchronization(
                                      new TransactionSynchronization() {
                                        public int getOrder() {
                                          return Integer.MIN_VALUE;
                                        }

                                        public void beforeCommit(boolean readOnly) {
                                          query.restrictDeadline(System.nanoTime() - 1);
                                        }
                                      });
                                }
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return result;
                    });
          }
        };
    ledger = new JdbcLedger(faulty, new ModelProfiles(env));
    ledger.sessionReadEnabled(true, List.of());
    ledger.priorSessionSearchEnabled(true);
    var row = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    var lease =
        new Lease(
            "tenant-a",
            (String) row.get("owner_actor_id"),
            task,
            (String) row.get("current_attempt_id"),
            ((Number) row.get("fence")).longValue(),
            (String) row.get("worker_id"),
            ((LocalDateTime) row.get("deadline")).toInstant(ZoneOffset.UTC),
            (String) row.get("goal"),
            Arrays.asList(((String) row.get("capabilities")).split(",")),
            ((Number) row.get("installation_epoch")).longValue());
    String raw = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      String before = facts();
      try {
        raw = call(lease);
        break;
      } catch (TaskFailure failure) {
        if (attempt == 2 || !failure.code().equals("SESSION_QUERY_TIMEOUT")) throw failure;
        require(before.equals(facts()), "cold timeout rollback before identical context retry");
        System.out.println("OBSERVED cold-path timeout attempt=" + attempt);
      }
    }
    require(raw != null, "context acquired");
    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
    long upper =
        db.queryForObject(
            "SELECT next_seq-1 FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
            Long.class,
            task);
    var recent =
        db.queryForList(
            "SELECT seq,type FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND seq BETWEEN ? AND ? ORDER BY seq LIMIT 8",
            task,
            Math.max(0, upper - 7),
            upper);
    var expected =
        Payloads.json(
            Map.of(
                "kind",
                "session_event_context",
                "version",
                ((Number)
                        db.queryForMap(
                                "SELECT history_version FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                                task)
                            .get("history_version"))
                    .intValue(),
                "session_id",
                task,
                "upper_seq",
                upper,
                "user_message_seq",
                0,
                "recent",
                recent.stream()
                    .map(r -> Map.of("seq", r.get("seq"), "type", r.get("type")))
                    .toList()));
    var expectedNode =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(expected);
    if (node.path("version").asInt() == 4) {
      var scope =
          ledger.sessions.contracts.json(
              (String)
                  db.queryForMap(
                          "SELECT authority_snapshot FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                          task)
                      .get("authority_snapshot"),
              4096);
      expectedNode.put(
          "scope_sha256",
          (String)
              db.queryForMap(
                      "SELECT authority_digest FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                      task)
                  .get("authority_digest"));
      expectedNode.put("retain_until", scope.path("retain_until").asText());
      var prior = expectedNode.putArray("prior_sessions");
      for (var target : scope.path("targets"))
        prior
            .addObject()
            .put("session_id", target.path("session_id").asText())
            .put("upper_seq", target.path("upper_seq").asLong());
    }
    require(raw.equals(Payloads.json(expectedNode)), "original context bytes in same JVM");
    System.out.println("CONTEXT " + raw);
    System.out.println("QUERY " + Payloads.json(active.queryContext().resources()));
    if (node.path("version").asInt() == 4)
      require(node.path("prior_sessions").size() == 1, "whole scope target retained");
    long metadata = referenceMetadata();
    require(
        metadata
            == db.queryForObject(
                "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                Long.class,
                task),
        "metadata fixed point independently re-read");
    System.out.println("METADATA " + metadata);
    rejection(
        "stale-fence",
        new Lease(
            lease.tenant(),
            lease.actor(),
            task,
            lease.attemptId(),
            lease.fence() - 1,
            lease.worker(),
            lease.deadline(),
            lease.goal(),
            lease.capabilities(),
            lease.installationEpoch()),
        "FENCE_REJECTED");
    rejection(
        "wrong-actor",
        new Lease(
            lease.tenant(),
            "bob",
            task,
            lease.attemptId(),
            lease.fence(),
            lease.worker(),
            lease.deadline(),
            lease.goal(),
            lease.capabilities(),
            lease.installationEpoch()),
        "INVOCATION_MISMATCH");
    db.update(
        "UPDATE task_session_history SET metadata_bytes=0 WHERE tenant_id='tenant-a' AND task_id=?",
        task);
    fault.set("sql");
    rejection("metadata-fault", lease, "SESSION_QUERY_UNAVAILABLE");
    require(fault.get() == null, "actual metadata SQL fault executed");
    fault.set("deadline");
    rejection("final-beforeCommit", lease, "SESSION_QUERY_TIMEOUT");
    require(fault.get() == null, "actual final metadata update beforeCommit hook executed");
    require(call(lease).equals(raw), "same lease recovers without effect replay");
    System.out.println("RESULT recovered-context");
  }
}
