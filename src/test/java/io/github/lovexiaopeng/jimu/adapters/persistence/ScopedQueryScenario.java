package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
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
import java.util.LinkedHashMap;
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

/** 实际原槽的旧新JAR驱动：故障回滚／提交确认丢失、receipt拒绝和零重放。 */
public final class ScopedQueryScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static JdbcLedger active;
  private static String task;
  private static final AtomicReference<String> fault = new AtomicReference<>();

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
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
    var facts = new LinkedHashMap<String, Object>();
    facts.put(
        "task",
        db.queryForList(
            "SELECT tools_used,next_event_seq,token_held,cost_held FROM task WHERE tenant_id='tenant-a' AND id=?",
            task));
    facts.put(
        "budget",
        db.queryForList(
            "SELECT tool_used,history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'"));
    for (String table :
        List.of(
            "invocation",
            "invocation_session_scope_query",
            "task_session_history",
            "reservation",
            "event",
            "session_public_event")) {
      String key = table.equals("invocation_session_scope_query") ? "caller_task_id" : "task_id";
      facts.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?", task));
    }
    return Payloads.json(normalize(facts));
  }

  private static Object normalize(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    if (value instanceof Map<?, ?> map) {
      var result = new java.util.TreeMap<String, Object>();
      map.forEach((k, v) -> result.put(k.toString(), normalize(v)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(ScopedQueryScenario::normalize).toList();
    return value;
  }

  private static void rejects(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("Expected " + code);
    } catch (TaskFailure failure) {
      require(code.equals(failure.code()), "rejection " + code + " got " + failure.code());
    }
  }

  private static void uncertain(Runnable work) {
    try {
      work.run();
      throw new AssertionError("Commit uncertainty expected");
    } catch (SessionCommitUnconfirmed expected) {
      System.out.println("PASS original unconfirmed boundary");
    }
  }

  private static String call(Lease lease, Map<String, Object> invocation) {
    try {
      var view = JdbcLedger.class.getDeclaredMethod("queryView", Lease.class, boolean.class);
      view.setAccessible(true);
      active = (JdbcLedger) view.invoke(ledger, lease, true);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
    return active.scopedOrResult(
        lease,
        s(invocation, "step_id"),
        new Call(
            s(invocation, "slot"),
            s(invocation, "provider_id"),
            s(invocation, "tool"),
            s(invocation, "input_snapshot")),
        s(invocation, "plugin_version"),
        s(invocation, "plugin_digest"));
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
    var faulty =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, values) -> {
                      String mode = fault.get();
                      if (method.getName().equals("commit")
                          && ("before".equals(mode) || "after".equals(mode))
                          && fault.compareAndSet(mode, null)) {
                        if (mode.equals("before")) connection.rollback();
                        else connection.commit();
                        throw new SQLException("Private synthetic lost acknowledgement", "08006");
                      }
                      Object result = invoke(connection, method, values);
                      if (method.getName().equals("prepareStatement")
                          && result instanceof PreparedStatement statement) {
                        String sql = values[0].toString().replaceAll("\\s+", " ");
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (p, m, a) -> {
                              if (m.getName().startsWith("execute")
                                  && sql.contains(
                                      "UPDATE invocation_session_scope_query SET outcome")) {
                                if (fault.compareAndSet("sql", null))
                                  throw new SQLException(
                                      "Private one-shot query CAS fault", "HY000");
                                if (fault.compareAndSet("deadline", null)) {
                                  var context = active.queryContext();
                                  TransactionSynchronizationManager.registerSynchronization(
                                      new TransactionSynchronization() {
                                        @Override
                                        public int getOrder() {
                                          return Integer.MIN_VALUE;
                                        }

                                        @Override
                                        public void beforeCommit(boolean readOnly) {
                                          context.restrictDeadline(System.nanoTime() - 1);
                                        }
                                      });
                                }
                              }
                              return invoke(statement, m, a);
                            });
                      }
                      return result;
                    });
          }
        };
    ledger = new JdbcLedger(faulty, new ModelProfiles(env));
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.priorSessionSearchEnabled(true);
    task = args[1];
    var row = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    var lease =
        new Lease(
            "tenant-a",
            s(row, "owner_actor_id"),
            task,
            s(row, "current_attempt_id"),
            n(row, "fence"),
            s(row, "worker_id"),
            ((LocalDateTime) row.get("deadline")).toInstant(ZoneOffset.UTC),
            s(row, "goal"),
            Arrays.asList(s(row, "capabilities").split(",")),
            n(row, "installation_epoch"));
    var invocations =
        db.queryForList(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY ordinal",
            task);
    require(invocations.size() == 4 || invocations.size() == 2, "actual immutable response slots");
    boolean missing = args.length > 2 && args[2].equals("missing");
    int ordinal = 0;
    for (var invocation : invocations) {
      boolean failedSlot = missing && s(invocation, "tool").equals("session_event_read");
      require("PENDING".equals(s(invocation, "state")), "actual original PENDING slot " + ordinal);
      Runnable run = () -> call(lease, invocation);
      String before = facts();
      if (failedSlot && args.length > 3 && args[3].equals("old")) {
        try {
          run.run();
          throw new AssertionError("Accepted baseline null-unboxing defect expected");
        } catch (NullPointerException defect) {
          require(
              Arrays.stream(defect.getStackTrace())
                  .anyMatch(
                      frame ->
                          frame.getClassName().equals(JdbcLedger.class.getName())
                              && frame.getMethodName().startsWith("lambda$scopedQueryOrResult")),
              "baseline missing-read defect originates in original scoped query");
          require(
              before.equals(facts()),
              "baseline null-unboxing rolls back with no known failure receipt or consumption");
          System.out.println("BASELINE_DEFECT " + defect);
          defect.printStackTrace(System.out);
        }
        System.out.println(
            "RESULT slot-" + ordinal + " session_event_read BASELINE_NULL_UNBOX_ROLLBACK");
        ordinal++;
        continue;
      }
      fault.set("sql");
      for (int warm = 0; ; warm++) {
        try {
          uncertain(run);
          break;
        } catch (TaskFailure timeout) {
          if (warm >= 2
              || !timeout.code().equals("SESSION_QUERY_TIMEOUT")
              || !"sql".equals(fault.get())) throw timeout;
          require(
              before.equals(facts()),
              "bounded cold-path timeout before injected SQL preserves all facts");
          System.out.println(
              "OBSERVED original absolute deadline before SQL hook; retry original slot " + warm);
        }
      }
      require(
          fault.get() == null && before.equals(facts()),
          "SQL fault rolls back query tool body reservation and events");
      fault.set("deadline");
      rejects("SESSION_QUERY_TIMEOUT", run);
      require(
          fault.get() == null && before.equals(facts()),
          "scope final beforeCommit deadline rolls back every staged fact");
      fault.set("before");
      uncertain(run);
      require(before.equals(facts()), "commit-before acknowledgement loss rolled back");
      long used =
          db.queryForObject(
              "SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id=?", Long.class, task);
      fault.set("after");
      uncertain(run);
      require(
          db.queryForObject(
                  "SELECT tools_used FROM task WHERE tenant_id='tenant-a' AND id=?",
                  Long.class,
                  task)
              == used + 1,
          "lost success acknowledgement consumes exactly once");
      String replayBefore = facts();
      if (failedSlot) {
        rejects("SESSION_EVENT_NOT_FOUND", run);
        rejects("SESSION_EVENT_NOT_FOUND", run);
        require(
            db.queryForObject(
                    "SELECT outcome FROM invocation_session_scope_query WHERE tenant_id='tenant-a' AND invocation_id=?",
                    String.class,
                    s(invocation, "id"))
                .equals("FAILED"),
            "known refusal persisted before rejection");
      } else {
        String output = call(lease, invocation);
        require(output.equals(call(lease, invocation)), "replay returns exact committed bytes");
      }
      require(replayBefore.equals(facts()), "replay has zero business writes or charge");
      System.out.println(
          "QUERY slot-" + ordinal + " " + Payloads.json(active.queryContext().resources()));
      var binding =
          db.queryForMap(
              "SELECT * FROM invocation_session_scope_query WHERE tenant_id='tenant-a' AND invocation_id=?",
              s(invocation, "id"));
      String originalType =
          db.queryForObject(
              "SELECT type FROM event WHERE tenant_id='tenant-a' AND task_id=? AND seq=?",
              String.class,
              task,
              binding.get("receipt_event_seq"));
      db.update(
          "UPDATE event SET type=? WHERE tenant_id='tenant-a' AND task_id=? AND seq=?",
          "X".repeat(originalType.length()),
          task,
          binding.get("receipt_event_seq"));
      try {
        rejects("SESSION_HISTORY_CORRUPT", run);
      } finally {
        db.update(
            "UPDATE event SET type=? WHERE tenant_id='tenant-a' AND task_id=? AND seq=?",
            originalType,
            task,
            binding.get("receipt_event_seq"));
      }
      require(replayBefore.equals(facts()), "receipt tamper refusal adds no writes");
      db.update(
          "UPDATE invocation_session_scope_query SET input_digest=? WHERE tenant_id='tenant-a' AND invocation_id=?",
          "0".repeat(64),
          s(invocation, "id"));
      try {
        rejects("SESSION_HISTORY_CORRUPT", run);
      } finally {
        db.update(
            "UPDATE invocation_session_scope_query SET input_digest=? WHERE tenant_id='tenant-a' AND invocation_id=?",
            binding.get("input_digest"),
            s(invocation, "id"));
      }
      require(replayBefore.equals(facts()), "binding tamper refusal adds no writes");
      System.out.println(
          "RESULT slot-"
              + ordinal
              + " "
              + s(invocation, "tool")
              + " "
              + (failedSlot ? "FAILED" : "SUCCEEDED")
              + " rollback/final-deadline/lost-ack/replay/proof");
      ordinal++;
    }
    var wrong =
        new Lease(
            lease.tenant(),
            lease.actor(),
            task,
            lease.attemptId(),
            lease.fence() + 1,
            lease.worker(),
            lease.deadline(),
            lease.goal(),
            lease.capabilities(),
            lease.installationEpoch());
    String before = facts();
    rejects("FENCE_REJECTED", () -> call(wrong, invocations.getFirst()));
    require(before.equals(facts()), "stale fence adds no writes");
    System.out.println("RESULT stale fence refused with no write");
  }
}
