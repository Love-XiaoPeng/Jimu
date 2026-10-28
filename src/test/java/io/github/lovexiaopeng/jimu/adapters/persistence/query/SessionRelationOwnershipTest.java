package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionRelationOwnershipTest {
  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  @Test
  void dependencyInvalidationDropsAllAffectedChainsButPreservesOtherTasks() {
    var q = query();
    var w = new SessionQueryWork(q);
    for (var type : SessionQueryWork.Relation.values()) {
      w.observed(type, "t", "row-" + type, "caller", null, "QUEUE");
      w.observed(type, "t", "other-" + type, "other", null, "QUEUE");
    }
    w.dependencyMutation(Set.of(new SessionQueryWork.Dependency("t", "caller")));
    for (var type : SessionQueryWork.Relation.values()) {
      assertNull(w.related(type, "t", "row-" + type, null, null, null));
      assertEquals("other", w.related(type, "t", "other-" + type, null, null, null));
    }
    w.observed(SessionQueryWork.Relation.STEP, "t", "row-STEP", "caller", null, null);
    assertEquals(
        "caller", w.related(SessionQueryWork.Relation.STEP, "t", "row-STEP", null, null, null));
    w.dependencyMutation();
    assertNull(w.related(SessionQueryWork.Relation.STEP, "t", "row-STEP", null, null, null));
    w.complete();
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void duplicateConflictAndLookupReserveBeforeAllocationAndReleaseTemporaryOwners() {
    var q = query();
    var w = new SessionQueryWork(q);
    w.observed(SessionQueryWork.Relation.STEP, "t", "s", "caller", null, null);
    long live = q.scratchLive();
    w.observed(SessionQueryWork.Relation.STEP, "t", "s", "caller", null, null);
    assertEquals(live, q.scratchLive());
    assertThrows(
        IllegalStateException.class,
        () -> w.observed(SessionQueryWork.Relation.STEP, "t", "s", "source", null, null));
    assertEquals(live, q.scratchLive());
    assertThrows(IllegalStateException.class, w::finish);
    w.complete();
    for (int remaining : new int[] {255, 256, 257}) {
      var limited = query();
      var owner = new SessionQueryWork(limited);
      Object unrelated = new Object();
      limited.retainScratch(unrelated, 1048576 - limited.scratchLive() - remaining);
      long before = limited.scratchLive();
      if (remaining < 256)
        assertThrows(
            TaskFailure.class,
            () -> owner.related(SessionQueryWork.Relation.STEP, "t", "missing", null, null, null));
      else
        assertNull(owner.related(SessionQueryWork.Relation.STEP, "t", "missing", null, null, null));
      assertEquals(before, limited.scratchLive());
      owner.complete();
    }
  }
}
