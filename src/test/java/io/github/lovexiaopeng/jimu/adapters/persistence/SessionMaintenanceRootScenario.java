package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Small actual-Mapper root boundary fixtures; synthetic authority, no application/A03. */
public final class SessionMaintenanceRootScenario {
  private SessionMaintenanceRootScenario() {}

  private static String id(int n) {
    return "30000000-0000-0000-0000-%012d".formatted(n);
  }

  private static void check(boolean pass, String message) {
    if (!pass) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  @org.apache.ibatis.plugin.Intercepts(
      @org.apache.ibatis.plugin.Signature(
          type = org.apache.ibatis.executor.Executor.class,
          method = "query",
          args = {
            org.apache.ibatis.mapping.MappedStatement.class,
            Object.class,
            org.apache.ibatis.session.RowBounds.class,
            org.apache.ibatis.session.ResultHandler.class
          }))
  public static final class Boundary implements org.apache.ibatis.plugin.Interceptor {
    String mode;
    SessionQueryContext query;
    Object firstOwner, secondOwner;
    long deadline, firstOperations;
    boolean fired;

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
      var p = (Map<?, ?>) invocation.getArgs()[1];
      if (mode != null
          && statement.getId().endsWith("TaskMapper.selectTaskForUpdate")
          && p.get("query") instanceof SessionQueryContext q) {
        Object task = p.get("taskId");
        if (id(1).equals(task)) {
          query = q;
          deadline = q.deadlineNanos();
          firstOwner = SessionQueryTransaction.current(q).token();
        }
        if (id(2).equals(task)) {
          check(
              query == q && deadline == q.deadlineNanos(),
              "two root candidates share exact query/deadline");
          secondOwner = SessionQueryTransaction.current(q).token();
          firstOperations = q.resources().operations();
          check(
              firstOwner != secondOwner && firstOperations > 0,
              "candidate transactions have different proof owners and cumulative resources");
          fired = true;
          if (mode.equals("budget")) q.operation(65536);
          else throw new IllegalStateException("second candidate fixture failure");
        }
      }
      return invocation.proceed();
    }
  }

  private static void seed(JdbcTemplate db, int n, int version) {
    String binding = new SessionContracts(version).binding();
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice',?,?,'{}','root',?,1,'SUCCEEDED',CURRENT_TIMESTAMP(6),1,1)",
        id(n),
        "root-" + n,
        Payloads.hash("{}"),
        version == 5 ? SessionLineagePolicyV5.CAPABILITIES : SessionContracts.NAME);
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,?,?,?,TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(6)))",
        id(n),
        version,
        binding,
        Payloads.hash(binding));
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a',?,?,'SESSION_HISTORY_STORAGE',8388608,'RESERVED','KNOWN')",
        id(n) + ":session-history",
        id(n));
    db.update(
        "UPDATE tenant_budget SET history_storage_held=history_storage_held+8388608 WHERE tenant_id='tenant-a'");
    if (version == 5)
      db.update(
          "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant-a',?,'{}',?,2,'{}',?,2)",
          id(n),
          Payloads.hash("{}"),
          Payloads.hash("{}"));
    db.update(
        "UPDATE task_session_history SET body_bytes=? WHERE task_id=?",
        SessionRepairSqlOracle.inspect(db, id(n)).body(),
        id(n));
  }

  private static List<Object> taskSnapshot(JdbcTemplate db, int n) {
    var result = new ArrayList<Object>();
    for (String table :
        List.of(
            "task",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "task_session_history",
            "task_session_scope",
            "task_session_scope_target",
            "task_session_lineage",
            "step_session_scope",
            "invocation_session_scope_query",
            "invocation_session_lineage_trace",
            "reservation",
            "event",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace")) {
      String key =
          switch (table) {
            case "task" -> "id";
            case "task_session_scope",
                    "task_session_scope_target",
                    "step_session_scope",
                    "invocation_session_scope_query",
                    "invocation_session_lineage_trace" ->
                "caller_task_id";
            default -> "task_id";
          };
      result.add(
          db.queryForList(
              "SELECT * FROM `" + table + "` WHERE tenant_id='tenant-a' AND `" + key + "`=?",
              id(n)));
    }
    return result;
  }

  public static void run(JdbcTemplate db, JdbcLedger root) throws Exception {
    root.cleanupSessionHistory(); // zero v5 candidates; legacy pass also empty.
    seed(db, 1, 5);
    seed(db, 2, 5);
    seed(db, 3, 1);
    var second = taskSnapshot(db, 2);
    var legacy = taskSnapshot(db, 3);
    var boundary = new Boundary();
    boundary.mode = "budget";
    root.mappers.session().getConfiguration().addInterceptor(boundary);
    boolean rejected = false;
    try {
      root.cleanupSessionHistory();
    } catch (RuntimeException expected) {
      rejected = true;
    }
    check(
        rejected && boundary.fired, "second root candidate cumulative budget rejection propagated");
    check(
        "PURGED"
            .equals(
                db.queryForObject(
                    "SELECT content_state FROM task_session_history WHERE task_id=?",
                    String.class,
                    id(1))),
        "first root candidate independently committed before second failure");
    check(
        second.equals(taskSnapshot(db, 2)), "second root candidate 18 task projections unchanged");
    check(legacy.equals(taskSnapshot(db, 3)), "legacy pass not run after first v5 error");
    check(
        boundary.query.scratchLive() == 65536,
        "failed root releases candidate batch and candidate Work references");
    boundary.mode = null;
    root.cleanupSessionHistory();
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM task_session_history WHERE task_id IN (?,?,?) AND content_state='PURGED'",
                Integer.class,
                id(1),
                id(2),
                id(3))
            == 3,
        "mixed v5 and legacy candidates complete via their separate passes");
    var after = SessionDependencyScenario.snapshot(db);
    root.cleanupSessionHistory();
    check(
        after.equals(SessionDependencyScenario.snapshot(db)),
        "zero-candidate repeated root unchanged");
    // Invoke the same production view with an already expired root. No SQL counter can advance.
    var method =
        JdbcLedger.class.getDeclaredMethod(
            "queryView",
            long.class,
            io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources
                .class);
    method.setAccessible(true);
    var expired =
        (JdbcLedger)
            method.invoke(
                root,
                System.nanoTime() - 1,
                new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger
                    .QueryResources(0, 0, 0));
    boolean timeout = false;
    try {
      expired.historyRetention.cleanup();
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure expected) {
      timeout = expected.code().equals("SESSION_QUERY_TIMEOUT");
    }
    check(
        timeout && expired.queryContext().resources().reads() == 0,
        "already expired root refuses before any ResultSet");
  }
}
