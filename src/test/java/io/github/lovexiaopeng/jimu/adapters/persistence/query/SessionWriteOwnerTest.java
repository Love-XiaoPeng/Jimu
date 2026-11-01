package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 真实Spring owner配Executor代理；这里只证明拦截/尾部行为，SQL另走私库。 */
class SessionWriteOwnerTest {
  private final AtomicInteger writes = new AtomicInteger();

  private void write(String id, String sql, Object parameter) {
    var configuration = new Configuration();
    var statement =
        new MappedStatement.Builder(
                configuration,
                id,
                new RawSqlSource(configuration, sql, Object.class),
                SqlCommandType.UPDATE)
            .build();
    var executor =
        (Executor)
            Proxy.newProxyInstance(
                Executor.class.getClassLoader(),
                new Class<?>[] {Executor.class},
                (proxy, method, args) -> {
                  writes.incrementAndGet();
                  return 1;
                });
    try {
      new SessionQueryInterceptor()
          .intercept(
              new Invocation(
                  executor,
                  Executor.class.getMethod("update", MappedStatement.class, Object.class),
                  new Object[] {statement, parameter}));
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void missingOrDifferentQueryPoisonsEvenWhenBusinessCatchesIt() {
    for (boolean wrong : java.util.List.of(false, true)) {
      var f = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          IllegalStateException.class,
          () ->
              f.execute(
                  () -> {
                    Object parameter =
                        wrong
                            ? Map.of(
                                "query",
                                new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null))
                            : Map.of();
                    assertThrows(
                        IllegalStateException.class,
                        () ->
                            write(
                                "private.noQuery", "UPDATE task SET tools_digest='x'", parameter));
                    return null;
                  }));
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
    }
    assertEquals(0, writes.get());
    write("private.legacy", "UPDATE task SET tools_digest='x'", Map.of());
    assertEquals(1, writes.get());
  }

  @Test
  void unknownWritesInvalidateStagingRegardlessOfSqlShape() {
    for (String sql :
        java.util.List.of(
            "UPDATE `installation` SET epoch=2",
            "UPDATE task SET caller_binding_digest='x'",
            "UPDATE task_session_lineage SET graph_digest='x'",
            "DELETE FROM unknown_table")) {
      var f = new SessionQueryTransactionTest.Fixture();
      f.execute(
          () -> {
            var owner = SessionQueryTransaction.current(f.context);
            var invalidations = new AtomicInteger();
            owner.invalidator(
                this,
                keys -> {
                  assertNull(keys);
                  invalidations.incrementAndGet();
                });
            write(
                "private.unregistered",
                sql,
                Map.of("query", f.context, "tenant", "tenant", "taskId", "source"));
            assertEquals(1, invalidations.get());
            return null;
          });
      assertEquals(1, f.commits);
    }
  }
}
