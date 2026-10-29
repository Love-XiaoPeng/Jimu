package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.jdbc.core.JdbcTemplate;

/** 实际MyBatis同锁十一列/长度/完整Caller，和物化之前拒绝的22表回滚。 */
public final class SessionAcceptedEventSqlScenario {
  private SessionAcceptedEventSqlScenario() {}

  private static void check(boolean valid, String text) {
    if (!valid) throw new AssertionError(text);
    System.out.println("PASS " + text);
    System.out.flush();
  }

  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "query",
          args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  public static final class Observe implements Interceptor {
    int rows;

    public Object intercept(Invocation call) throws Throwable {
      if (((MappedStatement) call.getArgs()[0])
          .getId()
          .endsWith("TaskAdmissionMapper.lockAcceptedEvent")) rows++;
      return call.proceed();
    }
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

  static void verify(JdbcLedger root, JdbcTemplate db, String id) throws Exception {
    var observer = new Observe();
    root.mappers.session().getConfiguration().addInterceptor(observer);
    var baseline = SessionDependencyScenario.snapshot(db);
    for (String goal : List.of("", "ASCII", "中文😀\0tail  ", "x".repeat(1000))) {
      var v = view(root);
      var q = v.queryContext();
      Map<String, Object>[] held = new Map[1];
      try {
        v.sessionTransaction(
            () -> {
              var full = v.locked("tenant-a", id);
              db.update("UPDATE task SET goal=? WHERE tenant_id='tenant-a' AND id=?", goal, id);
              var sql = v.mappers.session().getMapper(TaskAdmissionMapper.class);
              var sizes = sql.lockAcceptedEventSizes(q, "tenant-a", id).getFirst();
              check(
                  sizes.size() == 2
                      && (sizes.get("goal") instanceof Integer || sizes.get("goal") instanceof Long)
                      && ((Number) sizes.get("goal")).longValue()
                          == goal.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                  "real UTF8 OCTET_LENGTH Integer/Long incl NUL Unicode trailing spaces: " + sizes);
              held[0] = SessionAcceptedTaskRows.load(sql, q, "tenant-a", id, true);
              check(
                  held[0].size() == 11 && held[0].get("goal").equals(goal),
                  "actual event11 exact goal");
              check(
                  MapperSessionScopeRecords.caller(full)
                      .equals(MapperSessionScopeRecords.caller(held[0])),
                  "event11 actual full Caller unchanged");
              check(
                  held[0].get("record_version") instanceof Integer
                      && held[0].get("installation_epoch") instanceof Long
                      && held[0].get("created_at") instanceof java.time.LocalDateTime,
                  "actual Integer Long UTC LocalDateTime types");
              for (String key :
                  List.of(
                      "revision",
                      "updated_at",
                      "cancel_requested_at",
                      "deadline",
                      "code",
                      "summary",
                      "next_event_seq",
                      "tools_snapshot",
                      "mcp_snapshot",
                      "mcp_digest"))
                check(!held[0].containsKey(key), "event11 does not expose " + key);
              throw new IllegalStateException("event11-fixture-rollback");
            },
            q.deadlineNanos());
      } catch (IllegalStateException expected) {
        check(
            expected.getMessage().equals("event11-fixture-rollback"),
            "only explicit fixture rollback");
      }
      check(held[0].isEmpty() && q.scratchLive() == 65536, "event11 clear before refund");
      check(
          baseline.equals(SessionDependencyScenario.snapshot(db)),
          "event11 every actual variant22 rollback");
    }
    var v = view(root);
    var q = v.queryContext();
    int reads = observer.rows;
    try {
      v.sessionTransaction(
          () -> {
            v.locked("tenant-a", id);
            db.update(
                "UPDATE task SET goal=REPEAT('x',1000) WHERE tenant_id='tenant-a' AND id=?", id);
            Object pressure = new Object();
            q.retainScratch(pressure, 1048576 - q.scratchLive() - 16000);
            try {
              SessionAcceptedTaskRows.load(
                  v.mappers.session().getMapper(TaskAdmissionMapper.class),
                  q,
                  "tenant-a",
                  id,
                  true);
            } catch (RuntimeException caught) {
              check(
                  caught == q.recordedFailure(),
                  "projection allocation rejection original first slot");
            } finally {
              q.releaseScratch(pressure);
            }
            return null;
          },
          q.deadlineNanos());
      throw new AssertionError("preallocation refusal required");
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure limit) {
      check(
          limit.code().equals("SESSION_HISTORY_LIMIT") && limit == q.recordedFailure(),
          "real limit sticky through finish");
    }
    check(observer.rows == reads, "no actual event row SELECT before successful reservation");
    check(
        q.scratchLive() == 65536 && baseline.equals(SessionDependencyScenario.snapshot(db)),
        "preallocation22 rollback/live65536");
  }
}
