package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** 合法submit/claim/prepare后真实保护SQL与访问；仅窄持久化链，不冒称完整M2。 */
public final class SessionAccessSqlScenario {
  private SessionAccessSqlScenario() {}

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static boolean causedBy(Throwable failure, Throwable marker) {
    for (Throwable current = failure; current != null; current = current.getCause())
      if (current == marker) return true;
    return false;
  }

  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "query",
          args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  public static final class AccessFault implements Interceptor {
    final DataSource source;
    String mode;
    RuntimeException marker;
    SQLException sql;

    AccessFault(DataSource source) {
      this.source = source;
    }

    public Object intercept(Invocation call) throws Throwable {
      if (mode != null
          && ((MappedStatement) call.getArgs()[0])
              .getId()
              .endsWith("task.mapper.TaskAdmissionMapper.lockActor")) {
        String current = mode;
        mode = null;
        if (current.equals("409")) marker = new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
        else {
          var connection = DataSourceUtils.getConnection(source);
          try (var statement = connection.createStatement()) {
            statement.executeQuery("SELECT * FROM xiao126_deliberately_absent_access_table");
            throw new AssertionError("server SQL error required");
          } catch (SQLException failure) {
            sql = failure;
            marker = new DataAccessResourceFailureException("access SQL marker", failure);
          } finally {
            DataSourceUtils.releaseConnection(connection, source);
          }
        }
        throw marker;
      }
      return call.proceed();
    }
  }

  static void verify(JdbcLedger root, JdbcTemplate db, Lease lease) throws Exception {
    String context = root.sessionContext(lease);
    var model = root.modelSnapshot(lease);
    String body =
        Payloads.json(
            java.util.Map.of(
                "messages", List.of(java.util.Map.of("role", "user", "content", context))));
    String request =
        Payloads.json(
            new DurableAgent.Checkpoint(
                2,
                model.model(),
                List.of(new DurableAgent.WireMessage("user", context, List.of(), List.of())),
                lease.capabilities(),
                lease.installationEpoch(),
                model.stream(),
                ModelProfiles.digest(model),
                body,
                Payloads.hash(body)));
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    var fault = new AccessFault(java.util.Objects.requireNonNull(db.getDataSource()));
    root.mappers.session().getConfiguration().addInterceptor(fault);
    for (String mode : List.of("403", "409", "sql")) {
      var before = SessionDependencyScenario.snapshot(db);
      var v =
          (JdbcLedger)
              factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      var q = v.queryContext();
      q.restrictLineage();
      RuntimeException[] original = {null};
      try {
        v.sessionTransaction(
            () -> {
              // 加入同一真实owner，先产生合法PREPARED行；后续首错回滚时不越过终算。
              var step = v.prepare(lease, request);
              check(step != null, "access fixture legal prepare " + mode);
              var records =
                  new MapperModelWorkflows(v).lockResponse(lease, step.id(), "access-local");
              records.storeResponse("{\"local-protected-write\":true}");
              check(
                  "RESPONDED"
                      .equals(
                          db.queryForObject(
                              "SELECT state FROM step WHERE tenant_id=? AND id=?",
                              String.class,
                              lease.tenant(),
                              step.id())),
                  "natural storeResponse SQL executed " + mode);
              var task = v.locked(lease.tenant(), lease.taskId());
              if (mode.equals("403"))
                db.update(
                    "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
                    lease.tenant(),
                    lease.actor());
              else fault.mode = mode;
              try {
                // 真实Service.visibleView捕获；不额外嵌套tx的出站健康检查遮住返回值。
                var redacted = v.view(task);
                check(
                    mode.equals("403")
                        && redacted.summary() == null
                        && redacted.artifacts().isEmpty(),
                    "Service caught actual protected 403 without leaking summary/artifacts");
              } catch (RuntimeException caught) {
                if (mode.equals("403"))
                  throw new AssertionError("403 must be caught by visibleView", caught);
                check(caught == q.recordedFailure(), "access caller sees first object " + mode);
              }
              original[0] = q.recordedFailure();
              check(original[0] != null, "natural access failure registered " + mode);
              if (mode.equals("403"))
                check(
                    ((TaskFailure) original[0]).status() == 403,
                    "protected actor denial retains original 403");
              else {
                check(
                    causedBy(original[0], fault.marker), "original access cause retained " + mode);
                if (mode.equals("sql"))
                  check(
                      causedBy(original[0], fault.sql) && "42S02".equals(fault.sql.getSQLState()),
                      "actual MySQL cause and SQLState");
              }
              q.restrictDeadline(System.nanoTime() - 1);
              try {
                q.operation(65537);
                throw new AssertionError("must keep first error");
              } catch (RuntimeException later) {
                check(later == original[0], "timeout/429 keeps first " + mode);
              }
              return null; // Work.finish must still reject the caught failure.
            },
            q.deadlineNanos());
        throw new AssertionError("protected write committed after access fault");
      } catch (RuntimeException finalFailure) {
        if (original[0] == null)
          throw new AssertionError("before natural access: " + mode, finalFailure);
        check(
            original[0] != null && finalFailure == original[0],
            "finish original access identity " + mode);
      } finally {
        fault.mode = null;
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "natural access 22-table rollback " + mode);
      check(q.scratchLive() == 65536, "natural access references released " + mode);
    }
  }
}
