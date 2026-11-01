package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.jdbc.core.JdbcTemplate;

/** 新fact在真实body SQL后、公共event insert处失败；不以prior早退代替写后故障。 */
public final class SessionAppendFailureSqlScenario {
  private SessionAppendFailureSqlScenario() {}

  private static void check(boolean valid, String text) {
    if (!valid) throw new AssertionError(text);
    System.out.println("PASS " + text);
    System.out.flush();
  }

  @Intercepts({
    @Signature(
        type = Executor.class,
        method = "update",
        args = {MappedStatement.class, Object.class}),
    @Signature(
        type = StatementHandler.class,
        method = "update",
        args = {Statement.class})
  })
  public static final class AfterBody implements Interceptor {
    boolean armed;
    int bodies, inserts;
    SQLException marker;
    String protectedSql, failedSql;

    public Object intercept(Invocation call) throws Throwable {
      if (call.getTarget() instanceof StatementHandler handler) {
        String sql = handler.getBoundSql().getSql();
        if (armed
            && sql.toLowerCase(java.util.Locale.ROOT)
                .contains("insert into session_public_event")) {
          check(bodies == 1, "body Mapper succeeded before public insert");
          inserts++;
          armed = false;
          failedSql = sql;
          try (var statement = ((Statement) call.getArgs()[0]).getConnection().createStatement()) {
            statement.executeQuery("SELECT * FROM xiao126_absent_append_failure_table");
          } catch (SQLException failure) {
            marker = failure;
            throw failure;
          }
          throw new AssertionError("actual server SQL failure required");
        }
        return call.proceed();
      }
      var command = (MappedStatement) call.getArgs()[0];
      Object result = call.proceed();
      if (armed && command.getId().endsWith("SessionHistoryStorageMapper.body")) {
        check(Integer.valueOf(1).equals(result), "actual body SQL affected exactly one history");
        bodies++;
        protectedSql = command.getBoundSql(call.getArgs()[1]).getSql();
      }
      return result;
    }
  }

  static void verify(JdbcLedger root, JdbcTemplate db, String id) throws Exception {
    var before = SessionDependencyScenario.snapshot(db);
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    var v =
        (JdbcLedger)
            factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    var q = v.queryContext();
    q.restrictLineage();
    var fault = new AfterBody();
    root.mappers.session().getConfiguration().addInterceptor(fault);
    String fact = "after-body-" + java.util.UUID.randomUUID();
    RuntimeException[] first = {null};
    try {
      v.sessionTransaction(
          () -> {
            var task = v.locked("tenant-a", id);
            long bodyBefore =
                db.queryForObject(
                    "SELECT body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                    Long.class,
                    id);
            v.event(task, "history:" + fact, "session_history.changed", null);
            long reliable =
                v.mappers.events().selectFactSequenceValue(q, "tenant-a", id, "history:" + fact);
            check(
                db.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=? AND fact_id=?",
                        Long.class,
                        id,
                        "history:" + fact)
                    == 1,
                "new native event SQL really executed");
            var source = new java.util.LinkedHashMap<String, Object>();
            source.put("kind", "request");
            source.put("step_id", null);
            source.put("invocation_id", null);
            fault.armed = true;
            try {
              v.sessions.append(
                  task,
                  fact,
                  "user.message",
                  source,
                  Map.of("content", "new actual write-after-body event"),
                  false,
                  Payloads.hash("new actual write-after-body event"),
                  null,
                  reliable);
              throw new AssertionError("new event insert must fail");
            } catch (RuntimeException caught) {
              first[0] = q.recordedFailure();
              check(
                  first[0] != null
                      && fault.marker != null
                      && fault.bodies == 1
                      && fault.inserts == 1,
                  "caller caught actual insert failure after body");
              check(
                  "42S02".equals(fault.marker.getSQLState()),
                  "actual MySQL insert fault SQLState42S02");
              boolean cause = false;
              for (Throwable t = first[0]; t != null; t = t.getCause())
                if (t == fault.marker) cause = true;
              check(cause, "first owner slot preserves original SQL cause");
              check(
                  db.queryForObject(
                          "SELECT body_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                          Long.class,
                          id)
                      > bodyBefore,
                  "body SQL visible inside failed transaction before rollback");
              check(
                  db.queryForObject(
                          "SELECT COUNT(*) FROM session_public_event WHERE tenant_id='tenant-a' AND task_id=? AND fact_key=?",
                          Long.class,
                          id,
                          fact)
                      == 0,
                  "public insert and cursor advance not executed after failure");
              try {
                q.checkHealthy();
                throw new AssertionError("sticky first error required");
              } catch (RuntimeException again) {
                check(again == first[0], "caught query entry keeps same first object");
              }
            }
            return null; // 明确catch后继续到实际Work.finish；不能提交保护写。
          },
          q.deadlineNanos());
      throw new AssertionError("caught insert failure committed");
    } catch (RuntimeException terminal) {
      if (first[0] == null) throw new AssertionError("before new append failure point", terminal);
      check(terminal == first[0], "actual Work.finish rejects original first slot");
    } finally {
      fault.armed = false;
    }
    var after = SessionDependencyScenario.snapshot(db);
    check(before.size() == 22 && after.size() == 22, "all22 table snapshots captured");
    for (int i = 0; i < before.size(); i++)
      check(before.get(i).equals(after.get(i)), "rollback22 table ordinal " + i);
    check(q.scratchLive() == 65536, "append failure final live65536");
    System.out.println(
        "APPEND_FAILURE_SQL "
            + Payloads.json(
                Map.of(
                    "fact",
                    fact,
                    "protectedMapper",
                    "SessionHistoryStorageMapper.body",
                    "protectedSql",
                    fault.protectedSql,
                    "failedMapper",
                    "SessionPublicEventAppendMapper.insert",
                    "failedSql",
                    fault.failedSql,
                    "sqlState",
                    fault.marker.getSQLState(),
                    "resources",
                    q.resources(),
                    "live",
                    q.scratchLive())));
    System.out.flush();
  }
}
