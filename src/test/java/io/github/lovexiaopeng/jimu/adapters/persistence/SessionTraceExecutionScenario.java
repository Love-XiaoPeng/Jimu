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
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 同一实际PENDING search槽的工具/owner和故障外层回滚，不恢复历史全矩阵。 */
public final class SessionTraceExecutionScenario {
  private static JdbcTemplate db;
  private static JdbcLedger active;
  private static boolean inject;
  private static int injected;
  private static boolean relationFault;
  private static final List<String> trace = new ArrayList<>();

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String facts(String task) {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "invocation_session_read",
            "invocation_session_trace",
            "model_dispatch",
            "task_attempt",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event")) {
      String column = table.equals("task") ? "id" : "task_id";
      result.put(
          table,
          db
              .queryForList(
                  "SELECT * FROM "
                      + table
                      + " WHERE tenant_id='tenant-a' AND "
                      + column
                      + "=? ORDER BY 1,2,3",
                  task)
              .stream()
              .map(row -> row.toString())
              .sorted()
              .toList());
    }
    result.put(
        "budget",
        db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'").toString());
    return Payloads.json(result);
  }

  private static void proof(String trial, Object invocation) {
    var row =
        db.queryForMap(
            "SELECT outcome,failure_code,upper_seq,input_digest,binding_digest,target_seq,relation_policy,scan_digest,relations_digest,target_digest,scan_rows,scan_event_bytes,relation_edges,output_digest,output_bytes,receipt_event_seq,committed_attempt_id,committed_fence FROM invocation_session_trace WHERE tenant_id='tenant-a' AND invocation_id=?",
            invocation);
    if ("SUCCEEDED".equals(row.get("outcome"))) {
      var scan = new ArrayList<java.util.Map<String, Object>>();
      var relations = new ArrayList<java.util.Map<String, Object>>();
      for (var event :
          db.queryForList(
              "SELECT seq,event_digest,type,source_invocation_id FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=(SELECT task_id FROM invocation WHERE tenant_id='tenant-a' AND id=?) AND seq<=? ORDER BY seq",
              invocation,
              row.get("upper_seq"))) {
        int seq = ((Number) event.get("seq")).intValue();
        scan.add(java.util.Map.of("seq", seq, "event_sha256", event.get("event_digest")));
        if ("session.read".equals(event.get("type"))) {
          var relation =
              db.queryForMap(
                  "SELECT read_sources_digest FROM invocation_session_read WHERE tenant_id='tenant-a' AND invocation_id=?",
                  event.get("source_invocation_id"));
          relations.add(
              java.util.Map.of(
                  "seq", seq, "read_sources_sha256", relation.get("read_sources_digest")));
        }
      }
      String rawScan = Payloads.json(scan), rawRelations = Payloads.json(relations);
      require(
          Payloads.hash(rawScan).equals(row.get("scan_digest"))
              && Payloads.hash(rawRelations).equals(row.get("relations_digest")),
          "original Map.of scan and relations raw digest proofs");
      System.out.println(
          "RAWPROOF "
              + trial
              + " "
              + Payloads.json(java.util.Map.of("scan", rawScan, "relations", rawRelations)));
    }
    System.out.println("PROOF " + trial + " " + Payloads.json(row));
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
            var connection = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object result = invoke(connection, m, a);
                      if (m.getName().equals("prepareStatement")
                          && result instanceof PreparedStatement statement) {
                        String sql = a[0].toString().replaceAll("\\s+", " ");
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("execute")) {
                                trace.add(sql);
                                if (inject
                                    && (relationFault
                                        ? sql.contains("FROM invocation_session_read")
                                            && !sql.contains("relation_version")
                                            && sql.contains("read_sources_digest")
                                        : sql.contains("FROM session_public_event")
                                            && sql.contains(" BETWEEN ")
                                            && sql.contains("LIMIT 128"))) {
                                  injected++;
                                  throw new SQLException(
                                      "PRIVATE_NATIVE_SCAN_FAILURE", "HY000", 1644);
                                }
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return result;
                    });
          }
        };
    db = new JdbcTemplate(source);
    var ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    String id = args[1];
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    long upper =
        db.queryForObject(
            "SELECT next_seq-1 FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
            Long.class,
            id);
    require(upper >= 2, "actual immutable user/step/model facts available");
    String before = facts(id);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var slot =
        db.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND tool='session_event_trace' ORDER BY ordinal LIMIT 1",
            id);
    for (String mode :
        List.of("owner", "source", "missing", "tool", "sql", "relation-sql", "corrupt")) {
      trace.clear();
      inject = (mode.equals("sql") || mode.equals("relation-sql"));
      injected = 0;
      relationFault = mode.equals("relation-sql");
      active = null;
      tx.execute(
          status -> {
            try {
              String output;
              if (mode.equals("owner") || mode.equals("source") || mode.equals("missing")) {
                var identity =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity(
                        "tenant-a", (String) task.get("owner_actor_id"));
                long target =
                    mode.equals("source")
                        ? db.queryForObject(
                            "SELECT seq FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND type='session.read'",
                            Long.class,
                            id)
                        : mode.equals("missing") ? 127 : 0;
                byte[] body =
                    ("{\"seq\":" + target + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output =
                    ledger.sessionTrace(
                        identity, id, end -> body, System.nanoTime() + 2_000_000_000L);
                var value = ledger.sessions.contracts.json(output, 16384);
                require(
                    mode.equals("source")
                        ? value.path("source_event_seqs").size() > 0
                        : value.path("derived_event_seqs").size() > 0,
                    "actual source and derived relationships");
              } else {
                db.update(
                    "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND,deadline=CURRENT_TIMESTAMP(6)+INTERVAL 120 SECOND WHERE tenant_id='tenant-a' AND id=?",
                    id);
                var row =
                    db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
                var lease =
                    new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
                        "tenant-a",
                        (String) row.get("owner_actor_id"),
                        id,
                        (String) row.get("current_attempt_id"),
                        ((Number) row.get("fence")).longValue(),
                        (String) row.get("worker_id"),
                        ((java.time.LocalDateTime) row.get("deadline"))
                            .toInstant(java.time.ZoneOffset.UTC),
                        (String) row.get("goal"),
                        java.util.Arrays.asList(((String) row.get("capabilities")).split(",")),
                        ((Number) row.get("installation_epoch")).longValue());
                var method =
                    JdbcLedger.class.getDeclaredMethod(
                        "queryView",
                        io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease.class,
                        boolean.class);
                method.setAccessible(true);
                var view = (JdbcLedger) method.invoke(ledger, lease, false);
                active = view;
                if (mode.equals("corrupt"))
                  db.update(
                      "UPDATE session_public_event SET event_digest=? WHERE tenant_id='tenant-a' AND task_id=? AND seq=0",
                      "0".repeat(64),
                      id);
                output =
                    view.traceOrResult(
                        lease,
                        (String) slot.get("step_id"),
                        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
                            (String) slot.get("slot"),
                            (String) slot.get("provider_id"),
                            (String) slot.get("tool"),
                            (String) slot.get("input_snapshot")),
                        (String) slot.get("plugin_version"),
                        (String) slot.get("plugin_digest"));
                require(mode.equals("tool"), "only successful tool returns");
                require(
                    "SUCCEEDED"
                        .equals(
                            db.queryForObject(
                                "SELECT outcome FROM invocation_session_trace WHERE tenant_id='tenant-a' AND invocation_id=?",
                                String.class,
                                slot.get("id"))),
                    "actual tool settlement before rollback");
                proof(mode, slot.get("id"));
              }
              System.out.println("RESULT " + mode + " " + output);
            } catch (
                io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed
                    failure) {
              require(
                  (mode.equals("sql") || mode.equals("relation-sql")) && injected == 1,
                  "single scan fault retains original unconfirmed boundary");
              System.out.println(
                  "RESULT " + mode + " " + Payloads.json("SessionCommitUnconfirmed"));
            } catch (TaskFailure failure) {
              require(
                  (mode.equals("corrupt") && failure.code().equals("SESSION_HISTORY_CORRUPT"))
                      || (mode.equals("missing")
                          && failure.code().equals("SESSION_EVENT_NOT_FOUND")),
                  "original known rejection");
              if (mode.equals("corrupt")) {
                require(
                    "FAILED"
                        .equals(
                            db.queryForObject(
                                "SELECT outcome FROM invocation_session_trace WHERE tenant_id='tenant-a' AND invocation_id=?",
                                String.class,
                                slot.get("id"))),
                    "known rejection staged settlement before throw");
                proof(mode, slot.get("id"));
              }
              System.out.println("RESULT " + mode + " " + Payloads.json(failure.code()));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            } finally {
              status.setRollbackOnly();
            }
            return null;
          });
      require(
          before.equals(facts(id)),
          "all captured native trace facts restored after outer rollback");
      if (active != null) {
        var budgetField = JdbcSessionRecords.class.getDeclaredField("searchBudget");
        budgetField.setAccessible(true);
        require(budgetField.get(active.sessions) == null, "native trace budget finally cleared");
      }
      require(
          trace.stream()
              .anyMatch(sql -> sql.contains("task_session_history") && sql.contains("FOR UPDATE")),
          "history lock retained");
      System.out.println("TRACE " + mode + " " + Payloads.json(trace));
    }
  }
}
