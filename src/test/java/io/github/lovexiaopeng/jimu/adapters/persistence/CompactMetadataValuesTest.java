package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;

/** 紧凑前缀的NULL/缺列、两轮消费、首错回滚与分配前预留边界。 */
class CompactMetadataValuesTest {
  private SessionQueryContext query(long used) {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, used));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row(SessionMetadataProjection p, int variation) {
    var values = new LinkedHashMap<String, Object>();
    int i = 0;
    for (var c : p.columns) {
      int mode = i++ % 3;
      if (variation == 1 && c.nullable() && mode == 0) continue;
      Object v =
          variation == 1 && c.nullable() && mode == 1
              ? null
              : switch (c.kind()) {
                case 'S' -> c.points() >= 2 ? "\u5b57\ud83d\ude00" : "x";
                case 'I' -> 7;
                case 'L' -> -27L;
                case 'B' -> true;
                case 'D' -> LocalDateTime.of(2026, 10, 30, 1, 2, 3, 456789000);
                case 'T', 'M', 'X' -> 27L;
                default -> throw new AssertionError(c.kind());
              };
      values.put(c.name(), v);
    }
    return values;
  }

  private long expected(SessionMetadataProjection p, Map<String, Object> values) {
    long result = 0;
    for (var c : p.columns)
      if (values.containsKey(c.name())) {
        var v = values.get(c.name());
        result +=
            v != null && (c.kind() == 'T' || c.kind() == 'M' || c.kind() == 'X')
                ? ((Number) v).longValue()
                : SessionQueryContext.scalarBytes(v);
      }
    return result;
  }

  @Test
  void allSchemasAndTaskSizedTwoPassesKeepExplicitNullAndMissingDistinct() {
    var schemas = new ArrayList<SessionMetadataProjection>();
    for (var t : Table.values()) {
      var p = SessionMetadataProjection.forTable(t);
      if (p != null) schemas.add(p);
    }
    schemas.add(SessionMetadataProjection.taskSized());
    for (var p : schemas)
      for (int variation : new int[] {0, 1}) {
        var values = row(p, variation);
        long expected = expected(p, values);
        int size = values.size();
        var q = query(0);
        var source = new Rows(List.of(values));
        try (var batch = new SessionCursorMetadataBatch(q, p, () -> source)) {
          var r = batch.iterator().next();
          Iterator<?> last = null;
          for (int pass = 0; pass < 2; pass++) {
            long bytes = 0;
            int seen = 0;
            var it = r.values().iterator();
            while (it.hasNext()) {
              assertTrue(it.hasNext());
              bytes += batch.scalarBytes(it.next());
              seen++;
            }
            assertEquals(size, seen);
            assertEquals(expected, bytes);
            last = it;
          }
          assertEquals(
              2, batch.scalarBytes(27L)); // Foreign Long never acquires SQL-length semantics.
          assertThrows(NoSuchElementException.class, last::next);
        }
        assertTrue(source.closed);
        assertEquals(65536, q.scratchLive());
      }
    System.out.println("COMPACT_SCHEMA cases=" + schemas.size() * 2);
  }

  private long consume(
      SessionQueryContext q, SessionMetadataProjection p, Map<String, Object> values) {
    long before = q.resources().operations();
    try (var batch = new SessionCursorMetadataBatch(q, p, () -> new Rows(List.of(values)))) {
      var r = batch.iterator().next();
      for (int pass = 0; pass < 2; pass++) for (var v : r.values()) batch.scalarBytes(v);
    }
    return q.resources().operations() - before;
  }

  @Test
  void fullTwoPassOperationBoundaryNMinusOneNAndNPlusOne() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    long needed = consume(query(0), p, row(p, 0));
    for (long free : new long[] {needed - 1, needed, needed + 1}) {
      var q = query(65536 - free);
      Object other = new Object();
      q.retainScratch(other, 77);
      if (free < needed) {
        var first = assertThrows(TaskFailure.class, () -> consume(q, p, row(p, 0)));
        assertSame(first, q.recordedFailure());
        assertSame(first, assertThrows(TaskFailure.class, () -> q.operation(1)));
      } else assertEquals(needed, consume(q, p, row(p, 0)));
      assertEquals(65613, q.scratchLive());
      q.releaseScratch(other);
      assertEquals(65536, q.scratchLive());
    }
    System.out.println("COMPACT_COST schema=TASK_ATTEMPT fullTwoPass=" + needed);
  }

  @Test
  void valuesRemainFreshAcrossRowsAndExpiredFacadeCannotReadCompactArray() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var a = row(p, 1);
    var b = row(p, 0);
    b.put("fence", 123456789L);
    var q = query(0);
    var source = new Rows(List.of(a, b));
    try (var batch = new SessionCursorMetadataBatch(q, p, () -> source)) {
      var it = batch.iterator();
      var first = it.next();
      var firstValues = first.values().iterator();
      firstValues.next();
      var second = it.next();
      assertEquals(123456789L, second.value("fence"));
      long bytes = 0;
      for (var v : second.values()) bytes += batch.scalarBytes(v);
      assertEquals(expected(p, b), bytes);
      assertThrows(IllegalStateException.class, firstValues::hasNext);
      assertTrue(source.closed); // Failure closes source and clears slots before refund.
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void compoundedUnknownAndWrongTypeRejectsTypeBeforeFinalCardinalityRead() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    int[] sizeCalls = {0};
    var values =
        new LinkedHashMap<String, Object>(row(p, 0)) {
          @Override
          public int size() {
            sizeCalls[0]++;
            return super.size();
          }
        };
    values.put("unknown_column", null);
    values.put("tenant_id", 7);
    var q = query(0);
    var source = new Rows(List.of(values));
    source.closeFailure = new IOException("close-secondary");
    var batch = new SessionCursorMetadataBatch(q, p, () -> source);
    var first = assertThrows(TaskFailure.class, () -> batch.iterator().next());
    assertEquals(0, sizeCalls[0]);
    assertEquals(1, first.getSuppressed().length);
    assertEquals("Metadata cursor close failed", first.getSuppressed()[0].getMessage());
    assertTrue(source.closed);
    assertEquals(65536, q.scratchLive());
    batch.close();
    assertEquals(1, source.closeCalls);
  }

  @Test
  void realWorkKeepsCompoundCorruptionBeforeLaterBudgetAndRollsBack() {
    var f =
        new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest
            .Fixture();
    var q = query(0);
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var values = row(p, 0);
    values.put("unknown_column", null);
    values.put("tenant_id", 7);
    var source = new Rows(List.of(values));
    source.closeFailure = new IOException("close-secondary");
    TaskFailure[] primary = {null};
    var caught =
        assertThrows(
            TaskFailure.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      var batch = new SessionCursorMetadataBatch(q, p, () -> source);
                      primary[0] = assertThrows(TaskFailure.class, () -> batch.iterator().next());
                      assertSame(primary[0], q.recordedFailure());
                      assertSame(
                          primary[0], assertThrows(TaskFailure.class, () -> q.operation(65536)));
                      assertEquals(1, primary[0].getSuppressed().length);
                      assertEquals(
                          "Metadata cursor close failed",
                          primary[0].getSuppressed()[0].getMessage());
                      assertEquals(
                          "close-secondary", primary[0].getSuppressed()[0].getCause().getMessage());
                      batch.close();
                      return "caught";
                    }));
    assertSame(primary[0], caught);
    assertEquals(409, caught.status());
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertTrue(source.closed);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void realWorkBudgetBeforeCompoundCorruptionKeepsBudgetFirstAndRollsBack() {
    var f =
        new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest
            .Fixture();
    var q = query(0);
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var values = row(p, 0);
    values.put("unknown_column", null);
    values.put("tenant_id", 7);
    var source = new Rows(List.of(values));
    TaskFailure[] primary = {null};
    var caught =
        assertThrows(
            TaskFailure.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      try (var batch = new SessionCursorMetadataBatch(q, p, () -> source)) {
                        q.operation(65536 - q.resources().operations());
                        primary[0] = assertThrows(TaskFailure.class, () -> batch.iterator().next());
                        assertSame(primary[0], q.recordedFailure());
                      }
                      return "caught";
                    }));
    assertSame(primary[0], caught);
    assertEquals(429, caught.status());
    assertEquals(0, f.commits);
    assertEquals(1, f.rollbacks);
    assertTrue(source.closed);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void unknownSubstitutingMissingNullableStillFailsFinalCardinality() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var values = row(p, 0);
    values.remove("ended_at");
    values.put("unknown_column", null);
    var q = query(0);
    var source = new Rows(List.of(values));
    try (var batch = new SessionCursorMetadataBatch(q, p, () -> source)) {
      assertThrows(TaskFailure.class, () -> batch.iterator().next());
    }
    assertTrue(source.closed);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void existingFirstFailureAndLoaderErrorNeverLeaveAdditionalOwners() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var q = query(0);
    var first = q.recordFailure(new TaskFailure(409, "FIRST"));
    boolean[] loaded = {false};
    assertSame(
        first,
        assertThrows(
            TaskFailure.class,
            () ->
                new SessionCursorMetadataBatch(
                    q,
                    p,
                    () -> {
                      loaded[0] = true;
                      return null;
                    })));
    assertFalse(loaded[0]);
    assertEquals(65536, q.scratchLive());
    var healthy = query(0);
    Error error = new AssertionError("load-error");
    assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () ->
                new SessionCursorMetadataBatch(
                    healthy,
                    p,
                    () -> {
                      throw error;
                    })));
    assertEquals(65536, healthy.scratchLive());
  }

  @Test
  void supplierReservationBoundaryPrecedesAnyRowAllocationAndIsNeverShrunk() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    long needed = p.retainedBytes + 256 + 16L * p.columns.size();
    for (int delta : new int[] {-1, 0, 1}) {
      var q = query(0);
      Object other = new Object();
      q.retainScratch(other, 1048576 - 65536 - needed - delta);
      long start = q.scratchLive();
      boolean[] loaded = {false};
      java.util.function.Supplier<Cursor<Map<String, Object>>> supplier =
          () -> {
            loaded[0] = true;
            return new Rows(List.of(row(p, 1)));
          };
      if (delta < 0)
        assertThrows(TaskFailure.class, () -> new SessionCursorMetadataBatch(q, p, supplier));
      else
        try (var batch = new SessionCursorMetadataBatch(q, p, supplier)) {
          batch.iterator().next();
          assertEquals(start + needed, q.scratchLive());
        }
      assertEquals(delta >= 0, loaded[0]);
      assertEquals(start, q.scratchLive());
      q.releaseScratch(other);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void noQueryPathKeepsTwoPassBehaviorAndClosedRowsFail() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var values = row(p, 1);
    var source = new Rows(List.of(values));
    var batch = new SessionCursorMetadataBatch(null, p, () -> source);
    var r = batch.iterator().next();
    for (int i = 0; i < 2; i++) {
      long size = 0;
      for (var v : r.values()) size += batch.scalarBytes(v);
      assertEquals(expected(p, values), size);
    }
    batch.close();
    assertThrows(IllegalStateException.class, r::values);
    assertTrue(source.closed);
  }

  private static class Rows implements Cursor<Map<String, Object>> {
    private final Iterator<Map<String, Object>> it;
    boolean closed;
    int fetched, closeCalls;
    IOException closeFailure;

    Rows(List<Map<String, Object>> values) {
      it = values.iterator();
    }

    public boolean isOpen() {
      return !closed;
    }

    public boolean isConsumed() {
      return !it.hasNext();
    }

    public int getCurrentIndex() {
      return fetched - 1;
    }

    public Iterator<Map<String, Object>> iterator() {
      return new Iterator<>() {
        public boolean hasNext() {
          if (closed) throw new IllegalStateException("closed");
          return it.hasNext();
        }

        public Map<String, Object> next() {
          fetched++;
          return it.next();
        }
      };
    }

    public void close() throws IOException {
      closed = true;
      closeCalls++;
      if (closeFailure != null) throw closeFailure;
    }
  }
}
