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
  void namedDmlAndMpEntitiesRequireUnambiguousFullKeys() {
    String root = "io.github.lovexiaopeng.jimu.adapters.persistence.";
    assertEquals(
        SessionDependencyWrites.Kind.TASK,
        SessionDependencyWrites.kind(root + "task.mapper.TaskAdmissionMapper.bindSnapshots"));
    assertEquals(
        SessionDependencyWrites.Kind.TASK,
        SessionDependencyWrites.kind(root + "session.mapper.SessionScopeRevocationMapper.revoke"));
    assertEquals(
        SessionDependencyWrites.Kind.SCALAR,
        SessionDependencyWrites.kind(root + "session.mapper.SessionContextMapper.account"));
    assertEquals(
        SessionDependencyWrites.Kind.UNKNOWN, SessionDependencyWrites.kind("private.unknown"));
    assertEquals(
        Set.of(new SessionQueryWork.Dependency("tenant", "source")),
        SessionDependencyWrites.keys(
            Map.of("tenant", "tenant", "taskId", "source"),
            SessionDependencyWrites.Kind.TASK,
            null));
    assertNull(
        SessionDependencyWrites.keys(
            Map.of("taskId", "source"), SessionDependencyWrites.Kind.TASK, null));
    assertNull(
        SessionDependencyWrites.keys(
            Map.of("tenant", "tenant", "caller", "new", "taskId", "source"),
            SessionDependencyWrites.Kind.TASK,
            null));
    var entity =
        new io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity();
    entity.setTenantId("tenant");
    entity.setTaskId("source");
    assertEquals(
        Set.of(new SessionQueryWork.Dependency("tenant", "source")),
        SessionDependencyWrites.keys(
            Map.of("et", entity), SessionDependencyWrites.Kind.TASK, null));
    assertNull(
        SessionDependencyWrites.keys(
            Map.of("et", entity, "taskId", "new"), SessionDependencyWrites.Kind.TASK, null));
    assertNull(
        SessionDependencyWrites.keys(
            Map.of("et", entity, "ew", new Object()), SessionDependencyWrites.Kind.TASK, null));
    var installation =
        new io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity();
    installation.setTenantId("tenant");
    installation.setPluginId("plugin");
    assertEquals(
        Set.of(SessionQueryWork.Dependency.installation("tenant", "plugin")),
        SessionDependencyWrites.keys(
            Map.of("et", installation), SessionDependencyWrites.Kind.INSTALLATION, null));
  }

  private SessionQueryWork published() {
    var owner = owner();
    owner.leaseTask("tenant", "new");
    owner.bind("lease", "step", "dispatch");
    owner.locked("tenant", "new");
    var fact = fact("new", null);
    owner.scope(
        caller,
        new SessionScopeRecords.Validated(fact.scope(), fact.authority(), List.of()),
        () -> {});
    return owner;
  }

  @Test
  void attachedCallerCannotReplaceRealStepDispatchInvocationOrAttemptKey() {
    String root = "io.github.lovexiaopeng.jimu.adapters.persistence.";
    for (var entry :
        Map.of(
                "execution.mapper.StepMapper.storeModelResponse", "stepId",
                "model.mapper.ModelDispatchMapper.recordActualUsage", "dispatchId",
                "session.mapper.SessionScopeQueryMapper.finishInvocation", "invocation",
                "task.mapper.TaskOperationsMapper.stopAttempt", "attemptId")
            .entrySet()) {
      var owner = published();
      var args = new java.util.HashMap<String, Object>();
      args.put("tenant", "tenant");
      args.put("taskId", "new");
      args.put(entry.getValue(), "wrong");
      assertThrows(
          IllegalStateException.class,
          () -> SessionDependencyWrites.before(root + entry.getKey(), args, owner));
      assertThrows(IllegalStateException.class, owner::finish);
      assertThrows(IllegalStateException.class, owner::beforeCommit);
    }
  }

  @Test
  void observedRowsAllowOnlyTheirOwnCallerStepAndReservationCategory() {
    String root = "io.github.lovexiaopeng.jimu.adapters.persistence.";
    var owner = published();
    SessionDependencyWrites.observed(
        root + "execution.mapper.StepMapper.selectModelStep",
        null,
        List.of(Map.of("tenant_id", "tenant", "id", "step", "task_id", "new")),
        owner);
    var invocation =
        Map.of("tenantId", "tenant", "taskId", "new", "stepId", "step", "invocationId", "call");
    SessionDependencyWrites.before(
        root + "invocation.mapper.InvocationMapper.insert", invocation, owner);
    SessionDependencyWrites.observed(
        root + "invocation.mapper.InvocationMapper.insert", invocation, 1, owner);
    SessionDependencyWrites.before(
        root + "session.mapper.SessionLineageTraceMapper.insert", invocation, owner);
    SessionDependencyWrites.before(
        root + "session.mapper.SessionLineageTraceMapper.commit",
        Map.of("tenant", "tenant", "id", "call"),
        owner);
    SessionDependencyWrites.observed(
        root + "budget.mapper.ReservationMapper.insertReservation",
        Map.of("tenant", "tenant", "taskId", "new", "reservationId", "call", "category", "TOOL"),
        1,
        owner);
    SessionDependencyWrites.before(
        root + "session.mapper.SessionScopeQueryMapper.settle",
        Map.of("tenant", "tenant", "invocation", "call"),
        owner);
    owner.finish();
    owner.beforeCommit();
    owner.complete();

    var mismatch = published();
    SessionDependencyWrites.observed(
        root + "invocation.mapper.InvocationMapper.selectTaskInvocation",
        null,
        List.of(
            Map.of(
                "tenant_id", "tenant", "id", "call", "task_id", "new", "step_id", "another-step")),
        mismatch);
    assertThrows(
        IllegalStateException.class,
        () ->
            SessionDependencyWrites.before(
                root + "session.mapper.SessionLineageTraceMapper.insert", invocation, mismatch));
    assertThrows(IllegalStateException.class, mismatch::finish);
  }
}
