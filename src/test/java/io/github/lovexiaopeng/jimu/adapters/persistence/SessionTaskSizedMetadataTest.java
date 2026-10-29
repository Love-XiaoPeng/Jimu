package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;

/** 具名长度和普通Long隔离，原NULL/UTF8/数字/UTC公式及LONGTEXT边界。 */
class SessionTaskSizedMetadataTest {
  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static boolean length(SessionMetadataProjection.Column c) {
    return c.kind() == 'T' || c.kind() == 'M' || c.kind() == 'X';
  }

  private static Map<String, Object> row() {
    var row = new LinkedHashMap<String, Object>();
    for (var c : SessionMetadataProjection.taskSized().columns)
      row.put(
          c.name(),
          switch (c.kind()) {
            case 'S' -> "a";
            case 'I' -> Integer.MIN_VALUE;
            case 'L' -> Long.MIN_VALUE;
            case 'D' -> LocalDateTime.of(2026, 10, 29, 1, 2, 3, 456789000);
            case 'T', 'M', 'X' -> 0L;
            default -> throw new AssertionError();
          });
    return row;
  }

  private static class Rows implements Cursor<Map<String, Object>> {
    final List<Map<String, Object>> values;
    boolean closed;

    Rows(List<Map<String, Object>> values) {
      this.values = values;
    }

    public Iterator<Map<String, Object>> iterator() {
      return values.iterator();
    }

    public boolean isOpen() {
      return !closed;
    }

    public boolean isConsumed() {
      return closed;
    }

    public int getCurrentIndex() {
      return 0;
    }

    public void close() {
      closed = true;
    }
  }

  @Test
  void exactLengthsAreScopedToElevenColumnsWithNullAndEmptyDistinct() {
    var p = SessionMetadataProjection.taskSized();
    assertEquals(60, p.columns.size());
    assertEquals(61288, p.retainedBytes);
    assertEquals(11, p.columns.stream().filter(SessionTaskSizedMetadataTest::length).count());
    for (String raw : new String[] {"", "abc", "\0", "中文", "😀", "a  "}) {
      var q = query();
      var values = row();
      long expected = 32;
      for (var c : p.columns) {
        Object value = length(c) ? raw : values.get(c.name());
        values.put(
            c.name(),
            length(c)
                ? (long) raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                : value);
        expected += 8 + SessionQueryContext.scalarBytes(value);
      }
      var rows = new Rows(List.of(values));
      long actual = 32;
      try (var batch = new SessionCursorMetadataBatch(q, p, () -> rows)) {
        for (Object value : batch.iterator().next().values())
          actual += 8 + batch.scalarBytes(value);
        assertEquals(20, batch.scalarBytes(Long.MIN_VALUE));
        assertEquals(1, batch.scalarBytes(0L));
      }
      assertEquals(expected, actual);
      assertTrue(rows.closed);
      assertEquals(65536, q.scratchLive());
    }
    var nullable = row();
    for (var c : p.columns) if (c.nullable()) nullable.put(c.name(), null);
    try (var batch =
        new SessionCursorMetadataBatch(query(), p, () -> new Rows(List.of(nullable)))) {
      var row = batch.iterator().next();
      int count = 0;
      for (var c : p.columns)
        if (c.nullable()) {
          assertNull(row.value(c.name()));
          count++;
        }
      assertTrue(count > 0);
      assertEquals(1, batch.scalarBytes(null));
    }
  }

  @Test
  void rejectsInvalidDriverLengthsAndKeepsLongtextAboveIntegerRange() {
    var p = SessionMetadataProjection.taskSized();
    for (var c : p.columns)
      if (length(c)) {
        long maximum = c.kind() == 'T' ? 65535L : c.kind() == 'M' ? 16777215L : 4294967295L;
        for (Object value : new Object[] {-1L, maximum + 1, "12", 1.0, java.math.BigInteger.ONE}) {
          var q = query();
          var map = row();
          map.put(c.name(), value);
          var rows = new Rows(List.of(map));
          try (var batch = new SessionCursorMetadataBatch(q, p, () -> rows)) {
            assertThrows(RuntimeException.class, () -> batch.iterator().next());
          }
          assertTrue(rows.closed);
          assertEquals(65536, q.scratchLive());
        }
        for (long value : new long[] {maximum - 1, maximum, 0}) {
          var map = row();
          map.put(c.name(), value);
          try (var batch =
              new SessionCursorMetadataBatch(query(), p, () -> new Rows(List.of(map)))) {
            assertEquals(value, batch.scalarBytes(batch.iterator().next().value(c.name())));
          }
        }
      }
  }

  @Test
  void reservesBeforeOpeningAndRejectsExtraTaskRowWithoutTruncation() {
    var q = query();
    Object pressure = new Object();
    q.retainScratch(pressure, 1048576 - q.scratchLive() - 61288 + 1);
    boolean[] loaded = {false};
    assertThrows(
        RuntimeException.class,
        () ->
            new SessionCursorMetadataBatch(
                q,
                SessionMetadataProjection.taskSized(),
                () -> {
                  loaded[0] = true;
                  return new Rows(List.of(row()));
                }));
    assertFalse(loaded[0]);
    q.releaseScratch(pressure);
    assertEquals(65536, q.scratchLive());
    var rows = new Rows(List.of(row(), row()));
    try (var batch =
        new SessionCursorMetadataBatch(
            query(), SessionMetadataProjection.taskSized(), () -> rows)) {
      var it = batch.iterator();
      it.next();
      assertThrows(RuntimeException.class, it::hasNext);
    }
    assertTrue(rows.closed);
  }
}
