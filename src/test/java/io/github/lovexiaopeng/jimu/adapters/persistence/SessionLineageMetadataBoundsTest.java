package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SessionLineageMetadataBoundsTest {
  private SessionQueryContext q(long used) {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, used));
    q.restrictLineage();
    return q;
  }

  private List<Map<String, Object>> rows(String tenant) {
    return new ArrayList<>(
        List.of(
            new HashMap<>(
                Map.of(
                    "tenant_id",
                    tenant,
                    "task_id",
                    "t".repeat(36),
                    "record_version",
                    1,
                    "genesis_digest",
                    "a".repeat(64),
                    "genesis_bytes",
                    2,
                    "graph_digest",
                    "b".repeat(64),
                    "graph_bytes",
                    2,
                    "content_state",
                    "PRESENT"))));
  }

  private long load(SessionQueryContext q, List<Map<String, Object>> rows) {
    long before = q.resources().operations();
    long held = q.scratchLive();
    try (var batch = new SessionLineageMetadataBatch(q, () -> rows)) {
      assertEquals(held + SessionLineageMetadataBatch.RETAINED_BYTES, q.scratchLive());
    }
    assertEquals(held, q.scratchLive());
    return q.resources().operations() - before;
  }

  @Test
  void actualSavedShapeCostsAndStillReadsBothValuePasses() {
    var q = q(0);
    var rows = rows("tenant19");
    long before = q.resources().operations();
    try (var batch = new SessionLineageMetadataBatch(q, () -> rows)) {
      long construction = q.resources().operations() - before;
      System.out.println("LINEAGE_BOUND constructor=" + construction);
      assertEquals(43, construction);
      var row = batch.iterator().next();
      long first = 0, second = 0;
      for (Object v : row.values()) first += batch.scalarBytes(v);
      rows.getFirst().put("graph_bytes", 9999);
      for (Object v : row.values()) second += batch.scalarBytes(v);
      assertEquals(first + 3, second);
      assertEquals(9999, row.value("graph_bytes"));
      assertEquals(3, batch.scalarBytes(-27));
    }
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void exactOperationThresholdAndFirstSlot() {
    for (long budget : new long[] {42, 43, 44}) {
      var q = q(65536 - budget);
      var rows = rows("tenant19");
      if (budget == 42) {
        var first =
            assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(q, () -> rows));
        assertSame(first, q.recordedFailure());
        assertSame(first, assertThrows(TaskFailure.class, () -> q.operation(1)));
        assertEquals(65536, q.scratchLive());
      } else assertEquals(43, load(q, rows));
    }
  }

  @Test
  void supplementaryBoundaryAndFallbackCost() {
    for (String text :
        List.of("x".repeat(64), "\ud83d\ude00".repeat(64), "x".repeat(63) + "\ud83d\ude00")) {
      var q = q(0);
      long cost = load(q, rows(text));
      assertEquals(text.length() <= 64 ? 43 : 43 + text.length(), cost);
    }
    for (String bad :
        List.of("x".repeat(65), "\ud83d\ude00".repeat(65), "x".repeat(63) + "\ud83d\ude00x")) {
      var q = q(0);
      var rows = rows(bad);
      assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(q, () -> rows));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void fallbackPrepaidAndOversizeConstantGuard() {
    var q = q(65536 - 71); // outer dispatch 1, guard 7, then full 128 before codePointCount
    var beforeFallback = q;
    var supplementary = rows("\ud83d\ude00".repeat(64));
    assertThrows(
        TaskFailure.class,
        () -> new SessionLineageMetadataBatch(beforeFallback, () -> supplementary));
    assertEquals(128, q.operationRejection().requested());
    assertEquals(65536, q.scratchLive());
    q = q(0);
    var current = q;
    var bad = rows("x".repeat(129));
    assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(current, () -> bad));
    assertEquals(8, q.resources().operations());
  }

  @Test
  void nullTypesLabelsAndMoreThanOneRowStillRejected() {
    for (String key :
        List.of("tenant_id", "task_id", "genesis_digest", "graph_digest", "content_state")) {
      for (Object invalid : Arrays.asList(null, 1)) {
        var q = q(0);
        var rows = rows("tenant19");
        rows.getFirst().put(key, invalid);
        assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(q, () -> rows));
        assertEquals(65536, q.scratchLive());
      }
    }
    for (String change : List.of("missing", "extra", "numeric", "two")) {
      var q = q(0);
      var rows = rows("tenant19");
      switch (change) {
        case "missing" -> rows.getFirst().remove("tenant_id");
        case "extra" -> rows.getFirst().put("forged", "x");
        case "numeric" -> rows.getFirst().put("graph_bytes", 2L);
        case "two" -> rows.add(new HashMap<>(rows.getFirst()));
      }
      assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(q, () -> rows));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void unpairedSurrogateRetainsOriginalScalarRejection() {
    var q = q(0);
    try (var batch = new SessionLineageMetadataBatch(q, () -> rows("\ud800"))) {
      assertThrows(
          TaskFailure.class, () -> batch.scalarBytes(batch.iterator().next().value("tenant_id")));
    }
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void allocationFreeGuardPreservesExistingFailureAndLoaderError() {
    var q = q(0);
    var first = new IllegalStateException("first");
    q.recordFailure(first);
    var failed = q;
    assertSame(
        first,
        assertThrows(
            IllegalStateException.class,
            () -> new SessionLineageMetadataBatch(failed, () -> rows("tenant19"))));
    assertEquals(65536, q.scratchLive());
    q = q(0);
    var healthy = q;
    var error = new AssertionError("loader");
    assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () ->
                new SessionLineageMetadataBatch(
                    healthy,
                    () -> {
                      throw error;
                    })));
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void guardMatchesCodePointOracleAcrossRepresentations() throws Exception {
    var method =
        SessionLineageMetadataBatch.class.getDeclaredMethod("bounded", Object.class, int.class);
    method.setAccessible(true);
    var random = new Random(190127);
    int checked = 0;
    try (var batch = new SessionLineageMetadataBatch(null, ArrayList::new)) {
      for (int max : new int[] {16, 36, 64})
        for (int size = 0; size <= 2 * max + 2; size++) {
          var text = new StringBuilder();
          for (int i = 0; i < size; i++) text.append((char) random.nextInt(65536));
          String s = text.toString();
          assertEquals(s.codePointCount(0, s.length()) <= max, method.invoke(batch, s, max));
          checked++;
        }
    }
    System.out.println("LINEAGE_BOUND oracle=" + checked);
  }
}
