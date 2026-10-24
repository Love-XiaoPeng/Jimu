package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactRetentions;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ArtifactRetentionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  private final Records records = new Records();
  private boolean active;
  private int transactions, removals;
  private boolean storageFailure;
  private final ArtifactStore store =
      new ArtifactStore() {
        public String write(String tenant, String upload, byte[] bytes, String hash) {
          throw new AssertionError("Unexpected write");
        }

        public byte[] read(String tenant, String key, String hash, long size) {
          throw new AssertionError("Unexpected read");
        }

        public void check() {}

        public void removeUpload(String tenant, String upload) {
          assertFalse(active, "Storage deletion must not hold the database transaction");
          assertEquals("t", tenant);
          assertEquals("u", upload);
          removals++;
          if (storageFailure) throw new IllegalStateException("delete unavailable");
        }
      };

  private ArtifactRetentionServiceImpl service() {
    return new ArtifactRetentionServiceImpl(
        records,
        new LedgerUnitOfWork() {
          public <T> T required(Supplier<T> work) {
            assertFalse(active);
            active = true;
            transactions++;
            try {
              return work.get();
            } finally {
              active = false;
            }
          }
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void onlyTerminalQuiescentTasksCanExpire() {
    for (var state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      records.taskState = state;
      service().cleanup(store);
      assertEquals("EXPIRED", records.marked);
    }
    assertEquals(3, removals);
    assertEquals(6, transactions);
  }

  @Test
  void runningAndUnknownCannotBecomeSafeFromQuiescenceAlone() {
    for (var state : List.of("RUNNING", "QUEUED", "NEEDS_RECONCILIATION", "WAITING_USER")) {
      records.taskState = state;
      service().cleanup(store);
    }
    assertEquals(0, removals);
    assertEquals(0, records.probes);
  }

  @Test
  void futureOrExactExpiryRequiresFailedOrCancelledTask() {
    records.expiry = NOW;
    records.taskState = "SUCCEEDED";
    service().cleanup(store);
    assertEquals(0, removals);
    for (var state : List.of("FAILED", "CANCELLED")) {
      records.taskState = state;
      service().cleanup(store);
      assertEquals("ABANDONED", records.marked);
    }
    assertEquals(2, removals);
  }

  @Test
  void unobservedWritersKeepArtifactAndStorageHeld() {
    records.quiescent = false;
    service().cleanup(store);
    assertEquals(0, removals);
    assertEquals(0, records.releases);
    assertEquals(null, records.marked);
  }

  @Test
  void deleteFailurePreservesHoldAndStopsBatchUntilRetry() {
    storageFailure = true;
    assertThrows(IllegalStateException.class, () -> service().cleanup(store));
    assertEquals("EXPIRED", records.marked);
    assertEquals(1, transactions);
    assertEquals(0, records.releases);
    storageFailure = false;
    service().cleanup(store);
    assertEquals(31, records.releasedAmount);
    assertEquals(1, records.releases);
  }

  @Test
  void concurrentFinalizerDoesNotReleaseStorageTwice() {
    records.holdState = "RELEASED";
    service().cleanup(store);
    assertEquals(1, removals);
    assertEquals(0, records.releases);
    assertEquals(0, records.releasedAmount);
  }

  private final class Records implements ArtifactRetentions {
    private String taskState = "SUCCEEDED", marked, holdState = "SETTLED";
    private Instant expiry = NOW.minusSeconds(1);
    private boolean quiescent = true;
    private int probes, releases;
    private long releasedAmount;

    public List<Candidate> candidates() {
      assertFalse(active);
      return List.of(new Candidate("t", "a", "task", "u"));
    }

    public void lockBudget(String tenant) {
      assertTrue(active);
    }

    public LockedTask lockTask(Candidate candidate) {
      assertTrue(active);
      return new LockedTask() {
        public String state() {
          return taskState;
        }

        public boolean writersQuiescent() {
          probes++;
          return quiescent;
        }
      };
    }

    public Instant lockArtifactExpiry(Candidate candidate) {
      assertTrue(active);
      return expiry;
    }

    public void mark(Candidate candidate, String state) {
      assertTrue(active);
      marked = state;
    }

    public Hold hold(Candidate candidate) {
      assertTrue(active);
      return new Hold(holdState, 31);
    }

    public void releaseStorage(String tenant, long amount) {
      assertTrue(active);
      releasedAmount += amount;
    }

    public void releaseHold(Candidate candidate) {
      assertTrue(active);
      releases++;
      holdState = "RELEASED";
    }
  }
}
