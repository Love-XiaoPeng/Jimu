package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 只验证新增借用及其写前/owner/首错边界；SQL来源另由私库场景验证。 */
class SessionLineageSourceBorrowTest {
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

  private SessionScopeRecords.SourceFact fact() {
    return new SessionScopeRecords.SourceFact(
        caller("source"),
        new SessionScopeRecords.History(1, "b", null),
        new SessionScopeRecords.Scope(
            1, 1, "owner", "b", "tools", "{}", "d", 2, 1, "ACTIVE", null, null, null, null, null,
            "PRESENT", true),
        new SessionScopeRecords.Authority(
            "tenant",
            "source",
            "owner",
            "tools",
            "b",
            "",
            "",
            List.of(new SessionScopeRecords.AuthorityTarget("target", "p", "b", 0, "", "", "{}"))),
        List.of());
  }

  private SessionQueryWork stage(SessionQueryContext q) {
    var owner = SessionQueryTransaction.current(q);
    owner.admission();
    owner.locked("tenant", "new");
    owner.locked("tenant", "source");
    owner.locked("tenant", "target");
    owner.stageSource(caller("new"), fact());
    return owner;
  }

  @Test
  void sameOwnerBorrowsOriginalWithAdditiveOwnerAndCallerOrAbsentFallsBack() {
    var f = new SessionQueryTransactionTest.Fixture();
    f.transaction.required(
        f.context,
        () -> {
          var o = stage(f.context);
          long before = f.context.scratchLive();
          assertNull(o.borrowLineageSource(f.context, "tenant", "new"));
          assertNull(o.borrowLineageSource(f.context, "tenant", "absent"));
          var original = o.stagedSource(caller("new"), "source");
          assertSame(original, o.borrowLineageSource(f.context, "tenant", "source"));
          assertEquals(before + 512, f.context.scratchLive());
          o.releaseLineageSource();
          assertEquals(before, f.context.scratchLive());
          assertSame(original, o.stagedSource(caller("new"), "source"));
          return null;
        });
  }

  @Test
  void everyOriginalDependencyInvalidatesBeforeNewSqlAndUnrelatedCallerRemainsAllowed() {
    for (String mode :
        List.of("source", "target", "actor", "installation", "unknown", "unrelated")) {
      var f = new SessionQueryTransactionTest.Fixture();
      f.transaction.required(
          f.context,
          () -> {
            var o = stage(f.context);
            var d =
                switch (mode) {
                  case "actor" -> SessionQueryWork.Dependency.actor("tenant", "owner");
                  case "installation" ->
                      SessionQueryWork.Dependency.installation("tenant", "plugin");
                  case "unrelated" -> new SessionQueryWork.Dependency("tenant", "new");
                  default -> new SessionQueryWork.Dependency("tenant", mode);
                };
            o.dependencyMutation(mode.equals("unknown") ? null : Set.of(d));
            var result = o.borrowLineageSource(f.context, "tenant", "source");
            assertEquals(mode.equals("unrelated"), result != null);
            o.releaseLineageSource();
            return null;
          });
    }
  }

  @Test
  void writeWhileBorrowedPoisonsAndDoesNotRefundStillHeldSource() {
    var f = new SessionQueryTransactionTest.Fixture();
    RuntimeException[] first = {null};
    var got =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    f.context,
                    () -> {
                      var o = stage(f.context);
                      o.borrowLineageSource(f.context, "tenant", "source");
                      long held = f.context.scratchLive();
                      first[0] =
                          assertThrows(RuntimeException.class, () -> o.dependencyMutation(null));
                      assertEquals(held, f.context.scratchLive());
                      o.releaseLineageSource();
                      assertSame(
                          first[0],
                          assertThrows(
                              RuntimeException.class,
                              () -> o.borrowLineageSource(f.context, "tenant", "source")));
                      return null;
                    }));
    assertSame(first[0], got);
    assertEquals(1, f.rollbacks);
  }

  @Test
  void foreignQueryAndSuspendedOwnerCannotBorrowAndCompletedOwnerStaysClosed() {
    var f = new SessionQueryTransactionTest.Fixture();
    SessionQueryWork[] held = {null};
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                f.context,
                () -> {
                  held[0] = stage(f.context);
                  held[0].borrowLineageSource(
                      new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null),
                      "tenant",
                      "source");
                  return null;
                }));
    assertThrows(
        IllegalStateException.class,
        () -> held[0].borrowLineageSource(f.context, "tenant", "source"));
    var other = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        RuntimeException.class,
        () ->
            other.transaction.required(
                other.context,
                () -> {
                  var o = stage(other.context);
                  return other.transaction.requiresNew(
                      new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null),
                      () -> o.borrowLineageSource(other.context, "tenant", "source"));
                }));
  }

  @Test
  void caughtErrorStillPoisonsWorkAndBorrowReleasePreservesCause() {
    var f = new SessionQueryTransactionTest.Fixture();
    var error = new AssertionError("source read error");
    var got =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    f.context,
                    () -> {
                      var o = stage(f.context);
                      o.borrowLineageSource(f.context, "tenant", "source");
                      o.poisonLineageSource(error);
                      o.releaseLineageSource();
                      assertSame(error, f.context.recordedFailure().getCause());
                      return null;
                    }));
    assertSame(error, got.getCause());
    assertEquals(1, f.rollbacks);
  }

  @Test
  void scratchRefusalBeforeBorrowRetainsOriginalSourceAndMissingLockRefuses() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L,
            new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources(
                0, 0, 0));
    q.restrictLineage();
    Object fill = new Object();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  var o = stage(q);
                  q.retainScratch(fill, 1048576 - q.scratchLive());
                  long full = q.scratchLive();
                  assertThrows(
                      RuntimeException.class, () -> o.borrowLineageSource(q, "tenant", "source"));
                  assertEquals(full, q.scratchLive());
                  assertTrue(!o.lineageSourceActive());
                  q.releaseScratch(fill);
                  return null;
                }));
    assertEquals(65536, q.scratchLive());
    var missing = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        RuntimeException.class,
        () ->
            missing.transaction.required(
                missing.context,
                () -> {
                  var o = SessionQueryTransaction.current(missing.context);
                  o.admission();
                  o.locked("tenant", "new");
                  o.stageSource(caller("new"), fact());
                  return o.borrowLineageSource(missing.context, "tenant", "source");
                }));
  }

  @Test
  void nestedBorrowAndPublicationBeforeReleaseCannotRefundSource() {
    for (boolean publish : List.of(false, true)) {
      var f = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          RuntimeException.class,
          () ->
              f.transaction.required(
                  f.context,
                  () -> {
                    var o = stage(f.context);
                    o.borrowLineageSource(f.context, "tenant", "source");
                    long held = f.context.scratchLive();
                    assertThrows(
                        RuntimeException.class,
                        () -> {
                          if (publish) o.graph("tenant", "new", null);
                          else o.borrowLineageSource(f.context, "tenant", "source");
                        });
                    assertEquals(held, f.context.scratchLive());
                    o.releaseLineageSource();
                    return null;
                  }));
    }
  }

  @Test
  void changingPhysicalHolderCannotBorrowEvenWithOriginalQueryAndWork() {
    var f = new SessionQueryTransactionTest.Fixture();
    var other = new SessionQueryTransactionTest.Fixture();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                f.context,
                () -> {
                  var o = stage(f.context);
                  var holder =
                      (org.springframework.jdbc.datasource.ConnectionHolder)
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .getResource(f.source);
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .unbindResource(f.source);
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .bindResource(
                          f.source,
                          new org.springframework.jdbc.datasource.ConnectionHolder(
                              other.connection));
                  try {
                    return o.borrowLineageSource(f.context, "tenant", "source");
                  } finally {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .unbindResource(f.source);
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .bindResource(f.source, holder);
                  }
                }));
  }

  @Test
  void operationRefusalOccursBeforeBorrowAndRemainsFirstAfterRelease() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L,
            new io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources(
                0, 0, 0));
    q.restrictLineage();
    var got =
        assertThrows(
            RuntimeException.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var o = stage(q);
                      q.operation(65536 - q.resources().operations());
                      var first =
                          assertThrows(
                              RuntimeException.class,
                              () -> o.borrowLineageSource(q, "tenant", "source"));
                      assertTrue(!o.lineageSourceActive());
                      o.releaseLineageSource();
                      assertSame(first, q.recordedFailure());
                      return null;
                    }));
    assertSame(got, q.recordedFailure());
  }
}
