package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 原history与accepted专属投影在同私库的独立对照、早退与损坏回滚。 */
public final class SessionEventHistorySqlScenario {
  private SessionEventHistorySqlScenario() {}

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
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

  @org.apache.ibatis.plugin.Intercepts(
      @org.apache.ibatis.plugin.Signature(
          type = org.apache.ibatis.executor.resultset.ResultSetHandler.class,
          method = "handleCursorResultSets",
          args = {java.sql.Statement.class}))
  public static final class ExpireOpening implements org.apache.ibatis.plugin.Interceptor {
    io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query;
    int opened;

    public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
      if (query != null) {
        check(
            ((java.sql.Statement) call.getArgs()[0]).getResultSet() != null,
            "actual streaming result exists before deadline rejection");
        opened++;
        query.restrictDeadline(System.nanoTime() - 1);
        query = null;
      }
      return call.proceed();
    }
  }

  static void verify(JdbcLedger root, JdbcTemplate db, String id) throws Exception {
    var before = SessionDependencyScenario.snapshot(db);
    var v = view(root);
    var q = v.queryContext();
    v.sessionTransaction(
        () -> {
          var task = v.locked("tenant-a", id);
          var full = v.sessions.history(task, true);
          var narrow = v.sessions.acceptedEventHistory(task, true);
          check(full.size() == 15 && narrow.size() == 8, "history full15/event8 stay distinct");
          check(
              full.entrySet().containsAll(narrow.entrySet()),
              "actual MySQL event8 equals full history");
          check(
              narrow.get("record_version") instanceof Integer
                  && narrow.get("next_seq") instanceof Long
                  && narrow.get("retain_until") instanceof java.time.LocalDateTime,
              "actual MySQL Integer/Long/LocalDateTime adaptation");
          long prior =
              v.sessions.append(
                  task, "accepted-user", "user.message", null, null, false, null, null, 0);
          check(prior == 0, "accepted prior returns before dereferencing event/body arguments");
          return null;
        },
        q.deadlineNanos());
    check(
        before.equals(SessionDependencyScenario.snapshot(db)),
        "prior early return leaves all22 tables unchanged");
    for (String mutation :
        List.of(
            "record_version=2",
            "history_version=4",
            "binding_snapshot='{}'",
            "binding_digest=REPEAT('0',64)",
            "content_state='PURGED',body_bytes=0,cleanup_receipt='negative-fixture',held_bytes=metadata_bytes",
            "retain_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))")) {
      var scoped = view(root);
      var query = scoped.queryContext();
      RuntimeException[] first = {null};
      try {
        scoped.sessionTransaction(
            () -> {
              var task = scoped.locked("tenant-a", id);
              try {
                db.update(
                    "UPDATE task_session_history SET "
                        + mutation
                        + " WHERE tenant_id='tenant-a' AND task_id=?",
                    id);
              } catch (org.springframework.dao.DataAccessException constraint) {
                check(
                    mutation.equals("record_version=2")
                        && constraint.getMostSpecificCause() instanceof java.sql.SQLException sql
                        && sql.getErrorCode() == 3819,
                    "only record_version blocked by actual DDL");
                first[0] = constraint;
                throw constraint;
              }
              try {
                scoped.sessions.acceptedEventHistory(task, true);
                throw new AssertionError("must reject " + mutation);
              } catch (RuntimeException failure) {
                first[0] = failure;
                check(
                    failure instanceof TaskFailure
                        && (mutation.startsWith("content_state")
                                || mutation.startsWith("retain_until")
                            ? ((TaskFailure) failure).status() == 410
                            : ((TaskFailure) failure).status() == 409),
                    "eventHistory rejects " + mutation);
              }
              return null;
            },
            query.deadlineNanos());
        throw new AssertionError("caught history corruption must still fail finish");
      } catch (RuntimeException failure) {
        if (first[0] == null)
          throw new AssertionError("before history proof: " + mutation, failure);
        check(failure == first[0], "eventHistory first identity through finish " + mutation);
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "eventHistory22 rollback " + mutation);
      check(query.scratchLive() == 65536, "eventHistory released " + mutation);
    }
    var expire = new ExpireOpening();
    root.mappers.session().getConfiguration().addInterceptor(expire);
    var scoped = view(root);
    var query = scoped.queryContext();
    RuntimeException[] first = {null};
    try {
      scoped.sessionTransaction(
          () -> {
            var task = scoped.locked("tenant-a", id);
            expire.query = query;
            try (var cursor =
                scoped.sessions.mapperCursor(
                    "SessionHistoryRetentionMapper.metadataModelDispatch", task)) {
              throw new AssertionError("deadline must refuse before cursor escapes");
            } catch (java.io.IOException failure) {
              throw new AssertionError(failure);
            } catch (RuntimeException failure) {
              first[0] = query.recordedFailure();
              check(
                  first[0] instanceof TaskFailure && ((TaskFailure) first[0]).status() == 504,
                  "stream opening preserves original timeout");
            }
            return null;
          },
          query.deadlineNanos());
      throw new AssertionError("deadline must roll back");
    } catch (RuntimeException failure) {
      check(failure == first[0], "stream opening rollback confirmed with original identity");
    } finally {
      expire.query = null;
    }
    check(
        expire.opened == 1 && before.equals(SessionDependencyScenario.snapshot(db)),
        "stream opening failure closes provider and rolls back all22 tables");
  }
}
