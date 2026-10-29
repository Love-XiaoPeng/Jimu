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
    assertThrows(IllegalStateException.class, first::values);
    assertThrows(IllegalStateException.class, values::hasNext);
    var second = iterator.next();
    assertEquals(r.get("tenant_id"), second.value("tenant_id"));
    iterator.next();
    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
    batch.close();
    batch.close();
    assertThrows(IllegalStateException.class, second::values);
    assertTrue(rows.closed);
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void emptyAndTrueBusinessLimitsNeverTruncateOrInventOtherRowLimits() {
    for (var table :
        List.of(Table.TASK_SESSION_SCOPE_TARGET, Table.SESSION_PUBLIC_EVENT, Table.EVENT)) {
      var p = SessionMetadataProjection.forTable(table);
      int limit = p.maximumRows;
      for (int count : new int[] {0, limit > 0 ? limit : 129, limit > 0 ? limit + 1 : 130}) {
        var q = query();
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
      q.retainScratch(proof, 1048576 - q.scratchLive() - p.retainedBytes - 1024 - delta);
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
}
