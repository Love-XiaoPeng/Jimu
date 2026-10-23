package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.model.service.impl.ModelDispatchServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ModelFailurePolicyTest {
  private static final Lease LEASE =
      new Lease("a", "owner", "task", "attempt", 1, "worker", Instant.MAX, "goal", List.of(), 1);

  private static final class Fixture implements ModelDispatchRecords.Dispatch, LedgerUnitOfWork {
    final List<String> events = new ArrayList<>();
    boolean paid, session, inside, costFailure;
    long reserved = 20, actual = -1, released = -1;
    ModelUsage usage;

    ModelDispatchServiceImpl service() {
      return new ModelDispatchServiceImpl(
          (lease, id) -> {
            assertTrue(inside);
            events.add("lock");
            return this;
          },
          this,
          new io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords() {
            public SnapshotAccess lockModelSnapshot(Lease lease) {
              throw new AssertionError("not a failure path");
            }

            public io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot
                lockToolSnapshot(Lease lease) {
              throw new AssertionError("not a failure path");
            }

            public Admission lockAdmission(Lease lease, String step) {
              throw new AssertionError("not a failure path");
            }

            public Response lockResponse(Lease lease, String step, String dispatch) {
              throw new AssertionError("not a failure path");
            }

            public Outcome outcome(Lease lease, String dispatch) {
              throw new AssertionError("not a failure path");
            }
          },
          new io.github.lovexiaopeng.jimu.kernel.transaction.port.LeaseUnitOfWork() {
            public <T> T admission(Lease lease, Supplier<T> work) {
              throw new AssertionError("not a failure path");
            }

            public <T> T directory(Lease lease, Supplier<T> work) {
              throw new AssertionError("not a failure path");
            }
          },
          io.github.lovexiaopeng.jimu.adapters.codec.Payloads::hash);
    }

    @Override
    public <T> T required(Supplier<T> work) {
      inside = true;
      try {
        return work.get();
      } finally {
        inside = false;
      }
    }

    @Override
    public boolean paid() {
      return paid;
    }

    @Override
    public boolean session() {
      return session;
    }

    @Override
    public void settleCost(ModelUsage value) {
      assertTrue(inside);
      usage = value;
      events.add("cost");
      if (costFailure) throw new TaskFailure(409, "MODEL_SETTLEMENT_PROOF_INVALID");
    }

    @Override
    public void markFailed(String code) {
      assertTrue(inside);
      events.add("failed");
    }

    @Override
    public long reservedTokens() {
      return reserved;
    }

    @Override
    public void settleTokens(long value, long delta) {
      actual = value;
      released = delta;
      events.add("tokens");
    }

    @Override
    public void holdUnknown() {
      events.add("unknown");
    }

    @Override
    public void accountSession() {
      events.add("account");
    }
  }

  @Test
  void paidMissingUsageRetainsUnknownCostAndTokens() {
    var f = new Fixture();
    f.paid = true;
    f.session = true;
    f.service().failed(LEASE, "dispatch", "PROVIDER_FAILURE", null);
    assertNotNull(f.usage);
    assertNull(f.usage.totalTokens());
    assertNull(f.usage.inputTokens());
    assertEquals(List.of("lock", "cost", "failed", "unknown", "account"), f.events);
    assertEquals(-1, f.released);
  }

  @Test
  void onlyProvedSessionTokensWithinReservationReleaseDifference() {
    for (long tokens : new long[] {19, 20, 21}) {
      var f = new Fixture();
      f.session = true;
      f.service()
          .failed(
              LEASE,
              "dispatch",
              "PROVIDER_FAILURE",
              new ModelUsage(10L, 9L, tokens, null, null, null, null, null));
      assertEquals(tokens <= 20, tokens == f.actual);
      assertEquals(tokens <= 20 ? 20 - tokens : -1, f.released);
      assertEquals(tokens > 20, f.events.contains("unknown"));
    }
  }

  @Test
  void corruptSettlementCannotAdvanceFailureOrRelease() {
    var f = new Fixture();
    f.paid = true;
    f.session = true;
    f.costFailure = true;
    assertEquals(
        "MODEL_SETTLEMENT_PROOF_INVALID",
        assertThrows(
                TaskFailure.class,
                () -> f.service().failed(LEASE, "dispatch", "PROVIDER_FAILURE", null))
            .code());
    assertEquals(List.of("lock", "cost"), f.events);
    assertEquals(-1, f.released);
  }
}
