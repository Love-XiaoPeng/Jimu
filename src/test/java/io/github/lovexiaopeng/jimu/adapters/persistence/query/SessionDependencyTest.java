package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionDependencyTest {
  private final SessionScopeRecords.Caller caller = caller("new");

  private SessionScopeRecords.Caller caller(String id) {
    return new SessionScopeRecords.Caller(
        "tenant", id, "owner", "tools", "tools", 1, "SUCCEEDED", null, "digest");
  }

  private SessionScopeRecords.SourceFact fact(String id, String target) {
    var scope =
        new SessionScopeRecords.Scope(
            1, 1, "owner", "binding", "tools", "{}", "digest", 2, 1, "ACTIVE", null, null, null,
            null, null, "PRESENT", true);
    var authority =
        new SessionScopeRecords.Authority(
            "tenant",
            id,
            "owner",
            "tools",
            "binding",
            "",
            "",
            target == null
                ? List.of()
                : List.of(
                    new SessionScopeRecords.AuthorityTarget(
                        target, "pins", "binding", 1, "", "", "{}")));
    return new SessionScopeRecords.SourceFact(
        caller(id),
        new SessionScopeRecords.History(1, "binding", null),
        scope,
        authority,
        List.of());
  }

  private SessionQueryWork owner() {
    var context =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    context.restrictLineage();
    var owner = new SessionQueryWork(context);
    owner.admission();
    return owner;
  }

  @Test
  void newCallerInsertPreservesEightSourcesAndIntersectingOrUnknownWriteInvalidatesAllFacts() {
    for (boolean unknown : List.of(false, true)) {
      var owner = owner();
      for (int i = 0; i < 8; i++) {
        var fact = fact("source" + i, "target");
        owner.stageSource(caller, fact);
        owner.validatedAuthority(fact.caller(), fact.scope(), fact.authority());
      }
      owner.dependencyMutation(Set.of(new SessionQueryWork.Dependency("tenant", "new")));
      for (int i = 0; i < 8; i++) assertNotNull(owner.stagedSource(caller, "source" + i));
      owner.dependencyMutation(
          unknown ? null : Set.of(new SessionQueryWork.Dependency("tenant", "target")));
      for (int i = 0; i < 8; i++) {
        assertNull(owner.stagedSource(caller, "source" + i));
        assertNull(owner.validatedAuthority("tenant", "source" + i, "{}", "digest"));
      }
      owner.complete();
      assertThrows(IllegalStateException.class, () -> owner.stagedSource(caller, "source0"));
    }
  }

  @Test
  void createdWitnessIsBoundToOwnerResponseFenceAndDependencyGeneration() {
    var owner = owner();
    var row = new SessionLineage.Stored("{}", "g", 2, "{}", "h", 2, "PRESENT", 1);
    var graph = new VerifiedGraph("a", "b", "c", "scan", Map.of(), Map.of());
    owner.bind("lease", "step", "response");
    owner.checkedCreated("tenant", "new", row, graph);
    assertTrue(owner.checkedCreated("tenant", "new", row));
    assertFalse(owner().checkedCreated("tenant", "new", row));
    assertThrows(IllegalStateException.class, () -> owner.bind("new-fence", "step", "response"));
    owner.dependencyMutation(Set.of(new SessionQueryWork.Dependency("tenant", "target")));
    assertFalse(owner.checkedCreated("tenant", "new", row));
    owner.checkedCreated("tenant", "new", row, graph);
    owner.fence("changed", () -> {});
    assertFalse(owner.checkedCreated("tenant", "new", row));
    owner.complete();
    assertThrows(IllegalStateException.class, () -> owner.checkedCreated("tenant", "new", row));
  }

  @Test
  void dependencySqlClassifiesWrittenColumnsAndRequiresTheFullKey() {
    assertTrue(
        SessionQueryInterceptor.dependencyWrite(
            "update task set tools_digest=? where tenant_id=? and id=?"));
    assertTrue(
        SessionQueryInterceptor.dependencyWrite(
            "update task_session_scope set grant_state='REVOKED' where tenant_id=? and caller_task_id=?"));
    assertFalse(
        SessionQueryInterceptor.dependencyWrite(
            "update task set updated_at=current_timestamp where tools_digest=?"));
    assertFalse(
        SessionQueryInterceptor.dependencyWrite(
            "update task_session_history set metadata_bytes=? where tenant_id=? and task_id=?"));
    assertFalse(
        SessionQueryInterceptor.dependencyWrite(
            "insert into step_session_scope(tenant_id,caller_task_id,authority_snapshot) values(?,?,?)"));
    assertEquals(
        Set.of(new SessionQueryWork.Dependency("tenant", "source")),
        SessionQueryInterceptor.dependencyKeys(Map.of("tenant", "tenant", "taskId", "source")));
    assertNull(SessionQueryInterceptor.dependencyKeys(Map.of("taskId", "source")));
  }
}
