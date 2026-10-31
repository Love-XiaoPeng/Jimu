package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 发布前scope版本/Caller错误经真实生产reader；假Mapper/JDBC只证明首错及事务回滚。 */
class JdbcSessionScopeFailureTest {
  @Test
  void caughtPrePublicationCorruptionAndCallerExceptionCannotCommitOrAddWork() throws Exception {
    var source =
        new AbstractDataSource() {
          public Connection getConnection() {
            throw new AssertionError("No SQL");
          }

          public Connection getConnection(String user, String password) {
            return getConnection();
          }
        };
    var template =
        new SqlSessionTemplate(
            new DefaultSqlSessionFactory(
                new Configuration(
                    new Environment("scope-failure", new JdbcTransactionFactory(), source)))) {
          @SuppressWarnings("unchecked")
          public <T> T getMapper(Class<T> type) {
            return (T)
                Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (p, method, args) -> {
                      if (!method.getName().equals("scope")) throw new AssertionError(method);
                      // 版本先失败；此手工坏raw绝不作为合法scope发布。
                      return List.of(
                          new HashMap<String, Object>(
                              Map.of(
                                  "record_version",
                                  2L,
                                  "authority_version",
                                  1L,
                                  "authority_bytes",
                                  2L,
                                  "candidate_count",
                                  0L,
                                  "retained",
                                  1L,
                                  "authority_snapshot",
                                  "{}")));
                    });
          }
        };
    var root = new JdbcLedger(source, null, null, null, null, template);
    for (String mode : List.of("version", "caller")) {
      var factory =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      factory.setAccessible(true);
      var ledger =
          (JdbcLedger)
              factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      var query = ledger.queryContext();
      query.restrictLineage();
      var row =
          MapperSessionScopeRecords.row(
              new SessionScopeRecords.Caller(
                  "t",
                  "c",
                  "a",
                  SessionLineagePolicyV5.CAPABILITIES,
                  "tools",
                  1,
                  "QUEUED",
                  LocalDateTime.now(),
                  "digest"));
      if (mode.equals("caller")) row.put("installation_epoch", "wrong numeric type");
      var fixture = new SessionQueryTransactionTest.Fixture();
      RuntimeException[] first = {null};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.transaction.required(
                      query,
                      () -> {
                        first[0] =
                            assertThrows(
                                RuntimeException.class, () -> ledger.sessionScopes.require(row));
                        if (mode.equals("version"))
                          assertEquals(409, ((TaskFailure) first[0]).status());
                        long operations = query.resources().operations();
                        assertSame(
                            first[0],
                            assertThrows(RuntimeException.class, () -> query.operation(65537)));
                        assertSame(
                            first[0], assertThrows(RuntimeException.class, query::checkHealthy));
                        assertEquals(operations, query.resources().operations());
                        return null;
                      }));
      assertSame(first[0], observed);
      assertEquals(0, fixture.commits);
      assertEquals(1, fixture.rollbacks);
      assertEquals(65536, query.scratchLive());
    }
  }
}
