package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionLineageMetadataBatchTest {
  private SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }

  private List<Map<String, Object>> rows() {
    return new ArrayList<>(
        List.of(
            new HashMap<>(
                Map.of(
                    "tenant_id",
                    "租户😀",
                    "task_id",
                    "task",
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

  @Test
  void cursorClosureDoesNotReleaseBatchAndClosedFacadesCannotRead() {
    var q = query();
    long base = q.scratchLive();
    var rows = rows();
    var batch =
        new SessionLineageMetadataBatch(
            q,
            () -> {
              try (var cursor = q.result()) {
                cursor.column("tenant_id", "租户😀");
              }
              return rows;
            });
    assertEquals(base + SessionLineageMetadataBatch.RETAINED_BYTES, q.scratchLive());
    var iterator = batch.iterator();
    var row = iterator.next();
    var scalars = row.values().iterator();
    assertEquals(10, batch.scalarBytes("租户😀"));
    batch.close();
    assertEquals(1, rows.size()); // borrowed test alias; production Supplier transfers ownership
    assertEquals(base, q.scratchLive());
    assertThrows(IllegalStateException.class, iterator::hasNext);
    assertThrows(IllegalStateException.class, row::values);
    assertThrows(IllegalStateException.class, scalars::hasNext);
  }

  @Test
  void remainingScratchBoundaryRefusesBeforeMapperAndOtherHoldsSurvive() {
    for (int delta : List.of(-1, 0, 1)) {
      var q = query();
      Object proof = new Object();
      q.retainScratch(
          proof, 1048576 - q.scratchLive() - SessionLineageMetadataBatch.RETAINED_BYTES - delta);
      long held = q.scratchLive();
      var called = new java.util.concurrent.atomic.AtomicBoolean();
      if (delta < 0) {
        assertThrows(
            TaskFailure.class,
            () ->
                new SessionLineageMetadataBatch(
                    q,
                    () -> {
                      called.set(true);
                      return rows();
                    }));
        assertEquals(false, called.get());
      } else {
        try (var batch = new SessionLineageMetadataBatch(q, this::rows)) {
          assertEquals(held + SessionLineageMetadataBatch.RETAINED_BYTES, q.scratchLive());
        }
      }
      assertEquals(held, q.scratchLive());
    }
  }

  @Test
  void loadingConversionAndConsumerFailuresReleaseOnlyTheirOwnBatch() {
    var q = query();
    long base = q.scratchLive();
    try (var first = new SessionLineageMetadataBatch(q, this::rows)) {
      long held = q.scratchLive();
      assertThrows(
          IllegalStateException.class,
          () ->
              new SessionLineageMetadataBatch(
                  q,
                  () -> {
                    throw new IllegalStateException("mapper");
                  }));
      assertEquals(held, q.scratchLive());
      var invalid = rows();
      invalid.getFirst().put("record_version", "1");
      assertThrows(TaskFailure.class, () -> new SessionLineageMetadataBatch(q, () -> invalid));
      assertEquals(1, invalid.size());
      assertEquals(held, q.scratchLive());
      assertThrows(
          TaskFailure.class,
          () -> {
            try (var second = new SessionLineageMetadataBatch(q, this::rows)) {
              second.scalarBytes("\ud800");
            }
          });
      assertEquals(held, q.scratchLive());
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            try (var second = new SessionLineageMetadataBatch(q, this::rows)) {
              throw new IllegalArgumentException("consumer");
            }
          });
      assertEquals(held, q.scratchLive());
    }
    assertEquals(base, q.scratchLive());
  }

  @Test
  void emptyBatchAndNumericWidthsNeedNoTemporaryWire() {
    var q = query();
    try (var batch = new SessionLineageMetadataBatch(q, ArrayList::new)) {
      assertEquals(false, batch.iterator().hasNext());
      assertEquals(11, batch.scalarBytes(Integer.MIN_VALUE));
      assertEquals(1, batch.scalarBytes(0));
      assertEquals(19, SessionQueryContext.scalarBytes(Long.MAX_VALUE));
      assertEquals(20, SessionQueryContext.scalarBytes(Long.MIN_VALUE));
    }
  }

  @Test
  void finalLineageHasExactEightScalarsAndReleasesOnWrongShapeOrError() {
    var q = query();
    var result = SessionLineageMetadataBatch.measureFinal(q, this::rows);
    long expected = 32;
    try (var batch = new SessionLineageMetadataBatch(query(), this::rows)) {
      for (var v : batch.iterator().next().values()) expected += 8 + batch.scalarBytes(v);
    }
    assertEquals(expected, result.bytes());
    assertEquals(1, result.rows());
    assertEquals(65536, q.scratchLive());
    for (String mode : List.of("extra", "missing", "type", "surrogate")) {
      var values = rows();
      switch (mode) {
        case "extra" -> values.getFirst().put("unknown", null);
        case "missing" -> values.getFirst().remove("task_id");
        case "type" -> values.getFirst().put("graph_bytes", 1L);
        default -> values.getFirst().put("tenant_id", "\ud800");
      }
      var bad = query();
      assertThrows(
          RuntimeException.class,
          () -> SessionLineageMetadataBatch.measureFinal(bad, () -> values));
      assertEquals(65536, bad.scratchLive());
    }
  }
}
