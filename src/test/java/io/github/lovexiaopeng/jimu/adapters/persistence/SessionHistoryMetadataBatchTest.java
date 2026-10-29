package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionHistoryMetadataBatchTest {
  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row() {
    var r = new LinkedHashMap<String, Object>();
    r.put("tenant_id", "租户😀");
    r.put("task_id", "id");
    r.put("record_version", 1);
    r.put("history_version", 5);
    r.put("binding_snapshot", "绑定😀");
    r.put("binding_digest", "a".repeat(64));
    r.put("content_state", "PRESENT");
    for (String name :
        List.of("next_seq", "body_bytes", "metadata_bytes", "reserved_bytes", "held_bytes"))
      r.put(name, 0L);
    r.put("retain_until", LocalDateTime.of(2026, 10, 29, 0, 0, 0, 123456000));
    r.put("cleanup_receipt", null);
    return r;
  }

  private long cursorBytes(Map<String, Object> row) {
    return 3
        * row.entrySet().stream()
            .mapToLong(
                e ->
                    8
                        + SessionQueryContext.scalarBytes(e.getKey())
                        + SessionQueryContext.scalarBytes(e.getValue()))
            .sum();
  }

  @Test
  void actualCursorOverlapAtNMinusOneNAndNPlusOneAndFacadeClosure() {
    for (int delta : List.of(-1, 0, 1)) {
      var q = query();
      var r = row();
      Object proof = new Object();
      long needed = SessionHistoryMetadataBatch.RETAINED_BYTES + cursorBytes(r);
      q.retainScratch(proof, 1048576 - q.scratchLive() - needed - delta);
      long before = q.scratchLive();
      java.util.function.Supplier<List<Map<String, Object>>> load =
          () -> {
            try (var cursor = q.result("history-fixture")) {
              cursor.row();
              for (var e : r.entrySet()) cursor.column(e.getKey(), e.getValue());
            }
            return List.of(r); // immutable supplier is safe: close drops the sole owning reference.
          };
      if (delta < 0)
        assertThrows(TaskFailure.class, () -> new SessionHistoryMetadataBatch(q, load));
      else {
        var batch = new SessionHistoryMetadataBatch(q, load);
        var iterator = batch.iterator();
        var facade = iterator.next();
        var values = facade.values().iterator();
        assertEquals(1, batch.scalarBytes(null));
        assertEquals(10, batch.scalarBytes("绑定😀"));
        batch.close();
        batch.close();
        assertThrows(IllegalStateException.class, iterator::hasNext);
        assertThrows(IllegalStateException.class, values::hasNext);
        assertThrows(IllegalStateException.class, facade::values);
      }
      assertEquals(before, q.scratchLive());
    }
  }

  @Test
  void refusalBeforeLoadAndFailuresReleaseOnlyTheOwnedBatch() {
    var q = query();
    long base = q.scratchLive();
    Object other = new Object();
    q.retainScratch(other, 1048576 - base - SessionHistoryMetadataBatch.RETAINED_BYTES + 1);
    var called = new AtomicBoolean();
    assertThrows(
        TaskFailure.class,
        () ->
            new SessionHistoryMetadataBatch(
                q,
                () -> {
                  called.set(true);
                  return List.of();
                }));
    assertFalse(called.get());
    q.releaseScratch(other);
    try (var empty = new SessionHistoryMetadataBatch(q, List::of)) {
      assertFalse(empty.iterator().hasNext());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SessionHistoryMetadataBatch(
                q,
                () -> {
                  throw new IllegalArgumentException("load");
                }));
    var invalid = row();
    invalid.put("body", "not metadata");
    assertThrows(
        TaskFailure.class, () -> new SessionHistoryMetadataBatch(q, () -> List.of(invalid)));
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var batch = new SessionHistoryMetadataBatch(q, () -> List.of(row()))) {
            throw new IllegalStateException("consumer");
          }
        });
    try (var batch = new SessionHistoryMetadataBatch(q, () -> List.of(row()))) {
      assertEquals(20, batch.scalarBytes(Long.MIN_VALUE));
      assertEquals(27, batch.scalarBytes(row().get("retain_until")));
      assertThrows(TaskFailure.class, () -> batch.scalarBytes("\ud800"));
    }
    assertEquals(base, q.scratchLive());
  }

  @Test
  void supplementaryCharactersUseColumnCodePointBoundsAndOmittedNullStaysOmitted() {
    var q = query();
    var r = row();
    r.put("tenant_id", "😀".repeat(64));
    r.remove("cleanup_receipt");
    try (var batch = new SessionHistoryMetadataBatch(q, () -> List.of(r))) {
      long actual = 0;
      int columns = 0;
      for (var value : batch.iterator().next().values()) {
        actual += batch.scalarBytes(value);
        columns++;
      }
      assertEquals(13, columns);
      assertEquals(r.values().stream().mapToLong(SessionQueryContext::scalarBytes).sum(), actual);
    }
  }
}
