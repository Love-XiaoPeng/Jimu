package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionJdbcTemplate;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual MyBatis ResultSet/empty result/ignored columns, cumulative limits and commit deadline. */
final class QueryAdapterScenario {
  interface ProbeMapper {
    @Select("SELECT NULL AS nullable")
    List<java.util.Map<String, Object>> nullProjection(@Param("query") SessionQueryContext query);

    @Select("SELECT CAST('2026-01-02 03:04:05.000000' AS DATETIME(6)) AS observed_at")
    List<String> observed(@Param("query") SessionQueryContext query);

    @Select(
        "SELECT tenant_id,storage_held,NULL AS nullable,UNHEX('000102') AS binary_value FROM tenant_budget WHERE tenant_id=#{tenant}")
    List<String> projected(
        @Param("tenant") String tenant, @Param("query") SessionQueryContext query);

    @Select("SELECT storage_held FROM tenant_budget WHERE tenant_id=#{tenant}")
    List<Long> held(@Param("tenant") String tenant, @Param("query") SessionQueryContext query);
  }

  static void verify(AnnotationConfigApplicationContext beans) {
    var source = beans.getBean(DataSource.class);
    var factory = beans.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
    factory.getConfiguration().addMapper(ProbeMapper.class);
    var mapper = beans.getBean(SqlSessionTemplate.class).getMapper(ProbeMapper.class);
    var nullQuery = context(new QueryResources(0, 0, 0));
    var nullable = mapper.nullProjection(nullQuery);
    check(
        nullable.size() == 1
            && nullable.getFirst() != null
            && nullable.getFirst().containsKey("nullable")
            && nullable.getFirst().get("nullable") == null,
        "all-null-row-retains-jdbc-map-shape");
    check(
        nullQuery.resources().reads() == 1 && nullQuery.resources().bytes() == 17,
        "all-null-row-actual-metering");
    var work =
        new SessionQueryTransaction(source, beans.getBean(DataSourceTransactionManager.class));
    var query = context(new QueryResources(0, 0, 0));
    work.required(
        query,
        () -> {
          var jdbc = new SessionJdbcTemplate(source, query);
          check(mapper.projected("b1-commit", query).size() == 1, "mapper-actual-result");
          long bytes = query.resources().bytes();
          check(
              bytes == 8 + 9 + 9 + 8 + 12 + 1 + 8 + 8 + 1 + 8 + 12 + 3,
              "all-provider-columns-and-binary-bytes");
          check(
              mapper.projected("b1-commit", query).size() == 1
                  && query.resources().reads() == 2
                  && query.resources().bytes() == bytes * 2,
              "mapper-no-cache-reuse-or-double-charge");
          check(
              mapper.held("absent-private-tenant", query).isEmpty()
                  && query.resources().reads() == 3,
              "empty-mapper-result-charged-once");
          jdbc.queryForList(
              "SELECT tenant_id,storage_held,NULL AS nullable,UNHEX('000102') AS binary_value FROM tenant_budget WHERE tenant_id=?",
              "b1-commit");
          check(
              query.resources().reads() == 4 && query.resources().bytes() == bytes * 3,
              "jdbc-and-mapper-identical-provider-accounting");
          jdbc.queryForObject("SELECT 1", Long.class);
          check(query.resources().reads() == 5, "typed-static-jdbc-query-metered");
          return null;
        });
    check(query.resources().operations() > 0, "explicit-cumulative-operation-count");
    var datetime = context(new QueryResources(0, 0, 0));
    work.required(
        datetime,
        () -> {
          mapper.observed(datetime);
          check(
              datetime.resources().bytes() == 8 + 11 + 27,
              "mapper-time-scalar-fixed-utc-microseconds");
          new SessionJdbcTemplate(source, datetime)
              .queryForList(
                  "SELECT CAST('2026-01-02 03:04:05.000000' AS DATETIME(6)) AS observed_at");
          check(
              datetime.resources().bytes() == 2 * (8 + 11 + 27),
              "jdbc-time-scalar-identical-to-mapper");
          return null;
        });
    var boundary = context(new QueryResources(4095, 0, 0));
    work.required(
        boundary,
        () -> {
          mapper.held("absent-private-tenant", boundary);
          check(boundary.resources().reads() == 4096, "read-N-accepted");
          reject("SESSION_HISTORY_LIMIT", () -> mapper.held("absent-private-tenant", boundary));
          return null;
        });
    var byteLimit = context(new QueryResources(0, 16777216 - 87, 0));
    work.required(
        byteLimit,
        () -> {
          mapper.projected("b1-commit", byteLimit);
          check(byteLimit.resources().bytes() == 16777216, "actual-byte-N-accepted");
          reject("SESSION_HISTORY_LIMIT", () -> mapper.held("b1-commit", byteLimit));
          return null;
        });
    var operations = context(new QueryResources(0, 0, 2097150));
    work.required(
        operations,
        () -> {
          mapper.held("b1-commit", operations);
          check(operations.resources().operations() == 2097152, "actual-operation-N-accepted");
          reject("SESSION_HISTORY_LIMIT", () -> mapper.held("b1-commit", operations));
          return null;
        });
    var scratch = context(new QueryResources(0, 0, 0));
    work.required(
        scratch,
        () -> {
          reject(
              "SESSION_HISTORY_LIMIT",
              () ->
                  new SessionJdbcTemplate(source, scratch)
                      .queryForObject("SELECT REPEAT('x',5242880)", String.class));
          return null;
        });
    var expired = new SessionQueryContext(System.nanoTime() - 1, new QueryResources(0, 0, 0));
    reject(
        "SESSION_QUERY_TIMEOUT",
        () ->
            work.required(
                expired,
                () -> {
                  throw new AssertionError("expired work ran");
                }));
    var commit =
        new SessionQueryContext(System.nanoTime() + 1_000_000_000L, new QueryResources(0, 0, 0));
    var plain = new JdbcTemplate(source);
    long before =
        plain.queryForObject(
            "SELECT storage_held FROM tenant_budget WHERE tenant_id='b1-commit'", Long.class);
    reject(
        "SESSION_QUERY_TIMEOUT",
        () ->
            work.required(
                commit,
                () -> {
                  new SessionJdbcTemplate(source, commit)
                      .update(
                          "UPDATE tenant_budget SET storage_held=storage_held+1 WHERE tenant_id=?",
                          "b1-commit");
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                          while (System.nanoTime() < commit.deadlineNanos() + 10_000_000L)
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                        }
                      });
                  return null;
                }));
    check(
        plain.queryForObject(
                "SELECT storage_held FROM tenant_budget WHERE tenant_id='b1-commit'", Long.class)
            == before,
        "commit-deadline-no-late-write");
    var after = context(new QueryResources(0, 0, 0));
    work.required(
        after,
        () -> {
          check(mapper.held("b1-commit", after).size() == 1, "next-query-no-context-leak");
          return null;
        });
    System.out.println("PASS explicit query MySQL adapters");
  }

  private static SessionQueryContext context(QueryResources prior) {
    return new SessionQueryContext(System.nanoTime() + 2_000_000_000L, prior);
  }

  private static void reject(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("Expected " + code);
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (!(cause instanceof TaskFailure) && cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof TaskFailure task) || !code.equals(task.code())) throw failure;
      System.out.println("PASS " + code);
    }
  }

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
}
