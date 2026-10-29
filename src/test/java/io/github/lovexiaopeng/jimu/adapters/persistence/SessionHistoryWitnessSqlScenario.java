package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅真实合法首节点的history witness；实际Mapper失效及实际finish权限末检，不发布合成证明。 */
public final class SessionHistoryWitnessSqlScenario {
  private SessionHistoryWitnessSqlScenario() {}

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static JdbcLedger view(JdbcLedger root) throws Exception {
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    var v =
        (JdbcLedger)
            factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    v.queryContext().restrictLineage();
    return v;
  }

  static void verify(JdbcLedger root, JdbcTemplate db, Lease lease, String ending)
      throws Exception {
    var configuration = root.mappers.session().getConfiguration();
    configuration.addMappedStatement(
        new org.apache.ibatis.mapping.MappedStatement.Builder(
                configuration,
                "private.historyWitnessUnknown",
                new org.apache.ibatis.scripting.defaults.RawSqlSource(
                    configuration,
                    "UPDATE task SET revision=revision WHERE tenant_id=#{tenant} AND id=#{id}",
                    Map.class),
                org.apache.ibatis.mapping.SqlCommandType.UPDATE)
            .build());
    for (String mode : List.of("disjoint", "intersect", "unknown", "conflict")) {
      var before = SessionDependencyScenario.snapshot(db);
      var v = view(root);
      var q = v.queryContext();
      RuntimeException[] first = {null};
      var rollback = new IllegalStateException("private disjoint rollback marker");
      try {
        v.sessionTransaction(
            () -> {
              var task = v.fenced(lease, true);
              v.sessionScopes.require(task);
              v.todoCommitFence(lease);
              var history = v.sessions.history(task, true);
              var owner = SessionQueryTransaction.current(q);
              var caller = MapperSessionScopeRecords.caller(task);
              check(
                  owner.checkedHistory(caller, 5, v.sessions.contract(task).binding()),
                  "real readable witness before " + mode);
              long reads = q.resources().reads();
              try {
                if (mode.equals("unknown"))
                  v.mappers
                      .session()
                      .update(
                          "private.historyWitnessUnknown",
                          Map.of("query", q, "tenant", lease.tenant(), "id", lease.taskId()));
                else if (mode.equals("conflict"))
                  v.mappers
                      .session()
                      .update(
                          "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.retainAuthorityHistory",
                          Map.of(
                              "query",
                              q,
                              "tenant",
                              lease.tenant(),
                              "id",
                              lease.taskId(),
                              "taskId",
                              "different-task",
                              "retain",
                              history.get("retain_until")));
                else {
                  int affected =
                      v.mappers
                          .session()
                          .getMapper(
                              io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                                  .SessionHistoryRetentionMapper.class)
                          .retainAuthorityHistory(
                              q,
                              (java.time.LocalDateTime) history.get("retain_until"),
                              lease.tenant(),
                              mode.equals("disjoint") ? "not-a-target" : lease.taskId());
                  check(
                      mode.equals("disjoint") && affected == 0,
                      "disjoint actual Mapper write returned0");
                }
              } catch (RuntimeException failure) {
                first[0] = q.recordedFailure();
                check(
                    !mode.equals("disjoint") && first[0] != null,
                    "published dependency poisoned before SQL " + mode);
                try {
                  owner.checkedHistory(caller, 5, v.sessions.contract(task).binding());
                  throw new AssertionError("poisoned witness reusable");
                } catch (RuntimeException again) {
                  check(again == first[0], "witness preserves original failure " + mode);
                }
                return null; // 实际Work.finish仍必须拒绝，不能caught后提交。
              }
              check(mode.equals("disjoint"), "dependent write cannot execute");
              v.sessions.historyProof(task);
              check(
                  v.sessions.historyRetained(task) && reads == q.resources().reads(),
                  "disjoint retains real witness and both consumers add no ResultSet");
              first[0] = rollback;
              throw rollback;
            },
            q.deadlineNanos());
        throw new AssertionError("negative transaction must roll back");
      } catch (RuntimeException failure) {
        check(
            first[0] != null && failure == first[0],
            "actual finish/rollback first identity " + mode);
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "history witness rollback22 " + mode);
      check(q.scratchLive() == 65536, "history witness live65536 " + mode);
      System.out.println("HISTORY_WITNESS_RESOURCES " + mode + " " + q.resources());
    }
    for (String mode : List.of("actor", "installation", "pin", ending)) {
      var before = SessionDependencyScenario.snapshot(db);
      var v = view(root);
      var q = v.queryContext();
      boolean[] proof = {false};
      try {
        v.sessionTransaction(
            () -> {
              var task = v.fenced(lease, true);
              v.sessionScopes.require(task);
              v.todoCommitFence(lease);
              v.sessions.history(task, true);
              proof[0] =
                  SessionQueryTransaction.current(q)
                      .checkedHistory(
                          MapperSessionScopeRecords.caller(task),
                          5,
                          v.sessions.contract(task).binding());
              check(proof[0], "real checked history before live negative " + mode);
              // 同连接故障注入，绕过依赖通知来单独考验真实scope.live；不会手动publish/valid。
              switch (mode) {
                case "actor" ->
                    db.update(
                        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
                        lease.tenant(),
                        "alice");
                case "installation" ->
                    db.update(
                        "UPDATE installation SET enabled=FALSE WHERE tenant_id=? AND plugin_id='session_trace'",
                        lease.tenant());
                case "pin" ->
                    db.update(
                        "UPDATE installation SET plugin_digest=REPEAT('0',64) WHERE tenant_id=? AND plugin_id='session_trace'",
                        lease.tenant());
                case "retain" ->
                    db.update(
                        "UPDATE task_session_history SET retain_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND task_id=?",
                        lease.tenant(),
                        lease.taskId());
                case "purged" ->
                    db.update(
                        "UPDATE task_session_history SET content_state='PURGED',body_bytes=0,held_bytes=metadata_bytes,cleanup_receipt='private-negative' WHERE tenant_id=? AND task_id=?",
                        lease.tenant(),
                        lease.taskId());
                case "dbtime" -> {
                  long at =
                      db.queryForObject(
                              "SELECT UNIX_TIMESTAMP(retain_until) FROM task_session_history WHERE tenant_id=? AND task_id=?",
                              Long.class,
                              lease.tenant(),
                              lease.taskId())
                          + 1;
                  db.execute("SET timestamp=" + at);
                  var connection =
                      org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                          java.util.Objects.requireNonNull(db.getDataSource()));
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .registerSynchronization(
                          new org.springframework.transaction.support.TransactionSynchronization() {
                            public void afterCompletion(int status) {
                              try (var statement = connection.createStatement()) {
                                statement.execute("SET timestamp=DEFAULT");
                              } catch (java.sql.SQLException failure) {
                                throw new IllegalStateException("private clock restore", failure);
                              }
                            }
                          });
                }
                default -> throw new AssertionError(mode);
              }
              v.sessions.historyProof(task);
              check(
                  v.sessions.historyRetained(task),
                  "cached row proof cannot replace current live " + mode);
              return null;
            },
            q.deadlineNanos());
        throw new AssertionError("current live must reject " + mode);
      } catch (TaskFailure denied) {
        check(
            proof[0] && (denied.status() == 403 || denied.status() == 404),
            "actual scope.live/current authority rejects " + mode + " " + denied.code());
      }
      var after = SessionDependencyScenario.snapshot(db);
      boolean invalidating = List.of("retain", "purged", "dbtime").contains(mode);
      for (int table = 0; table < before.size(); table++) {
        if (invalidating && table == 6) continue; // 原snapshot第6表为task_session_scope。
        check(
            before.get(table).equals(after.get(table)),
            "live rollback table " + mode + " " + table);
      }
      if (invalidating)
        check(
            db.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM task_session_scope WHERE tenant_id=? AND caller_task_id=?",
                Boolean.class,
                lease.tenant(),
                lease.taskId()),
            "real REQUIRES_NEW invalidation persisted after rollback " + mode);
      check(q.scratchLive() == 65536, "live negative releases witness " + mode);
      System.out.println(
          "HISTORY_LIVE_RESOURCES " + mode + " " + q.resources() + " groups=" + q.groups());
    }
    System.out.println(
        "HISTORY_NEGATIVE_PROBE_COMPLETE "
            + ending
            + "; first source remains RUNNING in private fixture; no terminal/second-node claim");
  }
}
