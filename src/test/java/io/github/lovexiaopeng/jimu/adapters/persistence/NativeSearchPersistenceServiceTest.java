package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.search.port.NativeSearchRecords;
import io.github.lovexiaopeng.jimu.kernel.search.service.impl.NativeSearchPersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 合成端口验证原事务、惰性、首错和短路；不是进程死亡证明。 */
class NativeSearchPersistenceServiceTest {
  static final Lease LEASE =
      new Lease("t", "a", "task", "current", 2, "worker", null, "g", List.of(), 1);
  static final Invocation INV = new Invocation("inv", "effect", "RUNNING", null);
  static final Run RUN =
      new Run("t", "task", "inv", "effect", "old", "worker", "dir", "snap", "STARTED", null, false);
  static final String HASH = "a".repeat(64);

  static class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    final Map<String, Object[]> args = new HashMap<>();
    final RuntimeException failure = new IllegalStateException("sentinel");
    String failAt;
    boolean inside;
    final NativeSearchRecords records = proxy(NativeSearchRecords.class);
    final NativeSearchPersistenceServiceImpl service =
        new NativeSearchPersistenceServiceImpl(records);

    H() {
      values.put("state", "RUNNING");
      values.put("tool", "glob");
      values.put("effectIntentId", "effect");
      values.put("phase", "STOPPED");
      values.put("hostId", "host");
      values.put("storageLimit", 8388608L);
      values.put("cursorIndex", "raw");
      values.put("queryDigest", "q");
      values.put("toolPin", "p");
      values.put("has", true);
      values.put("intValue", 3);
      values.put("json", "fact");
      values.put("hash", HASH);
    }

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, a) -> {
                String n = m.getName();
                trace.add(n);
                args.put(n, a);
                if (n.equals(failAt)) throw failure;
                if (n.equals("leaseTransaction") || n.equals("transaction")) {
                  assertFalse(inside);
                  inside = true;
                  try {
                    return ((Supplier<?>) a[n.equals("transaction") ? 0 : 1]).get();
                  } finally {
                    inside = false;
                    trace.add("end");
                  }
                }
                if (n.equals("fenced")) {
                  assertTrue(inside);
                  assertSame(LEASE, a[0]);
                  assertEquals(true, a[1]);
                  return null;
                }
                if (values.containsKey(n)) return values.get(n);
                Class<?> result = m.getReturnType();
                if (result == List.class) return List.of();
                if (result == long.class) return 0L;
                if (result == int.class) return 1;
                if (result == boolean.class) return false;
                if (result == void.class || result == String.class || result == Object.class)
                  return null;
                if (result.isInterface()) return proxy(result);
                return null;
              });
    }

    void prepare() {
      service.prepareSearch(LEASE, INV, "dir", "snap", "q", "p");
    }

    void reject(String expected, Runnable call) {
      assertEquals(expected, assertThrows(TaskFailure.class, call::run).code());
    }
  }

  @Test
  void constructorAndQuiescentHaveNoTransactionOrFieldPreRead() {
    var h = new H();
    assertTrue(h.trace.isEmpty());
    assertTrue(h.service.searchQuiescent(LEASE, "inv"));
    assertEquals(List.of("selectNonStoppedSearch"), h.trace);
    assertEquals(List.of("t", "task", "inv"), List.of(h.args.get("selectNonStoppedSearch")));
  }

  @Test
  void preparePreservesCurrentWriterBudgetAndReserveOrder() {
    var h = new H();
    h.prepare();
    assertEquals(
        List.of(
            "leaseTransaction",
            "fenced",
            "budget",
            "selectSearchInvocation",
            "state",
            "tool",
            "effectIntentId",
            "selectSearchAggregate",
            "roots",
            "bytes",
            "storageHeld",
            "mediaCheckpointHeld",
            "storageLimit",
            "selectSearchWorkerHost",
            "hostId",
            "insertSearchRun",
            "holdSearchStorage",
            "reserve",
            "end"),
        h.trace);
    assertEquals(
        List.of("t", "inv", "task", "effect", "current", "worker", "host", "dir", "snap", "q", "p"),
        List.of(h.args.get("insertSearchRun")));
    assertEquals(4194304L, h.args.get("reserve")[5]);
    assertEquals(null, h.args.get("reserve")[3]);
  }

  @Test
  void invocationStateShortCircuitsBeforeToolAndAggregate() {
    var h = new H();
    h.values.put("state", "PROPOSED");
    h.reject("INVOCATION_MISMATCH", h::prepare);
    assertFalse(h.trace.contains("tool"));
    assertFalse(h.trace.contains("selectSearchAggregate"));
  }

  @Test
  void invalidToolShortCircuitsEffectRead() {
    var h = new H();
    h.values.put("tool", "read");
    h.reject("INVOCATION_MISMATCH", h::prepare);
    assertFalse(h.trace.contains("effectIntentId"));
  }

  @Test
  void rootThresholdShortCircuitsSumAndBudget() {
    var h = new H();
    h.values.put("roots", 20L);
    h.reject("SEARCH_RESULT_LIMIT", h::prepare);
    assertFalse(h.trace.contains("bytes"));
    assertFalse(h.trace.contains("storageHeld"));
  }

  @Test
  void aggregateBytesThresholdPrecedesStorageCheck() {
    var h = new H();
    h.values.put("bytes", 4194305L);
    h.reject("SEARCH_RESULT_LIMIT", h::prepare);
    assertFalse(h.trace.contains("storageHeld"));
  }

  @Test
  void mediaStorageParticipatesBeforeHostRead() {
    var h = new H();
    h.values.put("mediaCheckpointHeld", 4194305L);
    h.reject("STORAGE_BUDGET_EXCEEDED", h::prepare);
    assertFalse(h.trace.contains("selectSearchWorkerHost"));
  }

  @Test
  void startedRequiresExactlyOneAndSerializesSameIdentity() {
    var h = new H();
    var identity = new NativeIdentity(null, null, null);
    h.values.put("startSearchRun", 0);
    h.reject("SEARCH_PROCESS_UNPROVEN", () -> h.service.searchStarted(LEASE, "inv", identity));
    assertSame(identity, h.args.get("json")[0]);
    assertEquals(
        List.of("fact", "t", "task", "inv", "current"), List.of(h.args.get("startSearchRun")));
  }

  @Test
  void oldAttemptStopUsesTransactionAndOriginalTaskWithoutFence() {
    var h = new H();
    var task = h.proxy(NativeSearchRecords.Task.class);
    h.values.put("locked", task);
    h.values.put("processIdentityObject", "fact");
    h.service.searchStopped(RUN, new NativeIdentity(null, null, null));
    assertFalse(h.trace.contains("fenced"));
    assertSame(task, h.args.get("settleStoppedWriters")[0]);
    assertEquals(2, h.trace.stream().filter("processIdentityObject"::equals).count());
    assertTrue(h.trace.indexOf("insertSearchStop") < h.trace.indexOf("stopSearchRun"));
  }

  @Test
  void identityMismatchStopsBeforeHashAndWrites() {
    var h = new H();
    h.values.put("processIdentityObject", "other");
    h.reject("SEARCH_PROCESS_UNPROVEN", () -> h.service.searchStopped(RUN, null));
    assertFalse(h.trace.contains("hash"));
  }

  @Test
  void completionEarlyLimitsPrecedeLeaseAndNullHash() {
    var h = new H();
    h.reject("SEARCH_RESULT_LIMIT", () -> h.service.searchCompleted(null, "i", null, -1, null));
    h.reject(
        "SEARCH_RESULT_LIMIT", () -> h.service.searchCompleted(null, "i", null, 4194305, null));
    assertThrows(
        NullPointerException.class, () -> h.service.searchCompleted(null, "i", null, 0, null));
    h.reject("SEARCH_RESULT_LIMIT", () -> h.service.searchCompleted(null, "i", "bad", 0, null));
    assertTrue(h.trace.isEmpty());
  }

  @Test
  void completionUsesOriginalCursorReferenceAndDifferenceWithoutToolCommit() {
    var h = new H();
    var cursors = new HashMap<String, Integer>();
    cursors.put("c", 2);
    h.service.searchCompleted(LEASE, "inv", HASH, 71, cursors);
    assertSame(cursors, h.args.get("json")[0]);
    assertEquals(4194304L - 71, h.args.get("releaseCompletedSearchStorage")[0]);
    assertTrue(h.trace.indexOf("completeSearchRun") < h.trace.indexOf("settleSearchStorage"));
  }

  @Test
  void completionRejectsReplayBeforeCursorSerialization() {
    var h = new H();
    h.values.put("resultHashObject", HASH);
    h.reject(
        "SEARCH_PROCESS_UNPROVEN",
        () -> h.service.searchCompleted(LEASE, "inv", HASH, 0, Map.of()));
    assertFalse(h.trace.contains("json"));
  }

  @Test
  void pageSkipsAbsentCursorWithoutQueryOrResultReads() {
    var h = new H();
    h.values.put("selectSearchPage", List.of(h.proxy(NativeSearchRecords.RunRow.class)));
    h.values.put("has", false);
    h.reject("SEARCH_CURSOR_INVALID", () -> h.service.searchPage(LEASE, "q", "p", "c"));
    assertFalse(h.trace.contains("queryDigest"));
  }

  @Test
  void pageQueryMismatchShortCircuitsPinAndResult() {
    var h = new H();
    h.values.put("selectSearchPage", List.of(h.proxy(NativeSearchRecords.RunRow.class)));
    h.reject("SEARCH_CURSOR_INVALID", () -> h.service.searchPage(LEASE, "other", "p", "c"));
    assertFalse(h.trace.contains("toolPin"));
    assertFalse(h.trace.contains("rootInvocation"));
  }

  @Test
  void pageRetainsFieldAndIndexReadOrder() {
    var h = new H();
    h.values.put("selectSearchPage", List.of(h.proxy(NativeSearchRecords.RunRow.class)));
    h.values.put("resultSize", 17L);
    assertEquals(3, h.service.searchPage(LEASE, "q", "p", "c").page());
    assertEquals(
        List.of(
            "rootInvocation",
            "runtimeDirectory",
            "resultHash",
            "resultSize",
            "path",
            "intValue",
            "end"),
        h.trace.subList(h.trace.indexOf("rootInvocation"), h.trace.size()));
  }

  @Test
  void nativeScanNullIdentityIsLazyUnmodifiableAndHasNoTransaction() {
    var h = new H();
    h.values.put("selectNativeSearches", List.of(h.proxy(NativeSearchRecords.RunRow.class)));
    var runs = h.service.nativeSearches("host");
    assertEquals(null, runs.getFirst().identity());
    assertFalse(h.trace.contains("processIdentity"));
    assertFalse(h.trace.contains("readIdentity"));
    assertFalse(h.trace.contains("transaction"));
    assertThrows(UnsupportedOperationException.class, () -> runs.add(RUN));
  }

  @Test
  void expiredScanKeepsNullCodecArrivalAndConstantStopped() {
    var h = new H();
    h.values.put("selectExpiredSearches", List.of(h.proxy(NativeSearchRecords.RunRow.class)));
    var run = h.service.expiredSearches("host").getFirst();
    assertEquals("STOPPED", run.phase());
    assertTrue(run.stopRequested());
    assertTrue(h.trace.contains("readIdentity"));
    assertFalse(h.trace.contains("phase"));
  }

  @Test
  void releasedStorageStillWritesRemovedWithoutActualRead() {
    var h = new H();
    h.values.put("state", "RELEASED");
    h.service.searchStorageRemoved(RUN);
    assertFalse(h.trace.contains("actualObject"));
    assertFalse(h.trace.contains("releaseRemovedSearchStorage"));
    assertTrue(h.trace.contains("removeSearchStorage"));
  }

  @Test
  void storageReleaseUsesAmountOnlyWhenActualNull() {
    var h = new H();
    h.values.put("amount", 4194304L);
    h.service.searchStorageRemoved(RUN);
    assertEquals(4194304L, h.args.get("releaseRemovedSearchStorage")[0]);
    assertFalse(h.trace.contains("actual"));
    var a = new H();
    a.values.put("actualObject", 73L);
    a.values.put("actual", 73L);
    a.service.searchStorageRemoved(RUN);
    assertEquals(73L, a.args.get("releaseRemovedSearchStorage")[0]);
    assertFalse(a.trace.contains("amount"));
  }

  @Test
  void stopAndFencedFailurePropagateSameInstance() {
    var h = new H();
    h.failAt = "fenced";
    assertSame(h.failure, assertThrows(RuntimeException.class, h::prepare));
    assertFalse(h.trace.contains("budget"));
    var stop = new H();
    stop.failAt = "stopSearchRun";
    assertSame(
        stop.failure,
        assertThrows(RuntimeException.class, () -> stop.service.searchStopped(RUN, null)));
    assertFalse(stop.trace.contains("settleStoppedWriters"));
  }
}
