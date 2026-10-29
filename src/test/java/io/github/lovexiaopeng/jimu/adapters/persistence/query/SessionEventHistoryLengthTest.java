package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;

/** provider模拟验证能力隔离；真实SQL/业务提交另由私库场景证明。 */
class SessionEventHistoryLengthTest {
  static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  static ResultSet rows(String[] labels, Object... values) throws Exception {
    var data = RowSetProvider.newFactory().createCachedRowSet();
    var meta = new RowSetMetaDataImpl();
    meta.setColumnCount(labels.length);
    for (int i = 1; i <= labels.length; i++) {
      meta.setColumnName(i, labels[i - 1]);
      meta.setColumnLabel(i, labels[i - 1]);
      meta.setColumnType(
          i, values.length > 0 && values[i - 1] instanceof Number ? Types.BIGINT : Types.VARCHAR);
    }
    data.setMetaData(meta);
    if (values.length != 0) {
      data.moveToInsertRow();
      for (int i = 1; i <= values.length; i++) data.updateObject(i, values[i - 1]);
      data.insertRow();
      data.moveToCurrentRow();
    }
    data.beforeFirst();
    return data;
  }

  static Statement statement() {
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (p, m, a) -> null);
  }

  static SessionEventHistoryRows.Slot start(SessionQueryContext q) {
    var owner = SessionQueryTransaction.current(q);
    owner.locked("t", "x");
    return owner.beginEventHistory("t", "x");
  }

  static void size(SessionQueryContext q, SessionEventHistoryRows.Slot slot, Object length)
      throws Exception {
    var s = statement();
    var params = Map.of("query", q, "tenant", "t", "taskId", "x");
    slot.prepared(SessionEventHistoryRows.SIZE, params, SessionQueryTransaction.connection(q), s);
    try (var r =
        QueryResults.meter(
            rows(new String[] {"binding_snapshot_utf8_bytes"}, length),
            q,
            SessionEventHistoryRows.SIZE,
            slot,
            params,
            s)) {
      assertTrue(r.next());
      assertTrue(!r.next());
    }
  }

  static long raw(SessionQueryContext q, SessionEventHistoryRows.Slot slot, String value)
      throws Exception {
    var s = statement();
    var params = Map.of("query", q, "tenant", "t", "taskId", "x");
    slot.prepared(SessionEventHistoryRows.RAW, params, SessionQueryTransaction.connection(q), s);
    long before = q.resources().bytes();
    try (var r =
        QueryResults.meter(
            rows(
                new String[] {
                  "record_version",
                  "history_version",
                  "binding_snapshot",
                  "binding_digest",
                  "content_state",
                  "next_seq",
                  "retain_until",
                  "retained"
                },
                1,
                5,
                value,
                "d",
                "PRESENT",
                1,
                "2026",
                1),
            q,
            SessionEventHistoryRows.RAW,
            slot,
            params,
            s)) {
      assertTrue(r.next());
      assertEquals(value, r.getString(3));
      assertTrue(!r.next());
    }
    return q.resources().bytes() - before;
  }

  @Test
  void integersLongsUnicodeNulTrailingAndNullKeepExactProofBytes() {
    for (String value :
        new String[] {null, "", "abc", "中😀", "x\u0000尾  ", "x".repeat(2174), "中".repeat(1000)}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      f.transaction.required(
          q,
          () -> {
            try {
              var slot = start(q);
              long length =
                  value == null
                      ? 1
                      : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
              size(q, slot, value == null ? Integer.valueOf(1) : Long.valueOf(length));
              long bytes = raw(q, slot, value);
              var ordinary = query();
              try (var r = ordinary.result()) {
                r.row();
                String[] labels = {
                  "record_version",
                  "history_version",
                  "binding_snapshot",
                  "binding_digest",
                  "content_state",
                  "next_seq",
                  "retain_until",
                  "retained"
                };
                Object[] values = {1, 5, value, "d", "PRESENT", 1, "2026", 1};
                for (int i = 0; i < labels.length; i++) r.column(labels[i], values[i]);
              }
              assertEquals(ordinary.resources().bytes(), bytes);
              assertEquals(2, q.resources().reads());
              assertEquals(value == null ? null : 1L, q.completedWork().get("result.sqlLength"));
              return null;
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void writesWrongQueryKeysAndConnectionCannotConsumeTheSize() {
    for (String mode : new String[] {"write", "query", "tenant", "connection"}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    try {
                      var slot = start(q);
                      size(q, slot, 3L);
                      if (mode.equals("write")) SessionQueryTransaction.current(q).mutation();
                      var p =
                          Map.of(
                              "query",
                              mode.equals("query") ? query() : q,
                              "tenant",
                              mode.equals("tenant") ? "other" : "t",
                              "taskId",
                              "x");
                      var failure =
                          assertThrows(
                              IllegalStateException.class,
                              () ->
                                  slot.prepared(
                                      SessionEventHistoryRows.RAW,
                                      p,
                                      mode.equals("connection")
                                          ? null
                                          : SessionQueryTransaction.connection(q),
                                      statement()));
                      throw SessionQueryTransaction.current(q).poisonProof(failure);
                    } catch (RuntimeException e) {
                      throw e;
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void invalidSizeShapesAndUpperLowerBoundsRemainSticky() {
    for (Object length : new Object[] {-1L, "3", 0L, 10L}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  q,
                  () -> {
                    try {
                      var slot = start(q);
                      size(q, slot, length);
                      raw(q, slot, "abc");
                      throw new AssertionError("untrusted size accepted");
                    } catch (RuntimeException e) {
                      throw e;
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
      var first = q.recordedFailure();
      assertTrue(first != null);
      assertSame(first, assertThrows(RuntimeException.class, q::checkHealthy));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void capabilityHasNoPublicNumericConstructorAndCannotBeReusedOrCrossQuery() {
    assertEquals(0, SessionQueryContext.SqlUtf8Length.class.getConstructors().length);
    for (boolean crossQuery : new boolean[] {false, true}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      f.transaction.required(
          q,
          () -> {
            try {
              var slot = start(q);
              size(q, slot, 3L);
              // 仅测试取出真实provider已经签发的能力；不开放测试专用生产入口。
              var field = slot.getClass().getDeclaredField("capability");
              field.setAccessible(true);
              var cap = (SessionQueryContext.SqlUtf8Length) field.get(slot);
              if (!crossQuery) raw(q, slot, "abc");
              var other = query();
              try (var r = other.result(SessionEventHistoryRows.RAW)) {
                assertThrows(
                    IllegalStateException.class,
                    () -> r.columnSqlUtf8("binding_snapshot", "abc", cap));
              }
              assertTrue(other.recordedFailure() != null);
              return null;
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void ordinaryAndReflectiveFactoryCallsPoisonBeforeAnyReadAndCannotCommit() {
    for (boolean reflected : new boolean[] {false, true}) {
      var q = query();
      var f = new SessionQueryTransactionTest.Fixture();
      var thrown =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.transaction.required(
                      q,
                      () -> {
                        RuntimeException first;
                        if (reflected) {
                          var invocation =
                              assertThrows(
                                  java.lang.reflect.InvocationTargetException.class,
                                  () ->
                                      SessionQueryContext.class
                                          .getMethod("sqlUtf8LengthResult", String.class)
                                          .invoke(q, SessionEventHistoryRows.SIZE));
                          first = (RuntimeException) invocation.getCause();
                        } else
                          first =
                              assertThrows(
                                  IllegalStateException.class,
                                  () -> q.sqlUtf8LengthResult(SessionEventHistoryRows.SIZE));
                        assertEquals(0, q.resources().reads());
                        assertSame(first, q.recordedFailure());
                        assertSame(
                            first,
                            assertThrows(
                                RuntimeException.class,
                                () -> q.sqlUtf8LengthResult(SessionEventHistoryRows.SIZE)));
                        return null; // catch后仍由finish/beforeCommit原首错回滚
                      }));
      assertSame(q.recordedFailure(), thrown);
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void sameNamedProviderFromAnotherClassLoaderCannotIssue(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
    String name = "io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults";
    var source = temporary.resolve("QueryResults.java");
    java.nio.file.Files.writeString(
        source,
        """
        package io.github.lovexiaopeng.jimu.adapters.persistence.query;
        import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
        public class QueryResults {
          public static Object issue(SessionQueryContext q, String statement) {
            return q.sqlUtf8LengthResult(statement);
          }
        }
        """);
    assertEquals(
        0,
        javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                temporary.toString(),
                source.toString()));
    try (var loader =
        new java.net.URLClassLoader(
            new java.net.URL[] {temporary.toUri().toURL()}, getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String requested, boolean resolve)
              throws ClassNotFoundException {
            if (!requested.equals(name)) return super.loadClass(requested, resolve);
            var type = findLoadedClass(requested);
            if (type == null) type = findClass(requested);
            if (resolve) resolveClass(type);
            return type;
          }
        }) {
      var q = query();
      var provider = loader.loadClass(name);
      assertTrue(provider != QueryResults.class);
      var failure =
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () ->
                  provider
                      .getMethod("issue", SessionQueryContext.class, String.class)
                      .invoke(null, q, SessionEventHistoryRows.SIZE));
      assertSame(failure.getCause(), q.recordedFailure());
      assertEquals(0, q.resources().reads());
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void issuerChecksCannotReplaceAnEarlierFailureOrSpendAfterIt() {
    var q = query();
    var first = new IllegalStateException("original failure");
    q.recordFailure(first);
    var before = q.resources();
    assertSame(
        first,
        assertThrows(
            RuntimeException.class, () -> q.sqlUtf8LengthResult(SessionEventHistoryRows.SIZE)));
    assertEquals(before, q.resources());
  }

  @Test
  void sizeCursorCloseFailureIsStickyAndClearsItsOwner() {
    var q = query();
    var f = new SessionQueryTransactionTest.Fixture();
    var driver = new java.sql.SQLException("private size close failure");
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      try {
                        var slot = start(q);
                        var statement = statement();
                        var params = Map.of("query", q, "tenant", "t", "taskId", "x");
                        slot.prepared(
                            SessionEventHistoryRows.SIZE,
                            params,
                            SessionQueryTransaction.connection(q),
                            statement);
                        var actual = rows(new String[] {"binding_snapshot_utf8_bytes"}, 3L);
                        var failing =
                            (ResultSet)
                                Proxy.newProxyInstance(
                                    ResultSet.class.getClassLoader(),
                                    new Class<?>[] {ResultSet.class},
                                    (proxy, method, args) -> {
                                      if (method.getName().equals("close")) {
                                        actual.close();
                                        throw driver;
                                      }
                                      try {
                                        return method.invoke(actual, args);
                                      } catch (
                                          java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                      }
                                    });
                        var result =
                            QueryResults.meter(
                                failing, q, SessionEventHistoryRows.SIZE, slot, params, statement);
                        assertTrue(result.next());
                        assertTrue(!result.next());
                        var first = assertThrows(RuntimeException.class, result::close);
                        assertSame(driver, first.getCause());
                        assertSame(first, q.recordedFailure());
                        result.close();
                        return null; // caught driver failure must still prevent transaction commit
                      } catch (RuntimeException failure) {
                        throw failure;
                      } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                      }
                    }));
    assertSame(q.recordedFailure(), thrown);
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void extraProviderRowPoisonsBeforeIssuingAnotherCapability() {
    var q = query();
    var f = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  try {
                    var slot = start(q);
                    size(q, slot, 3L);
                    var first =
                        assertThrows(IllegalStateException.class, () -> slot.next(true, true));
                    throw SessionQueryTransaction.current(q).poisonProof(first);
                  } catch (RuntimeException failure) {
                    throw failure;
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                }));
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
  }
}
