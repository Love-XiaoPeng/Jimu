package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Row;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionMetadataAccountingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/** Original lazy reads, scalar/capacity boundaries and exact convergence stopping rules. */
class SessionMetadataAccountingServiceTest {
  private final SessionMetadataAccountingServiceImpl service =
      new SessionMetadataAccountingServiceImpl();

  private static final class Records implements SessionMetadataAccounting {
    final List<String> calls = new ArrayList<>();
    final EnumMap<Table, List<Row>> rows = new EnumMap<>(Table.class);
    final List<Long> writes = new ArrayList<>();
    boolean session = true;
    long stored;
    boolean converges = true;
    RuntimeException readFailure;

    public boolean session() {
      calls.add("session");
      return session;
    }

    public Iterable<? extends Row> rows(Table table) {
      calls.add(table.name());
      return rows.getOrDefault(table, List.of());
    }

    public long metadataBytes() {
      calls.add("metadata_bytes");
      if (readFailure != null) throw readFailure;
      return stored;
    }

    public void account(long bytes) {
      calls.add("update");
      writes.add(bytes);
      if (converges) stored = bytes;
    }

    void value(String value) {
      rows.put(Table.TASK, List.of(() -> List.of(value)));
    }
  }

  @Test
  void readsTablesRowsAndValuesLazilyInOriginalOrder() {
    var records = new Records();
    records.rows.put(
        Table.TASK,
        List.of(
            () -> {
              records.calls.add("values1");
              return List.of(
                  new Object() {
                    public String toString() {
                      records.calls.add("scalar1");
                      return "abc";
                    }
                  });
            },
            () -> {
              records.calls.add("values2");
              return List.of();
            }));
    assertEquals(75, service.metadata(records, true, 0));
    assertEquals(
        List.of("TASK", "values1", "scalar1", "values2", "TASK_SESSION_HISTORY"),
        records.calls.subList(0, 5));
    assertEquals("EVENT", records.calls.getLast());
  }

  @Test
  void nullUtcMicrosecondsAndUtf8UseOriginalScalarRepresentation() {
    var records = new Records();
    records.rows.put(
        Table.EVENT,
        List.of(
            () ->
                Arrays.asList(
                    null, LocalDateTime.of(2026, 10, 25, 1, 2, 3, 999999999), "🧱", 123L, false)));
    // Row32; five labels8; null1/time27/emoji4/decimal3/boolean5.
    assertEquals(112, service.metadata(records, true, 0));
  }

  @Test
  void scalarFailureStopsBeforeNextRowAndTable() {
    var records = new Records();
    var failure = new IllegalStateException("original toString");
    records.rows.put(
        Table.TASK,
        List.of(
            () ->
                List.of(
                    new Object() {
                      public String toString() {
                        throw failure;
                      }
                    }),
            () -> {
              throw new AssertionError("not reached");
            }));
    assertSame(
        failure,
        assertThrows(IllegalStateException.class, () -> service.metadata(records, false, 0)));
    assertEquals(List.of("TASK"), records.calls);
  }

  @Test
  void closingAndPlannedCapacityRejectAfterFullScanWithOriginalOverflow() {
    var records = new Records();
    records.value("a".repeat(65496));
    assertEquals(65536, service.metadata(records, true, Long.MAX_VALUE));
    records.value("a".repeat(65497));
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> service.metadata(records, true, Long.MIN_VALUE))
            .getMessage());
    records.value("a".repeat(59608));
    assertEquals(59648, service.metadata(records, false, 0));
    assertThrows(TaskFailure.class, () -> service.metadata(records, false, 1));
    assertEquals("EVENT", records.calls.getLast());
    records.value("a".repeat(61401));
    assertThrows(TaskFailure.class, () -> service.metadata(records, false, Long.MIN_VALUE));
    var empty = new Records();
    assertEquals(0, service.metadata(empty, false, Long.MAX_VALUE));
    assertThrows(TaskFailure.class, () -> service.metadata(empty, false, 60417));
  }

  @Test
  void allTableHeadroomWeightsRemainPerRow() {
    var records = new Records();
    for (Table table : Table.values()) records.rows.put(table, List.of(() -> List.of()));
    // 18*32 actual; the two v5 tables each require 512 cleanup bytes.
    assertEquals(576, service.metadata(records, false, 56000));
    assertThrows(TaskFailure.class, () -> service.metadata(records, false, 56001));
  }

  @Test
  void skipsNonSessionAndReturnsAtFirstEqualRound() {
    var records = new Records();
    records.session = false;
    service.account(records, true);
    assertEquals(List.of("session"), records.calls);
    records = new Records();
    service.account(records, false);
    assertTrue(records.writes.isEmpty());
    assertEquals(1, records.calls.stream().filter("metadata_bytes"::equals).count());
    records.value("abc");
    records.calls.clear();
    service.account(records, false);
    assertEquals(List.of(43L), records.writes);
    assertEquals(2, records.calls.stream().filter("metadata_bytes"::equals).count());
  }

  @Test
  void fourthUpdateStillCorruptWithoutFifthConfirmationAndOriginalReadFailurePropagates() {
    var records = new Records();
    records.value("abc");
    records.converges = false;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.account(records, true)).getMessage());
    assertEquals(List.of(43L, 43L, 43L, 43L), records.writes);
    assertEquals(4, records.calls.stream().filter("metadata_bytes"::equals).count());
    records.writes.clear();
    var failure = new NoSuchElementException("getFirst");
    records.readFailure = failure;
    assertSame(
        failure, assertThrows(NoSuchElementException.class, () -> service.account(records, true)));
    assertTrue(records.writes.isEmpty());
  }
}
