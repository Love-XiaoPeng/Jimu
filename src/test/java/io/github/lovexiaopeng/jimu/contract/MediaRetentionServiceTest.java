package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.MediaRetentions;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.MediaRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MediaRetentionServiceTest {
  private boolean active;
  private int transactions, deletes, decoded;
  private Runnable atDelete = () -> {};
  private final Records records = new Records();
  private final ArtifactStore store =
      new ArtifactStore() {
        public String write(String tenant, String id, byte[] bytes, String hash) {
          throw new AssertionError();
        }

        public byte[] read(String tenant, String key, String hash, long size) {
          throw new AssertionError();
        }

        public void check() {}

        public void removeUpload(String tenant, String id) {
          assertFalse(active, "storage removal must be outside both UoWs");
          assertEquals("t", tenant);
          assertEquals("persisted-image", id);
          deletes++;
          atDelete.run();
        }
      };

  private MediaRetentionServiceImpl service() {
    return new MediaRetentionServiceImpl(
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
        value -> "digest:" + value,
        (value, field) -> {
          assertEquals("requestBody", field);
          decoded++;
          return "retained-metadata";
        });
  }

  @Test
  void unavailableStoreDoesNotTouchRecordsOrDatabase() {
    service().cleanup(null);
    assertEquals(0, records.scans);
    assertEquals(0, transactions);
  }

  @Test
  void terminalCleanupUsesActualIncludingZeroAndFallsBackOnlyForNull() {
    for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      records.state = state;
      records.cleanup = "RETAINED";
      service().cleanup(store);
      assertEquals("DELETED", records.cleanup);
    }
    assertEquals(3, deletes);
    assertEquals(6, transactions);
    assertEquals(3 * 17, records.storageBytes);
    assertEquals(0, records.checkpointBytes, "actual zero must not fall back to reserved amount");
    assertEquals(9, records.released.size());
    assertEquals("retained-metadata", records.retained);
    assertEquals("digest:retained-metadata", records.retainedDigest);
  }

  @Test
  void nonterminalOrUnobservedWritersAndUnknownHoldsCannotPurge() {
    for (String state : List.of("RUNNING", "QUEUED", "NEEDS_RECONCILIATION", "WAITING_USER")) {
      records.state = state;
      service().cleanup(store);
    }
    records.state = "FAILED";
    records.quiet = false;
    service().cleanup(store);
    records.quiet = true;
    records.unknown = true;
    service().cleanup(store);
    assertEquals(0, deletes);
    assertTrue(records.released.isEmpty());
  }

  @Test
  void deletedCandidateShortCircuitsBeforeWriterProbe() {
    records.cleanup = "DELETED";
    service().cleanup(store);
    assertEquals(0, records.probes);
    assertEquals(0, deletes);
  }

  @Test
  void storageFailureAbortsBatchBeforeFinalizationAndRetryUsesPersistedIdentity() {
    atDelete =
        () -> {
          throw new IllegalStateException("disk unavailable");
        };
    assertThrows(IllegalStateException.class, () -> service().cleanup(store));
    assertEquals(1, transactions);
    assertTrue(records.marked);
    assertTrue(records.released.isEmpty());
    assertEquals(0, decoded);
    atDelete = () -> {};
    service().cleanup(store);
    assertEquals("DELETED", records.cleanup);
  }

  @Test
  void secondPhaseRechecksStateWriterAndUnknownAfterExternalDelete() {
    for (String change : List.of("state", "writer", "unknown")) {
      records.state = "SUCCEEDED";
      records.quiet = true;
      records.unknown = false;
      atDelete =
          () -> {
            switch (change) {
              case "state" -> records.state = "NEEDS_RECONCILIATION";
              case "writer" -> records.quiet = false;
              default -> records.unknown = true;
            }
          };
      assertEquals(
          "IMAGE_UNAVAILABLE",
          assertThrows(TaskFailure.class, () -> service().cleanup(store)).code());
      assertTrue(records.released.isEmpty());
    }
  }

  @Test
  void corruptSnapshotIsRejectedBeforeDecodeOrAccounting() {
    records.corrupt = true;
    assertEquals(
        "SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, () -> service().cleanup(store)).code());
    assertEquals(0, decoded);
    assertTrue(records.released.isEmpty());
  }

  @Test
  void completedConcurrentFinalizerPreventsDoubleReleaseAndRedaction() {
    atDelete = () -> records.cleanup = "DELETED";
    service().cleanup(store);
    assertEquals(0, decoded);
    assertTrue(records.released.isEmpty());
  }

  private final class Records implements MediaRetentions {
    String state = "SUCCEEDED", cleanup = "RETAINED", retained, retainedDigest;
    boolean quiet = true, unknown, corrupt, marked;
    int scans, probes;
    long storageBytes, checkpointBytes;
    final List<String> released = new ArrayList<>();

    public List<Candidate> candidates() {
      assertFalse(active);
      scans++;
      return List.of(new Candidate("t", "task"));
    }

    public void lockBudget(String tenant) {
      assertTrue(active);
      assertEquals("t", tenant);
    }

    public LockedTask lockTask(Candidate candidate) {
      assertTrue(active);
      return new LockedTask() {
        public String state() {
          return state;
        }

        public String cleanupState() {
          return cleanup;
        }

        public boolean writersQuiescent() {
          probes++;
          return quiet;
        }
      };
    }

    public boolean hasUnknownHold(Candidate c) {
      assertTrue(active);
      return unknown;
    }

    public void markDeleting(Candidate c) {
      assertTrue(active);
      marked = true;
    }

    public List<String> deletingObjects(Candidate c) {
      assertFalse(active);
      return List.of("persisted-image");
    }

    public List<Checkpoint> lockCheckpoints(Candidate c) {
      assertTrue(active);
      return List.of(new Checkpoint("step", "original", corrupt ? "bad" : "digest:original"));
    }

    public void purgeCheckpoint(Candidate c, String id, String value, String digest) {
      assertTrue(active);
      retained = value;
      retainedDigest = digest;
    }

    public List<Hold> lockHolds(Candidate c) {
      assertTrue(active);
      return List.of(
          new Hold("input", "MEDIA_INPUT", 7, null),
          new Hold("storage", "MEDIA_STORAGE", 100, 10L),
          new Hold("checkpoint", "MEDIA_CHECKPOINT", 99, 0L));
    }

    public void releaseCheckpointBytes(String t, long bytes) {
      assertTrue(active);
      checkpointBytes += bytes;
    }

    public void releaseStorageBytes(String t, long bytes) {
      assertTrue(active);
      storageBytes += bytes;
    }

    public void releaseHold(Candidate c, String id) {
      assertTrue(active);
      released.add(id);
    }

    public void purgeInputs(Candidate c) {
      assertTrue(active);
    }

    public void markObjectsDeleted(Candidate c) {
      assertTrue(active);
    }

    public void markTaskDeleted(Candidate c) {
      assertTrue(active);
      cleanup = "DELETED";
    }
  }
}
