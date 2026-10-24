package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRetentions;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.impl.McpRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class McpRetentionServiceTest {
  private boolean active, quiet = true, unknown, fail;
  private String state = "SUCCEEDED";
  private int transactions, unknownReads, holdReads;
  private long bytes;
  private final List<String> events = new ArrayList<>(), released = new ArrayList<>();
  private List<McpRetentions.Hold> holds = List.of(new McpRetentions.Hold("mcp:root", 99, 7L));
  private final McpRetentions records =
      new McpRetentions() {
        public List<Candidate> candidates() {
          assertFalse(active);
          return List.of(new Candidate("t", "task"));
        }

        public void lockBudget(String tenant) {
          assertTrue(active);
          assertEquals("t", tenant);
        }

        public LockedTask lockTask(Candidate c) {
          assertTrue(active);
          return new LockedTask() {
            public String state() {
              return state;
            }

            public boolean writersQuiescent() {
              return quiet;
            }

            public List<Hold> lockExpiredHolds() {
              assertTrue(active);
              holdReads++;
              return holds;
            }

            public void event(String fact, String type) {
              assertTrue(active);
              assertEquals("mcp.result_deleted", type);
              events.add(fact);
            }
          };
        }

        public boolean hasUnknownHold(Candidate c) {
          assertTrue(active);
          unknownReads++;
          return unknown;
        }

        public void deleteCursors(Candidate c, String root) {
          assertTrue(active);
          assertEquals("root", root);
        }

        public void purgeResult(Candidate c, String root) {
          assertTrue(active);
          if (fail) throw new IllegalStateException("purge unavailable");
        }

        public void releaseStorage(String tenant, long amount) {
          assertTrue(active);
          bytes += amount;
        }

        public void releaseHold(Candidate c, String id) {
          assertTrue(active);
          released.add(id);
        }
      };

  private McpRetentionServiceImpl service() {
    return new McpRetentionServiceImpl(
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
        });
  }

  @Test
  void terminalQuiescentCleanupIncludesAtomicAccountingAndEvent() {
    for (var terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
      state = terminal;
      service().cleanup();
    }
    assertEquals(21, bytes);
    assertEquals(3, transactions);
    assertEquals(3, released.size());
    assertEquals(List.of("mcp-cleanup:root", "mcp-cleanup:root", "mcp-cleanup:root"), events);
  }

  @Test
  void unobservedWriterShortCircuitsEvenTerminalBeforeUnknownRead() {
    quiet = false;
    service().cleanup();
    assertEquals(0, unknownReads);
    assertEquals(0, holdReads);
    assertTrue(events.isEmpty());
  }

  @Test
  void runningOrUnknownStateNeverReleasedFromQuiescenceAlone() {
    for (var other : List.of("RUNNING", "WAITING_USER", "QUEUED", "NEEDS_RECONCILIATION")) {
      state = other;
      service().cleanup();
    }
    assertEquals(0, unknownReads);
    assertEquals(0, holdReads);
    assertTrue(released.isEmpty());
  }

  @Test
  void unknownHoldBlocksWholeTask() {
    unknown = true;
    service().cleanup();
    assertEquals(1, unknownReads);
    assertEquals(0, holdReads);
    assertEquals(0, bytes);
  }

  @Test
  void actualZeroNeverFallsBackAndOnlyNullUsesReservedAmount() {
    holds =
        List.of(
            new McpRetentions.Hold("mcp:root", 99, 0L),
            new McpRetentions.Hold("mcp:root", 17, null));
    service().cleanup();
    assertEquals(17, bytes);
    assertEquals(2, released.size());
  }

  @Test
  void concurrentAlreadyReleasedHoldSelectionProducesNoDuplicateEventOrCharge() {
    holds = List.of();
    service().cleanup();
    assertEquals(0, bytes);
    assertTrue(released.isEmpty());
    assertTrue(events.isEmpty());
  }

  @Test
  void purgeExceptionPropagatesBeforeSettlementAndEvent() {
    fail = true;
    assertThrows(IllegalStateException.class, () -> service().cleanup());
    assertEquals(0, bytes);
    assertTrue(events.isEmpty());
    assertTrue(released.isEmpty());
    assertFalse(active);
  }
}
