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
import org.junit.jupiter.api.Test;

/** 候选专用：来源上界的新判断须先付费，原Fields构造后尚不能读取tenant等键。 */
class SourceBoundPrepayTest {
  @Test
  void prepaidNewRepresentationRefusesBeforeSourceMaterialization() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    var w = new SessionQueryWork(q);
    w.admission();
    var calls = new ArrayList<String>();
    var p =
        new HashMap<String, Object>() {
          @Override
          public boolean containsKey(Object key) {
            calls.add("has:" + key);
            return super.containsKey(key);
          }

          @Override
          public Object get(Object key) {
            calls.add("get:" + key);
            return super.get(key);
          }
        };
    p.put("tenant", "tenant");
    p.put("taskId", "task");
    q.operation(65536 - q.resources().operations() - 7);
    try {
      var first =
          assertThrows(
              TaskFailure.class,
              () -> SessionDependencyWrites.keys(p, SessionDependencyWrites.Kind.TASK, w));
      assertSame(first, q.recordedFailure());
      assertEquals(List.of("has:ew", "has:et", "has:param1"), calls);
    } finally {
      w.complete();
    }
    assertEquals(65536, q.scratchLive());
  }
}
