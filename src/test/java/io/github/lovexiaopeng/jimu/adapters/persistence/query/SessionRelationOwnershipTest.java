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

  @Test
  void sharedStringIdentityHasOneOwnerAndCapacitySurvivesInvalidation() {
    var q = query();
    var w = new SessionQueryWork(q);
    String shared = new String("shared");
    w.observed(SessionQueryWork.Relation.STEP, shared, shared, shared, shared, shared);
    long first = q.scratchLive();
    w.observed(SessionQueryWork.Relation.INVOCATION, shared, shared, shared, shared, shared);
    assertEquals(SessionQueryWork.RELATION_ENTRY_BYTES + 32, q.scratchLive() - first);
    String distinct = new String(shared);
    long before = q.scratchLive();
    w.observed(
        SessionQueryWork.Relation.DISPATCH, distinct, distinct, distinct, distinct, distinct);
    assertEquals(
        SessionQueryWork.RELATION_ENTRY_BYTES
            + 32
            + 64
            + SessionQueryWork.RELATION_STRING_BYTES
            + 2L * distinct.length(),
        q.scratchLive() - before);
    w.dependencyMutation();
    assertNull(w.related(SessionQueryWork.Relation.STEP, shared, shared, null, null, null));
    // HashMap and IdentityHashMap retain their arrays even after their entries are gone.
    assertEquals(65536 + SessionQueryWork.RELATION_ROOT_BYTES + 3 * 32 + 2 * 64, q.scratchLive());
    w.complete();
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void exhaustedOperationBudgetDoesNotPartlyPublishNewRelationship() {
    var q = query();
    var w = new SessionQueryWork(q);
    w.observed(SessionQueryWork.Relation.STEP, "t", "s", "caller", null, null);
    long before = q.scratchLive();
    q.operation(65536 - q.resources().operations() - 30);
    assertThrows(
        TaskFailure.class,
        () -> w.observed(SessionQueryWork.Relation.INVOCATION, "t", "i", "caller", "s", null));
    assertEquals(before, q.scratchLive());
    w.complete();
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void aliasesAndEachWrongExpectationPayForActualComparisons() {
    var q = query();
    var w = new SessionQueryWork(q);
    w.observed(SessionQueryWork.Relation.INVOCATION, "tenant", "id", "caller", "step", "TOOL");
    long before = q.resources().operations();
    assertEquals(
        "caller",
        w.related(SessionQueryWork.Relation.INVOCATION, "tenant", "id", null, null, null));
    long lookup = q.resources().operations() - before;
    before = q.resources().operations();
    assertEquals(
        "caller",
        w.related(
            SessionQueryWork.Relation.INVOCATION,
            "tenant",
            "id",
            new String("caller"),
            new String("step"),
            new String("TOOL")));
    assertEquals(lookup + 6 + 4 + 4, q.resources().operations() - before);
    assertNull(
        w.related(SessionQueryWork.Relation.INVOCATION, "tenant", "id", "wrong", null, null));
    assertNull(
        w.related(SessionQueryWork.Relation.INVOCATION, "tenant", "id", null, "wrong", null));
    assertNull(
        w.related(SessionQueryWork.Relation.INVOCATION, "tenant", "id", null, null, "wrong"));
    w.bind("lease", "step", "response");
    assertEquals(
        "caller",
        w.related(
            SessionQueryWork.Relation.INVOCATION,
            "tenant",
            "id",
            new String("caller"),
            new String("step"),
            null));
    assertNull(w.related(SessionQueryWork.Relation.STEP, "tenant", "id", null, null, null));
    w.complete();
  }

  @Test
  void invalidationOperationBoundaryIsAtomicAcrossBothMaps() throws Exception {
    var measured = query();
    var first = new SessionQueryWork(measured);
    first.observed(SessionQueryWork.Relation.INVOCATION, "tenant", "id", "caller", "step", "TOOL");
    long start = measured.resources().operations();
    first.dependencyMutation(
        Set.of(new SessionQueryWork.Dependency(new String("tenant"), new String("caller"))));
    long cost = measured.resources().operations() - start;
    first.complete();
    for (long remaining : new long[] {cost - 1, cost, cost + 1}) {
      var q = query();
      var w = new SessionQueryWork(q);
      w.observed(SessionQueryWork.Relation.INVOCATION, "tenant", "id", "caller", "step", "TOOL");
      long live = q.scratchLive();
      q.operation(65536 - q.resources().operations() - remaining);
      var writes =
          Set.of(new SessionQueryWork.Dependency(new String("tenant"), new String("caller")));
      if (remaining < cost) {
        assertThrows(TaskFailure.class, () -> w.dependencyMutation(writes));
        assertEquals(live, q.scratchLive());
        assertThrows(TaskFailure.class, w::finish);
      } else w.dependencyMutation(writes);
      var entries = SessionQueryWork.class.getDeclaredField("relations");
      var strings = SessionQueryWork.class.getDeclaredField("relationStrings");
      entries.setAccessible(true);
      strings.setAccessible(true);
      assertEquals(remaining < cost ? 1 : 0, ((java.util.Map<?, ?>) entries.get(w)).size());
      assertEquals(remaining < cost ? 5 : 0, ((java.util.Map<?, ?>) strings.get(w)).size());
      w.complete();
      assertEquals(65536, q.scratchLive());
    }
  }
}
