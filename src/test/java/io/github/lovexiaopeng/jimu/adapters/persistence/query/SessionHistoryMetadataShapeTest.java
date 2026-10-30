package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** New focused checks only; original test classes are compiled solely for their fixtures. */
class SessionHistoryMetadataShapeTest {
  static final boolean CANDIDATE = true;
  static final List<String> REQUIRED =
      List.of(
          "tenant_id",
          "task_id",
          "record_version",
          "history_version",
          "binding_snapshot",
          "binding_digest",
          "content_state",
          "next_seq",
          "body_bytes",
          "metadata_bytes",
          "reserved_bytes",
          "held_bytes",
          "retain_until");

  static Object call(Object receiver, String name, Object... args) {
    try {
      Method method =
          Arrays.stream(receiver.getClass().getDeclaredMethods())
              .filter(m -> m.getName().equals(name))
              .findFirst()
              .orElseThrow();
      method.setAccessible(true);
      return method.invoke(receiver, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException r) throw r;
      if (e.getCause() instanceof Error r) throw r;
      throw new AssertionError(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  static Object field(Object receiver, String name) {
    try {
      Field f = receiver.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(receiver);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  static class RowMap extends LinkedHashMap<String, Object> {
    final List<String> reads = new ArrayList<>();
    int keyViews, presenceReads, clears;
    RuntimeException runtime;
    Error fatal;
    Runnable lastRead;

    RowMap(Map<String, Object> row) {
      super(row);
    }

    @Override
    public Object get(Object key) {
      reads.add((String) key);
      if ("cleanup_receipt".equals(key) && lastRead != null) {
        var action = lastRead;
        lastRead = null;
        action.run();
      }
      return super.get(key);
    }

    @Override
    public Set<String> keySet() {
      keyViews++;
      return super.keySet();
    }

    @Override
    public boolean containsKey(Object key) {
      presenceReads++;
      if (runtime != null) throw runtime;
      if (fatal != null) throw fatal;
      return super.containsKey(key);
    }

    @Override
    public void clear() {
      clears++;
      super.clear();
    }
  }

  static class Fixture {
    final Object helpers = new SessionHistorySizedMetadataBatchTest();
    final Object original;
    final SessionQueryContext query;
    final org.apache.ibatis.session.Configuration config;
    RowMap observed;
    RuntimeException injected;
    Error fatal;
    Runnable lastRead;

    Fixture() throws Exception {
      Class<?> type =
          Class.forName(SessionHistorySizedMetadataBatchTest.class.getName() + "$Fixture");
      Constructor<?> ctor = type.getDeclaredConstructor();
      ctor.setAccessible(true);
      original = ctor.newInstance();
      query = (SessionQueryContext) call(helpers, "q");
      config =
          (org.apache.ibatis.session.Configuration)
              call(helpers, "config", original, call(helpers, "xml"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> row() {
      return (Map<String, Object>) call(helpers, "row", 27L);
    }

    SqlSession session(Map<String, Object> row) {
      SqlSession delegate =
          (SqlSession) call(helpers, "session", original, query, config, List.of(row));
      return (SqlSession)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {SqlSession.class},
              (p, m, a) -> {
                Object result;
                try {
                  result = m.invoke(delegate, a);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
                if (m.getName().equals("selectList")) {
                  @SuppressWarnings("unchecked")
                  var rows = (List<Map<String, Object>>) result;
                  observed = new RowMap(rows.getFirst());
                  observed.runtime = injected;
                  observed.fatal = fatal;
                  observed.lastRead = lastRead;
                  return new ArrayList<>(List.of(observed));
                }
                return result;
              });
    }

    <T> T owned(Supplier<T> action) {
      @SuppressWarnings("unchecked")
      T result = (T) call(helpers, "owned", original, query, action);
      return result;
    }

    SessionHistorySizedMetadataBatch load(Map<String, Object> row) {
      return SessionHistorySizedMetadataBatch.load(session(row), query, "tenant", "task");
    }

    void rolledBack() {
      var base = (SessionQueryTransactionTest.Fixture) field(original, "base");
      assertEquals(0, base.commits);
      assertEquals(1, base.rollbacks);
      assertEquals(65536, query.scratchLive());
      // The test intentionally retains its own Map alias. The production batch drops its
      // owned rows reference; it does not clear maps borrowed by a test observer.
    }
  }

  @Test
  void acceptsAbsentNullAndPresentCleanupWithoutReiteratingKeys() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var f = new Fixture();
      var row = f.row();
      if (mode == 0) row.remove("cleanup_receipt");
      if (mode == 2) row.put("cleanup_receipt", "receipt");
      int expected = mode == 0 ? 13 : 14;
      f.owned(
          () -> {
            try (var b = f.load(row)) {
              var r = b.iterator().next();
              assertEquals(27, b.scalarBytes(r.value("binding_snapshot")));
              assertEquals(2, b.scalarBytes(27L));
              int count = 0;
              for (var ignored : r.values()) count++;
              assertEquals(expected, count);
              assertEquals(CANDIDATE ? 0 : 1, f.observed.keyViews);
              assertEquals(REQUIRED, f.observed.reads.subList(2, 15));
            }
            return null;
          });
      assertEquals(65536, f.query.scratchLive());
    }
  }

  @Test
  void everyMissingNullAndUnknownColumnStillPoisonsAndCleans() throws Exception {
    int cases = 0;
    for (String column : REQUIRED)
      for (boolean explicitNull : new boolean[] {false, true}) {
        var f = new Fixture();
        var row = f.row();
        if (explicitNull) row.put(column, null);
        else row.remove(column);
        var first =
            assertThrows(
                RuntimeException.class,
                () ->
                    f.owned(
                        () -> {
                          var error = assertThrows(RuntimeException.class, () -> f.load(row));
                          assertSame(error, f.query.recordedFailure());
                          return null;
                        }));
        assertSame(first, f.query.recordedFailure());
        f.rolledBack();
        cases++;
      }
    for (int mode = 0; mode < 3; mode++) {
      var f = new Fixture();
      var row = f.row();
      if (mode == 1) row.remove("cleanup_receipt");
      if (mode == 2) row.remove("record_version");
      row.put("foreign_column", null);
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.owned(
                      () -> {
                        assertThrows(RuntimeException.class, () -> f.load(row));
                        return null;
                      }));
      assertSame(first, f.query.recordedFailure());
      assertInstanceOf(TaskFailure.class, first);
      assertEquals(409, ((TaskFailure) first).status());
      f.rolledBack();
      cases++;
    }
    System.out.println("SHAPE_INVALID_CASES " + cases);
  }

  @Test
  void fullLoadHasExactThresholdAndNoPublishedReceiverOnRefusal() throws Exception {
    var measured = new Fixture();
    long[] needed = {0};
    measured.owned(
        () -> {
          long start = measured.query.resources().operations();
          try (var b = measured.load(measured.row())) {
            needed[0] = measured.query.resources().operations() - start;
          }
          return null;
        });
    System.out.println("SHAPE_LOAD_COST candidate=" + CANDIDATE + " operations=" + needed[0]);
    for (long free : new long[] {needed[0] - 1, needed[0], needed[0] + 1}) {
      var f = new Fixture();
      boolean[] published = {false};
      var force = new IllegalStateException("test rollback");
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.owned(
                      () -> {
                        f.query.operation(65536 - f.query.resources().operations() - free);
                        try (var b = f.load(f.row())) {
                          published[0] = true;
                        }
                        throw force;
                      }));
      assertEquals(free >= needed[0], published[0]);
      if (free < needed[0]) {
        assertInstanceOf(TaskFailure.class, first);
        assertSame(first, f.query.recordedFailure());
      } else assertSame(force, first);
      f.rolledBack();
    }
  }

  @Test
  void allDynamicValuesRemainFreshAfterConstruction() throws Exception {
    var f = new Fixture();
    f.owned(
        () -> {
          try (var b = f.load(f.row())) {
            var row = b.iterator().next();
            for (String name :
                List.of(
                    "next_seq", "body_bytes", "metadata_bytes", "reserved_bytes", "held_bytes")) {
              f.observed.put(name, 12345L);
              assertEquals(12345L, row.value(name));
              assertEquals(5, b.scalarBytes(row.value(name)));
            }
            f.observed.put("content_state", "PURGED");
            assertEquals("PURGED", row.value("content_state"));
          }
          return null;
        });
    assertEquals(65536, f.query.scratchLive());
  }

  @Test
  void shapeLookupFailureAndErrorPreserveFirstAndMustRunCleanup() throws Exception {
    if (!CANDIDATE) return; // baseline has no optional-presence lookup at construction
    for (boolean error : new boolean[] {false, true}) {
      var f = new Fixture();
      var runtime =
          new IllegalStateException("shape provider", new java.sql.SQLException("source", "HY784"));
      var fatal = new AssertionError("shape provider error");
      if (error) f.fatal = fatal;
      else f.injected = runtime;
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.owned(
                      () -> {
                        if (error)
                          assertSame(
                              fatal, assertThrows(AssertionError.class, () -> f.load(f.row())));
                        else
                          assertSame(
                              runtime, assertThrows(RuntimeException.class, () -> f.load(f.row())));
                        return null;
                      }));
      if (error) assertSame(fatal, first.getCause());
      else assertSame(runtime, first);
      assertSame(first, f.query.recordedFailure());
      f.rolledBack();
    }
  }

  @Test
  void replacementGuardIsPrepaidAt45WithNoPartialPublication() throws Exception {
    assertTrue(CANDIDATE);
    for (long free : new long[] {44, 45, 46}) {
      var f = new Fixture();
      boolean[] returned = {false};
      var force = new IllegalStateException("test rollback");
      f.lastRead = () -> f.query.operation(65536 - f.query.resources().operations() - free);
      var first =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.owned(
                      () -> {
                        try (var b = f.load(f.row())) {
                          returned[0] = true;
                        }
                        throw force;
                      }));
      assertEquals(free >= 45, returned[0]);
      assertEquals(free >= 45 ? 1 : 0, f.observed.presenceReads);
      if (free == 44) {
        assertInstanceOf(TaskFailure.class, first);
        assertSame(first, f.query.recordedFailure());
      } else assertSame(force, first);
      f.rolledBack();
    }
  }
}
