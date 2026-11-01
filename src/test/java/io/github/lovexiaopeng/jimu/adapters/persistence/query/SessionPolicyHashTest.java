package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionPolicyHashTest {
  @Test
  void onlySameImmutableObjectSharesRealCalculationInsideOneOwner() {
    var calls = new AtomicInteger();
    String raw = new String("immutable byte pinned policy");
    String digest = Payloads.hash(raw);
    for (int transaction = 0; transaction < 2; transaction++) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      fixture.transaction.required(
          q,
          () -> {
            var owner = SessionQueryTransaction.current(q);
            var calculate =
                (java.util.function.Supplier<String>)
                    () -> {
                      calls.incrementAndGet();
                      return digest;
                    };
            assertSame(digest, owner.policyHash(raw, calculate));
            long held = q.scratchLive();
            assertTrue(held > 65536);
            assertSame(
                digest,
                owner.policyHash(
                    raw,
                    () -> {
                      throw new AssertionError("duplicate encoding/hash");
                    }));
            assertEquals(held, q.scratchLive());
            owner.policyHash(new String(raw), calculate);
            return null;
          });
      assertEquals(65536, q.scratchLive());
      assertEquals(1, fixture.commits);
    }
    assertEquals(4, calls.get());
  }

  @Test
  void failedCalculationCannotBeCaughtToCommitOrPopulateCache() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var first = new IllegalStateException("hash marker");
    assertSame(
        first,
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.transaction.required(
                    q,
                    () -> {
                      var owner = SessionQueryTransaction.current(q);
                      assertSame(
                          first,
                          assertThrows(
                              RuntimeException.class,
                              () ->
                                  owner.policyHash(
                                      "policy",
                                      () -> {
                                        throw first;
                                      })));
                      assertSame(
                          first,
                          assertThrows(
                              RuntimeException.class,
                              () -> owner.policyHash("policy", () -> "never")));
                      return null;
                    })));
    assertEquals(1, fixture.rollbacks);
    assertEquals(65536, q.scratchLive());
  }

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }
}
