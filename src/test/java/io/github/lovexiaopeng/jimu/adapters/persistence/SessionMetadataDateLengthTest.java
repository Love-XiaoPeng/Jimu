package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SessionMetadataDateLengthTest {
  private static final String PATTERN = "uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'";

  private SessionQueryContext query(long operations) {
    var query =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, operations));
    query.restrictLineage();
    return query;
  }

  private long scalar(boolean history, Object value, SessionQueryContext query) {
    if (!history) return SessionMetadataScalars.bytes(value, query);
    try (var batch = new SessionHistoryMetadataBatch(query, List::of)) {
      return batch.scalarBytes(value);
    }
  }

  @Test
  void frozenFormatterEquivalenceAcrossYearsLeapDayAndFractionTruncation() {
    for (boolean history : List.of(false, true)) {
      for (int year : List.of(0, 1, 2026, 9999, 10000, -1, -999999999, 999999999)) {
        for (int nanos : List.of(0, 1, 999, 1000, 999999000, 999999999)) {
          for (int hour : List.of(0, 23)) {
            var time = LocalDateTime.of(year, 12, 31, hour, 59, 59, nanos);
            var query = query(0);
            long expected =
                DateTimeFormatter.ofPattern(PATTERN, Locale.ROOT)
                    .format(time)
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
            assertEquals(expected, scalar(history, time, query), time.toString());
            assertEquals(
                year >= 0 && year <= 9999 ? 7 : 7 + 27 + expected, query.resources().operations());
            assertEquals(65536, query.scratchLive());
          }
        }
      }
      assertEquals(27, scalar(history, LocalDateTime.of(0, 2, 29, 0, 0), query(0)));
      assertEquals(28, scalar(history, LocalDateTime.of(-1, 1, 1, 0, 0), null));
      assertEquals(29, scalar(history, LocalDateTime.of(10000, 1, 1, 0, 0), null));
      assertEquals(
          27, scalar(history, LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999999), null));
    }
  }

  @Test
  void operationBoundariesPreserveTheFirstRejectionWithoutRetryCharges() {
    for (boolean history : List.of(false, true)) {
      for (int year : List.of(2026, -1, 10000)) {
        var time = LocalDateTime.of(year, 1, 1, 0, 0);
        long expectedBytes = year == 2026 ? 27 : year == -1 ? 28 : 29;
        long needed = year == 2026 ? 7 : year == -1 ? 62 : 63;
        for (int delta : List.of(-1, 0, 1)) {
          var query = query(65536 - needed - delta);
          if (delta < 0) {
            var failure = assertThrows(TaskFailure.class, () -> scalar(history, time, query));
            assertSame(failure, query.recordedFailure());
            long operations = query.resources().operations();
            assertSame(
                failure, assertThrows(TaskFailure.class, () -> scalar(history, time, query)));
            assertEquals(operations, query.resources().operations());
          } else {
            assertEquals(expectedBytes, scalar(history, time, query));
            assertEquals(65536 - delta, query.resources().operations());
          }
          assertEquals(65536, query.scratchLive());
        }
      }
    }
  }

  @Test
  void fastDatesNeedNoFormatterScratchAndFallbackKeepsItsExactReservation() {
    for (boolean history : List.of(false, true)) {
      for (int year : List.of(2026, -1, 10000)) {
        for (int available : List.of(2047, 2048, 2049)) {
          var query = query(0);
          Object pressure = new Object();
          long batchBytes = history ? SessionHistoryMetadataBatch.RETAINED_BYTES : 0;
          query.retainScratch(pressure, 1048576 - 65536 - batchBytes - available);
          long before = query.scratchLive();
          var time = LocalDateTime.of(year, 1, 1, 0, 0);
          if (year != 2026 && available < 2048)
            assertThrows(TaskFailure.class, () -> scalar(history, time, query));
          else assertEquals(year == 2026 ? 27 : year == -1 ? 28 : 29, scalar(history, time, query));
          assertEquals(before, query.scratchLive());
          if (year == 2026) assertEquals(before + batchBytes, query.scratchPeak());
          query.releaseScratch(pressure);
          assertEquals(65536, query.scratchLive());
        }
      }
    }
  }

  @Test
  void localeDoesNotChangeTheLiteralZOrMetadataWidth() {
    var original = Locale.getDefault(Locale.Category.FORMAT);
    try {
      for (var locale :
          List.of(
              Locale.ROOT,
              Locale.forLanguageTag("ar-EG"),
              Locale.forLanguageTag("th-TH"),
              Locale.JAPANESE)) {
        Locale.setDefault(Locale.Category.FORMAT, locale);
        for (boolean history : List.of(false, true)) {
          for (int year : List.of(0, 9999, -1, 10000)) {
            var time = LocalDateTime.of(year, 1, 1, 0, 0, 0, 999999999);
            long expected =
                DateTimeFormatter.ofPattern(PATTERN)
                    .format(time)
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
            assertEquals(expected, scalar(history, time, query(0)));
          }
        }
      }
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, original);
    }
  }

  @Test
  void otherScalarBranchesAndBooleanDifferenceRemainUnchanged() {
    for (boolean history : List.of(false, true)) {
      assertEquals(1, scalar(history, null, query(0)));
      assertEquals(10, scalar(history, "绑定😀", query(0)));
      assertEquals(20, scalar(history, Long.MIN_VALUE, query(0)));
      assertEquals(19, scalar(history, Long.MAX_VALUE, query(0)));
      assertEquals(11, scalar(history, Integer.MIN_VALUE, null));
      assertEquals(1, scalar(history, 0L, null));
      for (String malformed : List.of("\ud800", "\udc00", "\ud800a"))
        assertThrows(TaskFailure.class, () -> scalar(history, malformed, query(0)));
      for (Object invalid :
          List.of(java.sql.Timestamp.valueOf("2026-01-01 00:00:00"), 1.0, new byte[0])) {
        var query = query(0);
        assertThrows(TaskFailure.class, () -> scalar(history, invalid, query));
        assertEquals(0, query.resources().operations());
      }
    }
    assertEquals(4, scalar(false, true, query(0)));
    assertEquals(5, scalar(false, false, null));
    assertThrows(TaskFailure.class, () -> scalar(true, true, query(0)));
  }

  @Test
  void closedBatchAndExistingFailurePrecedeDateGuardsAndKeepCauseAndSuppressed() {
    var query = query(0);
    var time = LocalDateTime.of(2026, 1, 1, 0, 0);
    var batch = new SessionHistoryMetadataBatch(query, List::of);
    batch.close();
    var failure = new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    var cause = new java.sql.SQLException("marker", "22000");
    var suppressed = new IllegalStateException("close-marker");
    failure.initCause(cause);
    failure.addSuppressed(suppressed);
    query.recordFailure(failure);
    assertThrows(IllegalStateException.class, () -> batch.scalarBytes(time));
    assertSame(
        failure, assertThrows(TaskFailure.class, () -> SessionMetadataScalars.bytes(time, query)));
    assertSame(cause, failure.getCause());
    assertSame(suppressed, failure.getSuppressed()[0]);
    assertEquals(0, query.resources().operations());
    assertEquals(65536, query.scratchLive());
    // Already-open history still consults the same slot before either date path.
    var historyQuery = query(0);
    try (var open = new SessionHistoryMetadataBatch(historyQuery, List::of)) {
      historyQuery.recordFailure(failure);
      assertSame(failure, assertThrows(TaskFailure.class, () -> open.scalarBytes(time)));
      assertSame(
          failure, assertThrows(TaskFailure.class, () -> open.scalarBytes(LocalDateTime.MIN)));
      assertEquals(0, historyQuery.resources().operations());
    }
    assertEquals(65536, historyQuery.scratchLive());
  }
}
