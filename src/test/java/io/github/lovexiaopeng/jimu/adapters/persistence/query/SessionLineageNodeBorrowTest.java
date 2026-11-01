package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 新成对输入及pending require借用；真实SQL由独立私库夹具验证。 */
class SessionLineageNodeBorrowTest {
  private SessionQueryTransactionTest.Fixture fixture() {
    var f = new SessionQueryTransactionTest.Fixture();
    f.context.restrictLineage();
    return f;
  }

  private SessionScopeRecords.Caller caller(String id) {
    return new SessionScopeRecords.Caller(
        "tenant",
        id,
        "owner",
        SessionLineagePolicyV5.CAPABILITIES,
        "tools",
        1,
        "SUCCEEDED",
        null,
        "digest");
  }

  private SessionScopeRecords.Scope scope() {
    return new SessionScopeRecords.Scope(
        1, 1, "owner", "b", "tools", "{}", "d", 2, 0, "ACTIVE", null, null, null, null, null,
        "PRESENT", true);
  }

  private SessionScopeRecords.Authority authority(String id) {
    return new SessionScopeRecords.Authority(
        "tenant", id, "owner", "tools", "b", "", "", List.of());
  }

  private SessionScopeRecords.Validated proof() {
    return new SessionScopeRecords.Validated(
        scope(), authority("caller"), List.of(caller("source")), Map.of("source", scope()));
  }

  private SessionQueryWork owner(SessionQueryTransactionTest.Fixture f) {
    var o = SessionQueryTransaction.current(f.context);
    o.locked("tenant", "caller");
    o.locked("tenant", "source");
    var s = scope();
    o.validatedAuthority(caller("source"), s, authority("source"));
    o.holdScope(proof());
    return o;
  }

  @Test
  void pendingScopeBorrowsCallerAndSourceWithoutPublishingAndCleansAliases() {
    var f = fixture();
    f.transaction.required(
        f.context,
        () -> {
          var o = owner(f);
          var p = proof();
          long held = f.context.scratchLive();
          o.withLineageScope(
              f.context,
              caller("caller"),
              p,
              () -> {
                assertNull(o.proof());
                o.beginLineageInputs();
                var c = o.borrowLineageNode(f.context, "tenant", "caller");
                assertSame(p.authority(), c.authority());
                o.releaseLineageSource();
                var s = o.borrowLineageNode(f.context, "tenant", "source");
                assertEquals("source", s.authority().caller());
                o.releaseLineageSource();
                o.endLineageInputs();
                return null;
              });
          assertEquals(held, f.context.scratchLive());
          assertNull(o.borrowLineageNode(f.context, "tenant", "caller"));
          return null;
        });
    assertEquals(65536, f.context.scratchLive());
  }

  @Test
  void pendingScopeRejectsWritePublicationNestedAndForeignQueriesWithoutEarlyRefund() {
    for (String mode : List.of("write", "publish", "nested", "foreign")) {
      var f = fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  f.context,
                  () -> {
                    var o = owner(f);
                    var p = proof();
                    o.withLineageScope(
                        f.context,
                        caller("caller"),
                        p,
                        () -> {
                          long held = f.context.scratchLive();
                          assertThrows(
                              RuntimeException.class,
                              () -> {
                                switch (mode) {
                                  case "write" -> o.dependencyMutation(null);
                                  case "publish" -> o.scope(caller("caller"), p, () -> {});
                                  case "nested" ->
                                      o.withLineageScope(
                                          f.context, caller("caller"), p, () -> null);
                                  default ->
                                      o.borrowLineageNode(fixture().context, "tenant", "caller");
                                }
                              });
                          assertEquals(held, f.context.scratchLive());
                          return null;
                        });
                    return null;
                  }));
      assertEquals(65536, f.context.scratchLive());
    }
  }

  @Test
  void exportedAdmissionInputsKeepSourceOwnerAcrossEveryDependentWriteRefusal() {
    for (String mode :
        List.of("source", "target", "actor", "installation", "unknown", "unrelated")) {
      var f = fixture();
      java.util.function.Supplier<Object> action =
          () -> {
            var o = owner(f);
            o.admission();
            var a =
                new SessionScopeRecords.Authority(
                    "tenant",
                    "source",
                    "owner",
                    "tools",
                    "b",
                    "",
                    "",
                    List.of(
                        new SessionScopeRecords.AuthorityTarget(
                            "target", "p", "b", 0, "", "", "{}")));
            o.locked("tenant", "target");
            o.stageSource(
                caller("caller"),
                new SessionScopeRecords.SourceFact(
                    caller("source"),
                    new SessionScopeRecords.History(1, "b", null),
                    scope(),
                    a,
                    List.of()));
            o.beginLineageInputs();
            var input = o.borrowLineageNode(f.context, "tenant", "source");
            o.releaseLineageSource();
            long held = f.context.scratchLive();
            var d =
                switch (mode) {
                  case "actor" -> SessionQueryWork.Dependency.actor("tenant", "owner");
                  case "installation" ->
                      SessionQueryWork.Dependency.installation("tenant", "plugin");
                  case "unrelated" -> new SessionQueryWork.Dependency("tenant", "caller");
                  default -> new SessionQueryWork.Dependency("tenant", mode);
                };
            try {
              if (mode.equals("unrelated")) o.dependencyMutation(Set.of(d));
              else
                assertThrows(
                    RuntimeException.class,
                    () -> o.dependencyMutation(mode.equals("unknown") ? null : Set.of(d)));
              assertEquals(held, f.context.scratchLive());
              assertEquals("source", input.caller().id());
            } finally {
              input = null;
              o.endLineageInputs();
            }
            return null;
          };
      if (mode.equals("unrelated")) f.transaction.required(f.context, action);
      else assertThrows(RuntimeException.class, () -> f.transaction.required(f.context, action));
      assertEquals(65536, f.context.scratchLive());
    }
  }

  @Test
  void errorCleanupAndReservationRefusalKeepFirstFailure() {
    for (boolean fill : List.of(false, true)) {
      var f = fixture();
      var oref = new SessionQueryWork[1];
      try {
        f.transaction.required(
            f.context,
            () -> {
              var o = owner(f);
              oref[0] = o;
              if (fill) f.context.retainScratch(this, 1048576 - f.context.scratchLive());
              return o.withLineageScope(
                  f.context,
                  caller("caller"),
                  proof(),
                  () -> {
                    throw new AssertionError("node input Error");
                  });
            });
        fail("must reject");
      } catch (RuntimeException | AssertionError expected) {
        assertNotNull(f.context.recordedFailure());
        if (!fill) assertTrue(expected instanceof AssertionError);
      } finally {
        f.context.releaseScratch(this);
      }
      assertEquals(65536, f.context.scratchLive());
      assertThrows(
          RuntimeException.class, () -> oref[0].borrowLineageNode(f.context, "tenant", "caller"));
    }
  }

  @Test
  void metadataErrorClosesCursorAndPoisonsBeforeCallerCanCatchIt() {
    var f = fixture();
    var error = new AssertionError("metadata error");
    var closed = new boolean[1];
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                f.context,
                () -> {
                  var o = owner(f);
                  o.admission();
                  o.stageSource(
                      caller("caller"),
                      new SessionScopeRecords.SourceFact(
                          caller("source"),
                          new SessionScopeRecords.History(1, "b", null),
                          scope(),
                          authority("source"),
                          List.of()));
                  o.beginLineageInputs();
                  o.borrowLineageNode(f.context, "tenant", "source");
                  var rows =
                      (java.sql.ResultSet)
                          java.lang.reflect.Proxy.newProxyInstance(
                              java.sql.ResultSet.class.getClassLoader(),
                              new Class<?>[] {java.sql.ResultSet.class},
                              (proxy, method, args) -> {
                                if (method.getName().equals("getStatement")) throw error;
                                if (method.getName().equals("close")) closed[0] = true;
                                return null;
                              });
                  assertSame(
                      error,
                      assertThrows(
                          AssertionError.class,
                          () ->
                              QueryResults.meter(
                                  rows,
                                  f.context,
                                  "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageMapper.nodeInput")));
                  assertTrue(closed[0]);
                  assertSame(error, f.context.recordedFailure().getCause());
                  o.releaseLineageSource();
                  o.endLineageInputs();
                  return null;
                }));
    assertEquals(65536, f.context.scratchLive());
  }

  @Test
  void suspendedOuterWorkCannotLendProofIntoRequiresNew() {
    var f = fixture();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                f.context,
                () -> {
                  var o = owner(f);
                  return o.withLineageScope(
                      f.context,
                      caller("caller"),
                      proof(),
                      () -> {
                        var inner = fixture();
                        return inner.transaction.requiresNew(
                            inner.context,
                            () -> o.borrowLineageNode(f.context, "tenant", "source"));
                      });
                }));
    assertEquals(65536, f.context.scratchLive());
  }

  @Test
  void verifyClosureHasOwnerBeforeItCanBeAllocated() {
    var f = fixture();
    var query =
        new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
            System.nanoTime() + 2_000_000_000L,
            new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources(
                0, 0, 0));
    query.restrictLineage();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                query,
                () -> {
                  var o = SessionQueryTransaction.current(query);
                  query.retainScratch(this, 1048576 - query.scratchLive());
                  long held = query.scratchLive();
                  assertThrows(RuntimeException.class, o::prepareLineageScope);
                  assertEquals(held, query.scratchLive());
                  query.releaseScratch(this);
                  return null;
                }));
    assertEquals(65536, query.scratchLive());
  }
}
