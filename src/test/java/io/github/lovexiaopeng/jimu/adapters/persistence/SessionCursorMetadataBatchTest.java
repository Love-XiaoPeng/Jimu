package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;

class SessionCursorMetadataBatchTest {
  @Test
  void exactMembershipNeedsNoSecondTraversalOfDriverLabels() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var traversals = new java.util.concurrent.atomic.AtomicInteger();
    var values =
        new LinkedHashMap<String, Object>(row(p)) {
          @Override
          public java.util.Set<String> keySet() {
            traversals.incrementAndGet();
            return super.keySet();
          }
        };
    var q = query();
    try (var batch = new SessionCursorMetadataBatch(q, p, () -> new Rows(List.of(values)))) {
      batch.iterator().next();
      System.out.println(
          "CURSOR_MEMBERSHIP operations="
              + q.resources().operations()
              + " labelTraversals="
              + traversals.get());
      assertEquals(0, traversals.get());
    }
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void exactMembershipRejectsExtraKeysEvenWhenReplacingNullableMissingColumns() {
    int checked = 0;
    for (var table : Table.values()) {
      var p = SessionMetadataProjection.forTable(table);
      if (p == null) continue;
      assertEquals(p.columns.size(), p.names.size());
      for (String mode : List.of("extra", "missing", "nullable-substitution", "wrong-type")) {
        var values = row(p);
        switch (mode) {
          case "extra" -> values.put("unknown_column", "x");
          case "missing" ->
              values.remove(
                  p.columns.stream().filter(c -> !c.nullable()).findFirst().orElseThrow().name());
          case "nullable-substitution" -> {
            var nullable =
                p.columns.stream().filter(SessionMetadataProjection.Column::nullable).findFirst();
            if (nullable.isEmpty()) continue;
            int size = values.size();
            values.remove(nullable.get().name());
            values.put("unknown_column", null);
            assertEquals(size, values.size());
          }
          case "wrong-type" -> values.put("tenant_id", 1);
          default -> throw new AssertionError();
        }
        var q = query();
        var cursor = new Rows(List.of(values));
        try (var batch = new SessionCursorMetadataBatch(q, p, () -> cursor)) {
          assertThrows(TaskFailure.class, () -> batch.iterator().hasNext(), table + " " + mode);
        }
        assertTrue(cursor.closed);
        assertEquals(65536, q.scratchLive());
        checked++;
      }
    }
    assertTrue(checked >= 45);
    System.out.println("CURSOR_MEMBERSHIP malformedSamples=" + checked);
  }

  @Test
  void membershipBudgetBoundaryPreservesFirstFailureAndReleasesExactlyItsOwners() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var baseline = query();
    try (var batch = new SessionCursorMetadataBatch(baseline, p, () -> new Rows(List.of(row(p))))) {
      batch.iterator().next();
    }
    long cost = baseline.resources().operations();
    for (long free : new long[] {cost - 1, cost, cost + 1}) {
      var q = query();
      q.operation(65536 - free);
      Object unrelated = new Object();
      q.retainScratch(unrelated, 100);
      var cursor = new Rows(List.of(row(p)));
      try (var batch = new SessionCursorMetadataBatch(q, p, () -> cursor)) {
        if (free < cost) {
          var first = assertThrows(TaskFailure.class, () -> batch.iterator().next());
          org.junit.jupiter.api.Assertions.assertSame(first, q.recordedFailure());
          org.junit.jupiter.api.Assertions.assertSame(
              first, assertThrows(TaskFailure.class, () -> q.operation(1)));
        } else batch.iterator().next();
      }
      assertTrue(cursor.closed);
      assertEquals(65636, q.scratchLive());
      q.releaseScratch(unrelated);
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void borrowedValuesPreserveNullPresenceAcrossRowsAndDropAllOwnedReferences() throws Exception {
    for (var table : Table.values()) {
      var p = SessionMetadataProjection.forTable(table);
      if (p == null || p.maximumRows == 1) continue;
      var full = row(p);
      var explicit = row(p);
      var missing = row(p);
      for (var c : p.columns)
        if (c.nullable()) {
          explicit.put(c.name(), null);
          missing.remove(c.name());
        }
      var q = query();
      var batch =
          new SessionCursorMetadataBatch(q, p, () -> new Rows(List.of(full, explicit, missing)));
      var iterator = batch.iterator();
      for (var expected : List.of(full, explicit, missing)) {
        var actual = new java.util.ArrayList<Object>();
        for (var value : iterator.next().values()) actual.add(value);
        assertEquals(new java.util.ArrayList<>(expected.values()), actual, table.name());
      }
      assertFalse(iterator.hasNext());
      batch.close();
      for (String name : List.of("current", "slots", "source", "cursor")) {
        var field = SessionCursorMetadataBatch.class.getDeclaredField(name);
        field.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(field.get(batch), name);
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void borrowedArrayReservationRejectsBeforeOpeningAtTheExactScratchBoundary() {
    var p = SessionMetadataProjection.taskSized();
    for (int delta : List.of(-1, 0, 1)) {
      var q = query();
      Object pressure = new Object();
      q.retainScratch(pressure, 1048576 - 65536 - p.retainedBytes - 1216 - delta);
      long start = q.scratchLive();
      var loaded = new AtomicBoolean();
      java.util.function.Supplier<Cursor<Map<String, Object>>> load =
          () -> {
            loaded.set(true);
            return new Rows(List.of());
          };
      if (delta < 0)
        assertThrows(TaskFailure.class, () -> new SessionCursorMetadataBatch(q, p, load));
      else try (var batch = new SessionCursorMetadataBatch(q, p, load)) {}
      assertEquals(delta >= 0, loaded.get());
      assertEquals(start, q.scratchLive());
      q.releaseScratch(pressure);
      assertEquals(65536, q.scratchLive());
    }
  }

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private Map<String, Object> row(SessionMetadataProjection projection) {
    var result = new LinkedHashMap<String, Object>();
    for (var c : projection.columns)
      result.put(
          c.name(),
          switch (c.kind()) {
            case 'S' -> "字😀".codePointCount(0, 3) <= c.points() ? "字😀" : "a";
            case 'I' -> Integer.MIN_VALUE;
            case 'L' -> Long.MIN_VALUE;
            case 'B' -> true;
            case 'D' -> LocalDateTime.of(2026, 10, 29, 12, 34, 56, 123456000);
            default -> throw new AssertionError();
          });
    return result;
  }

  private static class Rows implements Cursor<Map<String, Object>> {
    private final Iterator<Map<String, Object>> rows;
    boolean closed, throwClose;
    int fetched;

    Rows(List<Map<String, Object>> rows) {
      this.rows = rows.iterator();
    }

    public boolean isOpen() {
      return !closed;
    }

    public boolean isConsumed() {
      return !rows.hasNext();
    }

    public int getCurrentIndex() {
      return fetched - 1;
    }

    public Iterator<Map<String, Object>> iterator() {
      return new Iterator<>() {
        public boolean hasNext() {
          if (closed) throw new IllegalStateException("closed");
          return rows.hasNext();
        }

        public Map<String, Object> next() {
          fetched++;
          return rows.next();
        }
      };
    }

    public void close() throws IOException {
      closed = true;
      if (throwClose) throw new IOException("close-marker");
    }
  }

  @Test
  void allFifteenSchemasMatchOriginalSelectAndScalarFormula() throws Exception {
    String xml =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/resources/mapper/session/SessionHistoryRetentionMapper.xml"));
    int schemas = 0;
    for (var table : Table.values()) {
      var p = SessionMetadataProjection.forTable(table);
      if (p == null) continue;
      schemas++;
      String operation =
          "metadata"
              + java.util.Arrays.stream(table.name().toLowerCase(java.util.Locale.ROOT).split("_"))
                  .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                  .collect(java.util.stream.Collectors.joining());
      var match =
          java.util.regex.Pattern.compile(
                  "<select id=\""
                      + operation
                      + "\"[^>]*>\\s*(?:<!\\[CDATA\\[)?SELECT (.*?)\\s+FROM ",
                  java.util.regex.Pattern.DOTALL)
              .matcher(xml);
      assertTrue(match.find());
      assertEquals(
          java.util.Arrays.stream(match.group(1).split(",")).map(String::trim).toList(),
          p.columns.stream().map(SessionMetadataProjection.Column::name).toList());
      var q = query();
      var values = row(p);
      var rows = new Rows(List.of(values));
      try (var batch = new SessionCursorMetadataBatch(q, p, () -> rows)) {
        var r = batch.iterator().next();
        long expected = values.values().stream().mapToLong(SessionQueryContext::scalarBytes).sum(),
            actual = 0;
        for (var v : r.values()) actual += batch.scalarBytes(v);
        assertEquals(expected, actual, table.name());
        assertEquals(4, batch.scalarBytes(true));
        assertEquals(5, batch.scalarBytes(false));
        assertEquals(20, batch.scalarBytes(Long.MIN_VALUE));
      }
      assertTrue(rows.closed);
      assertEquals(65536, q.scratchLive());
      // Nullable missing columns and explicit NULL remain distinct in values().
      for (var c : p.columns) if (c.nullable()) values.remove(c.name());
      try (var batch = new SessionCursorMetadataBatch(q, p, () -> new Rows(List.of(values)))) {
        int count = 0;
        for (var v : batch.iterator().next().values()) count++;
        assertEquals(values.size(), count);
      }
    }
    assertEquals(15, schemas);
  }

  @Test
  void forwardOnlyFacadeCannotRetainPreviousMapOrSurviveClosure() {
    var q = query();
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    var r = row(p);
    var rows = new Rows(java.util.Collections.nCopies(3, r));
    var batch = new SessionCursorMetadataBatch(q, p, () -> rows);
    var iterator = batch.iterator();
    var first = iterator.next();
    var values = first.values().iterator();
    assertTrue(iterator.hasNext());
    assertEquals(2, rows.fetched);
    var second = iterator.next();
    assertEquals(r.get("tenant_id"), second.value("tenant_id"));
    iterator.next();
    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
    assertThrows(IllegalStateException.class, first::values);
    assertThrows(IllegalStateException.class, values::hasNext);
    batch.close();
    batch.close();
    assertThrows(IllegalStateException.class, second::values);
    assertTrue(rows.closed);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void everyBorrowedGetterClosesOnCaughtBudgetFailureAndPreservesFirstFailure() {
    for (String entry : List.of("value", "hasNext", "next", "scalar", "lazy")) {
      var q = query();
      var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
      var rows = new Rows(List.of(row(p)));
      rows.throwClose = true;
      var batch = new SessionCursorMetadataBatch(q, p, () -> rows);
      var iterator = batch.iterator();
      var current = entry.equals("lazy") ? null : iterator.next();
      var values = current == null ? null : current.values().iterator();
      q.operation(65536 - q.resources().operations());
      var failure =
          assertThrows(
              TaskFailure.class,
              () -> {
                switch (entry) {
                  case "value" -> current.value("tenant_id");
                  case "hasNext" -> values.hasNext();
                  case "next" -> values.next();
                  case "scalar" -> batch.scalarBytes("x");
                  case "lazy" -> iterator.hasNext();
                  default -> throw new AssertionError();
                }
              });
      assertTrue(rows.closed, entry);
      assertEquals(1, failure.getSuppressed().length, entry);
      org.junit.jupiter.api.Assertions.assertSame(
          failure, assertThrows(TaskFailure.class, q::checkHealthy));
      assertEquals(65536, q.scratchLive());
      batch.close();
    }
  }

  @Test
  void emptyAndTrueBusinessLimitsNeverTruncateOrInventOtherRowLimits() {
    for (var table :
        List.of(Table.TASK_SESSION_SCOPE_TARGET, Table.SESSION_PUBLIC_EVENT, Table.EVENT)) {
      var p = SessionMetadataProjection.forTable(table);
      int limit = p.maximumRows;
      for (int count : new int[] {0, limit > 0 ? limit : 129, limit > 0 ? limit + 1 : 130}) {
        // 单独核业务行数上限；大行累计操作可能先触发独立预算，不改变生产限额。
        var q = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
        q.restrictLineage();
        var rows = new Rows(java.util.Collections.nCopies(count, row(p)));
        Runnable scan =
            () -> {
              try (var batch = new SessionCursorMetadataBatch(q, p, () -> rows)) {
                for (var r : batch) r.value("tenant_id");
              }
            };
        if (limit > 0 && count > limit) assertThrows(RuntimeException.class, scan::run);
        else scan.run();
        assertTrue(rows.closed);
        assertEquals(65536, q.scratchLive());
      }
    }
  }

  @Test
  void memoryBoundaryIncludesCursorAndProofAndRejectsBeforeLoad() {
    var p = SessionMetadataProjection.forTable(Table.RESERVATION);
    for (int delta : List.of(-1, 0, 1)) {
      var q = query();
      Object proof = new Object();
      q.retainScratch(
          proof,
          1048576
              - q.scratchLive()
              - p.retainedBytes
              - (256L + 16L * p.columns.size())
              - 1024
              - delta);
      long start = q.scratchLive();
      var called = new AtomicBoolean();
      java.util.function.Supplier<Cursor<Map<String, Object>>> load =
          () -> {
            called.set(true);
            q.retainScratch("cursor", 1024);
            return new Rows(List.of());
          };
      if (delta < 0)
        assertThrows(TaskFailure.class, () -> new SessionCursorMetadataBatch(q, p, load));
      else
        try (var batch = new SessionCursorMetadataBatch(q, p, load)) {
          assertFalse(batch.iterator().hasNext());
        }
      q.releaseScratch("cursor");
      assertTrue(called.get());
      assertEquals(start, q.scratchLive());
    }
    var q = query();
    q.retainScratch("proof", 1048576 - q.scratchLive() - p.retainedBytes + 1);
    var called = new AtomicBoolean();
    assertThrows(
        TaskFailure.class,
        () ->
            new SessionCursorMetadataBatch(
                q,
                p,
                () -> {
                  called.set(true);
                  return new Rows(List.of());
                }));
    assertFalse(called.get());
  }

  @Test
  void consumerLoadDecodeAndCloseFailuresKeepPrimaryAndReleaseReferences() {
    var q = query();
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    assertThrows(
        IllegalStateException.class,
        () ->
            new SessionCursorMetadataBatch(
                q,
                p,
                () -> {
                  throw new IllegalStateException("load");
                }));
    assertEquals(65536, q.scratchLive());
    var rows = new Rows(List.of(row(p)));
    rows.throwClose = true;
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              try (var batch = new SessionCursorMetadataBatch(q, p, () -> rows)) {
                batch.iterator().next();
                throw new IllegalArgumentException("consumer");
              }
            });
    assertEquals("consumer", error.getMessage());
    assertEquals(1, error.getSuppressed().length);
    assertTrue(rows.closed);
    assertEquals(65536, q.scratchLive());
    var invalid = row(p);
    invalid.put("tenant_id", "x".repeat(129));
    var bad = new Rows(List.of(invalid));
    assertThrows(
        RuntimeException.class,
        () -> {
          try (var batch = new SessionCursorMetadataBatch(q, p, () -> bad)) {
            batch.iterator().hasNext();
          }
        });
    assertTrue(bad.closed);
    assertEquals(65536, q.scratchLive());
    assertThrows(RuntimeException.class, () -> SessionMetadataScalars.bytes("\ud800", q));
  }

  @Test
  void finalFoldMatchesEveryOrdinaryProjectionWithoutRetainingSlotsOrRows() {
    for (var table : Table.values()) {
      var p = SessionMetadataProjection.forTable(table);
      if (p == null) continue;
      for (String mode : List.of("full", "null", "absent", "wide")) {
        var values = row(p);
        if (mode.equals("null"))
          for (var c : p.columns) if (c.nullable()) values.put(c.name(), null);
        if (mode.equals("absent"))
          for (var c : p.columns) if (c.nullable()) values.remove(c.name());
        if (mode.equals("wide")) {
          for (var c : p.columns) {
            if (c.kind() == 'L') values.put(c.name(), Long.MIN_VALUE);
            if (c.kind() == 'I') values.put(c.name(), Integer.MIN_VALUE);
            if (c.kind() == 'D') values.put(c.name(), LocalDateTime.of(-1, 1, 1, 0, 0));
            if (c.kind() == 'S' && c.points() >= 2) values.put(c.name(), "🧱");
          }
        }
        long expected = 32;
        var old = query();
        try (var batch =
            new SessionCursorMetadataBatch(
                old, p, () -> new Rows(List.of(new LinkedHashMap<>(values))))) {
          for (var value : batch.iterator().next().values())
            expected += 8 + batch.scalarBytes(value);
        }
        var q = query();
        var cursor = new Rows(List.of(values));
        var result = SessionCursorMetadataBatch.measureFinal(q, table, p, () -> cursor);
        assertEquals(expected, result.bytes(), table + ":" + mode);
        assertEquals(1, result.rows());
        assertTrue(cursor.closed);
        assertEquals(65536, q.scratchLive());
      }
    }
  }

  @Test
  void finalFoldRejectsMalformedRowsAndPreservesErrorWithCloseSuppressed() {
    var p = SessionMetadataProjection.forTable(Table.TASK_ATTEMPT);
    for (String mode : List.of("extra", "missing", "type", "surrogate")) {
      var values = row(p);
      switch (mode) {
        case "extra" -> values.put("unknown", null);
        case "missing" -> values.remove("tenant_id");
        case "type" -> values.put("fence", 1);
        default -> values.put("tenant_id", "\ud800");
      }
      var q = query();
      var cursor = new Rows(List.of(values));
      assertThrows(
          RuntimeException.class,
          () -> SessionCursorMetadataBatch.measureFinal(q, Table.TASK_ATTEMPT, p, () -> cursor));
      assertTrue(cursor.closed);
      assertEquals(65536, q.scratchLive());
    }
    var error = new AssertionError("final fold");
    var values =
        new LinkedHashMap<String, Object>(row(p)) {
          @Override
          public Object get(Object key) {
            throw error;
          }
        };
    var cursor = new Rows(List.of(values));
    cursor.throwClose = true;
    var q = query();
    org.junit.jupiter.api.Assertions.assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () -> SessionCursorMetadataBatch.measureFinal(q, Table.TASK_ATTEMPT, p, () -> cursor)));
    assertEquals(1, error.getSuppressed().length);
    assertEquals(65536, q.scratchLive());
  }
}
