package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.junit.jupiter.api.Test;

class SourceBoundProbeTest {
  private static final String WRITE =
      "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper.bindSnapshots";

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private SessionLineageEntity entity(String task) {
    var e = new SessionLineageEntity();
    e.setTenantId("tenant");
    e.setTaskId(task);
    return e;
  }

  private Set<SessionQueryWork.Dependency> expected(String task) {
    return Set.of(new SessionQueryWork.Dependency("tenant", task));
  }

  private Set<SessionQueryWork.Dependency> keys(Object p) {
    return SessionDependencyWrites.keys(p, SessionDependencyWrites.Kind.TASK, null);
  }

  @Test
  void everyActualEntitySourceAndInteriorHoleStillParticipates() {
    var a = entity("task");
    var b = entity("task");
    for (Object p :
        List.of(
            Map.of("tenant", "tenant", "taskId", "task"),
            a,
            Map.of("et", a),
            Map.of("param1", a),
            Map.of("et", a, "param1", a),
            Map.of("et", a, "param1", b))) assertEquals(expected("task"), keys(p));
    b.setTaskId("conflict");
    assertNull(keys(Map.of("et", a, "param1", b)));
    assertNull(keys(Map.of("tenant", "tenant", "taskId", "task", "param1", b)));
    assertNull(keys(Map.of("et", a, "caller", "conflict")));
  }

  @Test
  void actualParamMapOmissionsBadTypesAndEveryExplicitAliasRemainConservative() {
    var p = new ParamMap<Object>();
    p.put("tenant", "tenant");
    p.put("taskId", "task");
    assertEquals(expected("task"), keys(p));
    for (String alias :
        List.of("caller", "taskId", "task", "id", "callerTaskId", "caller_task_id", "task_id")) {
      var m = new HashMap<String, Object>();
      m.put("tenant", "tenant");
      m.put(alias, "task");
      assertEquals(expected("task"), keys(m));
      m.put(alias, "");
      assertNull(keys(m));
      m.put(alias, 17);
      assertNull(keys(m));
    }
    p.put("ew", new Object());
    assertNull(keys(p));
    assertNull(keys(Map.of("taskId", "task")));
    assertNull(keys(Map.of("tenant", "tenant")));
  }

  @Test
  void mapAndEntityValuesAreNewOnEachCallAndWithinOneExtraction() {
    var e = entity("task");
    var p = new HashMap<String, Object>();
    p.put("et", e);
    assertEquals(expected("task"), keys(p));
    e.setTaskId("new");
    assertEquals(expected("new"), keys(p));
    p.put("taskId", "task");
    assertNull(keys(p));
    var dynamic =
        new HashMap<String, Object>() {
          @Override
          public Object get(Object key) {
            if ("tenant".equals(key)) e.setTaskId("changed-during-tenant-read");
            return super.get(key);
          }
        };
    dynamic.put("tenant", "tenant");
    dynamic.put("taskId", "task");
    dynamic.put("et", e);
    e.setTaskId("task");
    assertNull(keys(dynamic));
  }

  private static class Traced extends HashMap<String, Object> {
    final List<String> trace = new ArrayList<>();

    @Override
    public boolean containsKey(Object key) {
      trace.add("has:" + key);
      return super.containsKey(key);
    }

    @Override
    public Object get(Object key) {
      trace.add("get:" + key);
      return super.get(key);
    }
  }

  @Test
  void observableMapCallsAndPerShapeOperationCostsAreRecorded() {
    var a = entity("task");
    var b = entity("task");
    for (int shape = 0; shape < 5; shape++) {
      var p = new Traced();
      if (shape == 0) {
        p.put("tenant", "tenant");
        p.put("taskId", "task");
      }
      if (shape == 1) p.put("et", a);
      if (shape == 2) p.put("param1", a);
      if (shape == 3) {
        p.put("et", a);
        p.put("param1", a);
      }
      if (shape == 4) {
        p.put("et", a);
        p.put("param1", b);
      }
      var q = query();
      var w = new SessionQueryWork(q);
      w.admission();
      var seen = new ArrayList<Set<SessionQueryWork.Dependency>>();
      w.invalidator(this, seen::add);
      long start = q.resources().operations();
      try {
        SessionDependencyWrites.before(WRITE, p, w);
        assertEquals(List.of(expected("task")), seen);
        System.out.println(
            "SOURCE_BOUND_SHAPE shape="
                + shape
                + " operations="
                + (q.resources().operations() - start)
                + " trace="
                + p.trace);
      } finally {
        w.complete();
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  private long needed() {
    var q = query();
    var w = new SessionQueryWork(q);
    w.admission();
    long start = q.resources().operations();
    try {
      SessionDependencyWrites.before(WRITE, Map.of("tenant", "tenant", "taskId", "task"), w);
      return q.resources().operations() - start;
    } finally {
      w.complete();
    }
  }

  @Test
  void exactThresholdKeepsFirstSlotAndRollsBackOnce() {
    long needed = needed();
    for (long free : new long[] {needed - 1, needed, needed + 1}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      TaskFailure[] first = {null};
      var marker = new IllegalStateException("only-this-boundary");
      var thrown =
          assertThrows(
              RuntimeException.class,
              () ->
                  f.transaction.required(
                      q,
                      () -> {
                        var w = SessionQueryTransaction.current(q);
                        w.admission();
                        q.operation(65536 - q.resources().operations() - free);
                        long start = q.resources().operations();
                        if (free < needed) {
                          first[0] =
                              assertThrows(
                                  TaskFailure.class,
                                  () ->
                                      SessionDependencyWrites.before(
                                          WRITE, Map.of("tenant", "tenant", "taskId", "task"), w));
                          assertSame(first[0], q.recordedFailure());
                          assertSame(
                              first[0], assertThrows(TaskFailure.class, () -> q.operation(1)));
                          return null;
                        }
                        SessionDependencyWrites.before(
                            WRITE, Map.of("tenant", "tenant", "taskId", "task"), w);
                        assertEquals(needed, q.resources().operations() - start);
                        throw marker;
                      }));
      assertSame(free < needed ? first[0] : marker, thrown);
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void extractionOwnerRefusesBeforeAnyParameterCallbackAndRefundsExactly() {
    var q = query();
    var w = new SessionQueryWork(q);
    w.admission();
    var extra = new Object();
    long bytes = 1048576L - q.scratchLive() - 4096 + 1;
    q.retainScratch(extra, bytes);
    var p = new Traced();
    p.put("tenant", "tenant");
    p.put("taskId", "task");
    try {
      var first =
          assertThrows(TaskFailure.class, () -> SessionDependencyWrites.before(WRITE, p, w));
      assertSame(first, q.recordedFailure());
      assertTrue(p.trace.isEmpty());
    } finally {
      w.complete();
      q.releaseScratch(extra);
    }
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void parameterExceptionAndSqlCleanupRetainPrimaryCauseAndSuppressed() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var cause = new SQLException("parameter cause", "22001", 71);
    var primary = new IllegalArgumentException("parameter failure", cause);
    var cleanup = new SQLException("reset", "08003", 72);
    f.reset = cleanup;
    var p =
        new HashMap<String, Object>() {
          @Override
          public Object get(Object key) {
            if ("taskId".equals(key)) throw primary;
            return super.get(key);
          }
        };
    p.put("tenant", "tenant");
    p.put("taskId", "task");
    assertSame(
        primary,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      f.active.set(true);
                      var w = SessionQueryTransaction.current(q);
                      w.admission();
                      SessionDependencyWrites.before(WRITE, p, w);
                      return null;
                    })));
    assertSame(cause, primary.getCause());
    assertEquals("22001", cause.getSQLState());
    assertTrue(List.of(primary.getSuppressed()).contains(cleanup));
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void parameterErrorIsNotReplacedAndAllOwnersAreReleased() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var primary = new AssertionError("parameter error");
    var p =
        new HashMap<String, Object>() {
          @Override
          public Object get(Object key) {
            if ("tenant".equals(key)) throw primary;
            return super.get(key);
          }
        };
    p.put("tenant", "tenant");
    p.put("taskId", "task");
    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var w = SessionQueryTransaction.current(q);
                      w.admission();
                      SessionDependencyWrites.before(WRITE, p, w);
                      return null;
                    })));
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertEquals(65536, q.scratchLive());
  }
}
