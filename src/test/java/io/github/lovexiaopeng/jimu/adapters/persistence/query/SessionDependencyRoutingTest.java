package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 普通DML只做一次完整参数抽取；冲突/缺键/wrapper仍保守失效，预算先拒并回滚。 */
class SessionDependencyRoutingTest {
  private static final String ROOT = "io.github.lovexiaopeng.jimu.adapters.persistence.";
  private static final String WRITE = ROOT + "task.mapper.TaskAdmissionMapper.bindSnapshots";

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static final class CountingMap extends HashMap<String, Object> {
    int tenantReads;

    CountingMap() {
      put("tenant", "tenant");
      put("taskId", "task");
    }

    @Override
    public Object get(Object key) {
      if ("tenant".equals(key)) tenantReads++;
      return super.get(key);
    }
  }

  @Test
  void ordinaryDmlReadsTenantOnceAndKeepsExactDependency() {
    for (String statement :
        List.of(WRITE, ROOT + "session.mapper.SessionEventAppendMapper.advance")) {
      var q = query();
      var w = new SessionQueryWork(q);
      w.admission();
      var seen = new ArrayList<Set<SessionQueryWork.Dependency>>();
      var args = new CountingMap();
      w.invalidator(this, seen::add);
      long start = q.resources().operations();
      try {
        SessionDependencyWrites.before(statement, args, w);
        assertEquals(1, args.tenantReads);
        assertEquals(List.of(Set.of(new SessionQueryWork.Dependency("tenant", "task"))), seen);
        System.out.println(
            "DML_ROUTE_COST " + statement + " operations=" + (q.resources().operations() - start));
      } finally {
        w.complete();
      }
      assertEquals(65536, q.scratchLive());
    }
    var q = query();
    var w = new SessionQueryWork(q);
    w.admission();
    var seen = new ArrayList<Set<SessionQueryWork.Dependency>>();
    w.invalidator(this, seen::add);
    try {
      SessionDependencyWrites.before(
          ROOT + "plugin.mapper.InstallationMapper.update",
          Map.of("tenant", "tenant", "plugin", "plugin", "caller", "one", "taskId", "two"),
          w);
      assertEquals(1, seen.size());
      assertEquals(null, seen.getFirst());
    } finally {
      w.complete();
    }
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void missingConflictingEntityAliasesAndWrappersStillInvalidateConservatively() {
    var entity =
        new io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity();
    entity.setTenantId("tenant");
    entity.setTaskId("entity-task");
    for (Map<String, Object> args :
        List.<Map<String, Object>>of(
            Map.of("taskId", "task"),
            Map.of("tenant", "tenant", "taskId", "task", "caller", "other"),
            Map.of("tenant", "tenant", "taskId", "task", "et", entity),
            Map.of("tenant", "tenant", "taskId", "task", "ew", new Object()))) {
      var q = query();
      var w = new SessionQueryWork(q);
      w.admission();
      var seen = new ArrayList<Set<SessionQueryWork.Dependency>>();
      w.invalidator(this, seen::add);
      try {
        SessionDependencyWrites.before(WRITE, args, w);
        assertEquals(1, seen.size());
        assertEquals(null, seen.getFirst());
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
      SessionDependencyWrites.before(WRITE, new CountingMap(), w);
      return q.resources().operations() - start;
    } finally {
      w.complete();
    }
  }

  @Test
  void exactOperationThresholdKeepsFirstFailureAndRollsBackRealWork() {
    long needed = needed();
    for (long free : new long[] {needed - 1, needed, needed + 1}) {
      var f = new SessionQueryTransactionTest.Fixture();
      var q = query();
      TaskFailure[] first = {null};
      java.util.function.Supplier<Object> action =
          () -> {
            var w = SessionQueryTransaction.current(q);
            w.admission();
            q.operation(65536 - q.resources().operations() - free);
            if (free < needed) {
              first[0] =
                  assertThrows(
                      TaskFailure.class,
                      () -> SessionDependencyWrites.before(WRITE, new CountingMap(), w));
              assertSame(first[0], q.recordedFailure());
              assertSame(first[0], assertThrows(TaskFailure.class, () -> q.operation(1)));
            } else {
              long before = q.resources().operations();
              SessionDependencyWrites.before(WRITE, new CountingMap(), w);
              assertEquals(needed, q.resources().operations() - before);
            }
            return null;
          };
      if (free < needed) {
        var thrown = assertThrows(TaskFailure.class, () -> f.transaction.required(q, action));
        assertSame(first[0], thrown);
        assertEquals(0, f.commits);
        assertEquals(1, f.rollbacks);
      } else {
        // 终算另有费用；只核本边界，随后显式标记回滚，不借用额外预算。
        assertThrows(
            IllegalStateException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      action.get();
                      throw new IllegalStateException("boundary-marker");
                    }));
        assertEquals(0, f.commits);
        assertEquals(1, f.rollbacks);
      }
      assertEquals(65536, q.scratchLive());
    }
  }
}
