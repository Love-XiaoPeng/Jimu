package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork.ProjectionRole;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 三种投影的实际Map同时驻留、同caller与失败原子性；不是SQL/业务验收。 */
class SessionProjectionOwnershipTest {
  @Test
  void onlyUnpublishedUnavailableStatusCanBeReturnedBeforeProtectedWrites() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var query = query();
    fixture.transaction.required(
        query,
        () -> {
          var owner = SessionQueryTransaction.current(query);
          var unavailable =
              new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
                  404, "SESSION_SCOPE_UNAVAILABLE");
          assertTrue(owner.scopeStatus(unavailable));
          org.junit.jupiter.api.Assertions.assertFalse(
              owner.scopeStatus(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(404, "OTHER")));
          org.junit.jupiter.api.Assertions.assertFalse(
              owner.scopeStatus(
                  new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(403, "OTHER")));
          owner.protectedWrite();
          org.junit.jupiter.api.Assertions.assertFalse(owner.scopeStatus(unavailable));
          return null;
        });
  }

  @Test
  void allThreeMapsRemainOwnedUntilCompletionThenLoseTheirTextReferences() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var query = query();
    var rows = new ArrayList<Map<String, Object>>();
    fixture.transaction.required(
        query,
        () -> {
          var owner = SessionQueryTransaction.current(query);
          owner.locked("t", "c");
          for (var role : ProjectionRole.values()) {
            var projection = owner.reserveProjection(role, "t", "c", 8192);
            var row =
                new HashMap<String, Object>(
                    Map.of("tenant_id", "t", "id", "c", "summary", "borrowed"));
            assertSame(row, projection.loaded(row));
            rows.add(row);
          }
          assertEquals(
              65536
                  + SessionQueryWork.RELATION_ROOT_BYTES
                  + SessionQueryTransaction.METADATA_BINDING_BYTES
                  + 1024
                  + 3 * 8192,
              query.scratchLive());
          rows.forEach(row -> assertEquals("borrowed", row.get("summary")));
          return null;
        });
    rows.forEach(row -> assertTrue(row.isEmpty()));
    assertEquals(65536, query.scratchLive());
    assertEquals(1, fixture.commits);
  }

  @Test
  void duplicateRoleDifferentCallerAndDifferentLoadedRowLatchBeforeAnyCommit() {
    for (String failure : java.util.List.of("duplicate", "caller", "row", "unlocked")) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var query = query();
      RuntimeException[] first = {null};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.transaction.required(
                      query,
                      () -> {
                        var owner = SessionQueryTransaction.current(query);
                        owner.locked("t", "c");
                        owner.locked("t", "other");
                        var projection =
                            owner.reserveProjection(ProjectionRole.LINEAGE_SCOPE, "t", "c", 8192);
                        first[0] =
                            assertThrows(
                                RuntimeException.class,
                                () -> {
                                  switch (failure) {
                                    case "duplicate" ->
                                        owner.reserveProjection(
                                            ProjectionRole.LINEAGE_SCOPE, "t", "c", 8192);
                                    case "caller" ->
                                        owner.reserveProjection(
                                            ProjectionRole.ACCEPTED_EVENT, "t", "other", 8192);
                                    case "row" ->
                                        projection.loaded(
                                            new HashMap<>(Map.of("tenant_id", "wrong", "id", "c")));
                                    default ->
                                        owner.reserveProjection(
                                            ProjectionRole.ACCEPTED_EVENT, "t", "unlocked", 8192);
                                  }
                                });
                        long operations = query.resources().operations();
                        assertSame(
                            first[0],
                            assertThrows(RuntimeException.class, () -> query.operation(1)));
                        assertEquals(operations, query.resources().operations());
                        return null;
                      }));
      assertSame(first[0], observed);
      assertEquals(1, fixture.rollbacks);
      assertEquals(65536, query.scratchLive());
    }
  }

  @Test
  void capacityFailureNeverPublishesTheFailedSlotAndRefundsAllPriorSlots() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var query = query();
    var first = new HashMap<String, Object>(Map.of("tenant_id", "t", "id", "c"));
    assertThrows(
        RuntimeException.class,
        () ->
            fixture.transaction.required(
                query,
                () -> {
                  var owner = SessionQueryTransaction.current(query);
                  owner.locked("t", "c");
                  owner
                      .reserveProjection(ProjectionRole.LINEAGE_SCOPE, "t", "c", 8192)
                      .loaded(first);
                  assertThrows(
                      RuntimeException.class,
                      () ->
                          owner.reserveProjection(
                              ProjectionRole.ACCEPTED_EVENT, "t", "c", 1048576));
                  return null;
                }));
    assertTrue(first.isEmpty());
    assertEquals(65536, query.scratchLive());
    assertEquals(1, fixture.rollbacks);
  }

  private static SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }
}
