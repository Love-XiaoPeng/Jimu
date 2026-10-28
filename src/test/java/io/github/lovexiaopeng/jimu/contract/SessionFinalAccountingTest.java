package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionMetadataAccountingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 同一字段快照对照原同步算法；含NULL、UTF8、微秒、十进位及读回故障。 */
class SessionFinalAccountingTest {
  private final SessionMetadataAccountingServiceImpl service =
      new SessionMetadataAccountingServiceImpl();

  static final class Records implements SessionMetadataAccounting {
    final EnumMap<Table, List<Map<String, Object>>> tables = new EnumMap<>(Table.class);
    final EnumMap<Table, Integer> scans = new EnumMap<>(Table.class);
    final Map<String, Object> history = new LinkedHashMap<>();
    boolean optimized = true, badRead, purged;
    int updates, readbacks;

    Records(long old, int padding) {
      history.put("metadata_bytes", old);
      history.put("body_bytes", 113L);
      history.put("reserved_bytes", 8388608L);
      history.put("held_bytes", 8388608L);
      history.put("content_state", "PRESENT");
      history.put("cleanup_receipt", null);
      history.put("retain_until", LocalDateTime.of(2026, 10, 28, 1, 2, 3, 123456000));
      tables.put(Table.TASK_SESSION_HISTORY, List.of(history));
      var reservation = new LinkedHashMap<String, Object>();
      reservation.put("category", "SESSION_HISTORY_STORAGE");
      reservation.put("amount", 8388608L);
      reservation.put("actual", null);
      reservation.put("state", "RESERVED");
      tables.put(Table.RESERVATION, List.of(reservation));
      tables.put(
          Table.EVENT,
          List.of(new LinkedHashMap<>(Map.of("utf8", "积木🧱", "padding", "x".repeat(padding)))));
    }

    public boolean session() {
      return true;
    }

    public boolean finalPresent() {
      return optimized;
    }

    public Iterable<? extends Row> rows(Table table) {
      scans.merge(table, 1, Integer::sum);
      return tables.getOrDefault(table, List.of()).stream()
          .map(
              map ->
                  new Row() {
                    public Iterable<?> values() {
                      return map.values();
                    }

                    public Object value(String name) {
                      return map.get(name);
                    }
                  })
          .toList();
    }

    public long metadataBytes() {
      return ((Number) history.get("metadata_bytes")).longValue();
    }

    public void account(long bytes) {
      updates++;
      history.put("metadata_bytes", bytes);
    }

    public Balance readBack() {
      readbacks++;
      return new Balance(
          metadataBytes() + (badRead ? 1 : 0),
          113,
          8388608,
          8388608,
          8388608,
          null,
          "RESERVED",
          purged ? "PURGED" : "PRESENT");
    }
  }

  @Test
  void finalSnapshotMatchesOriginalAlgorithmAcrossDecimalBoundaries() {
    for (long old : List.of(9L, 99L, 9999L, 65536L)) {
      for (int padding : List.of(0, 450, 900, 9450, 9950, 58000)) {
        var finalRows = new Records(old, padding);
        var synchronous = new Records(old, padding);
        synchronous.optimized = false;
        service.account(synchronous, true);
        service.account(finalRows, true);
        assertEquals(synchronous.metadataBytes(), finalRows.metadataBytes());
        assertEquals(service.metadata(finalRows, true, 0), finalRows.metadataBytes());
        assertEquals(1, finalRows.updates);
        assertEquals(1, finalRows.readbacks);
        for (var count : finalRows.scans.values())
          assertEquals(2, count); // final snapshot + independent recount
      }
    }
  }

  @Test
  void unchangedValueNeedsNoUpdateButStillChecksOneExactReadback() {
    var records = new Records(0, 0);
    service.account(records, true);
    records.updates = 0;
    records.readbacks = 0;
    records.scans.clear();
    service.account(records, true);
    assertEquals(0, records.updates);
    assertEquals(1, records.readbacks);
    for (var table : SessionMetadataAccounting.Table.values())
      assertEquals(1, records.scans.get(table));
  }

  @Test
  void limitOrReadbackMismatchCannotReportSettlement() {
    var limit = new Records(9, 65536);
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> service.account(limit, true)).code());
    assertEquals(0, limit.updates);
    assertEquals(0, limit.readbacks);
    for (boolean purged : List.of(false, true)) {
      var bad = new Records(9, 0);
      bad.badRead = !purged;
      bad.purged = purged;
      assertEquals(
          "SESSION_HISTORY_CORRUPT",
          assertThrows(TaskFailure.class, () -> service.account(bad, true)).code());
    }
  }
}
