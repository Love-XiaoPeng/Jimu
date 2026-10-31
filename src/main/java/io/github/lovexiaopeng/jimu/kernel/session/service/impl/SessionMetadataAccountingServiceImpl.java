package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionMetadataAccountingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Original scalar arithmetic, capacity rejection order and four-round convergence. */
public final class SessionMetadataAccountingServiceImpl
    implements SessionMetadataAccountingService {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  public long metadata(SessionMetadataAccounting records, boolean closing, long planned) {
    long total = 0;
    long cleanupHeadroom = SessionHistoryPolicyV1.CLEANUP_BASE_BYTES;
    for (var table : SessionMetadataAccounting.Table.values()) {
      try (var batch = records.batch(table)) {
        for (var row : batch) {
          cleanupHeadroom +=
              switch (table) {
                case TASK -> SessionHistoryPolicyV1.TASK_CLEANUP_BYTES;
                case STEP,
                        INVOCATION,
                        INVOCATION_SESSION_SEARCH,
                        INVOCATION_SESSION_LINEAGE_TRACE,
                        TASK_SESSION_LINEAGE ->
                    SessionHistoryPolicyV1.STEP_CLEANUP_BYTES;
                case MODEL_DISPATCH, SESSION_PUBLIC_EVENT ->
                    SessionHistoryPolicyV1.DISPATCH_CLEANUP_BYTES;
                default -> 0;
              };
          total += SessionHistoryPolicyV1.ROW_HEADER_BYTES;
          for (Object value : row.values())
            total += SessionHistoryPolicyV1.FIELD_HEADER_BYTES + batch.scalarBytes(value);
        }
      }
    }
    if (total
        > (closing
            ? SessionHistoryPolicyV1.CLOSED_METADATA_BYTES
            : SessionHistoryPolicyV1.OPEN_METADATA_BYTES)) throw limit();
    // Cleanup receipts consume capacity without being charged as actual stored bytes.
    if (!closing && total + cleanupHeadroom + planned > SessionHistoryPolicyV1.OPEN_METADATA_BYTES)
      throw limit();
    return total;
  }

  private static int scalarBytes(Object value) {
    if (value == null) return 1;
    if (value instanceof LocalDateTime time)
      return UTC.format(time).getBytes(StandardCharsets.UTF_8).length;
    return value.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  public void account(SessionMetadataAccounting records, boolean closing) {
    if (!records.session()) return;
    if (records.finalPresent()) {
      finalPresent(records, closing);
      return;
    }
    for (int i = 0; i < SessionHistoryPolicyV1.ACCOUNTING_ROUNDS; i++) {
      long bytes = metadata(records, closing, 0);
      long old = records.metadataBytes();
      if (old == bytes) return;
      records.account(bytes);
    }
    throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  /** 最终行快照只读一次；仅metadata_bytes的十进制内容参与影子自依赖。 */
  private void finalPresent(SessionMetadataAccounting records, boolean closing) {
    try (var retained = records.finalization()) {
      finalPresentReserved(records, closing);
    }
  }

  private void finalPresentReserved(SessionMetadataAccounting records, boolean closing) {
    var totals = new FinalTotals();
    readFinal(records, totals);
    long total = totals.total, headroom = totals.headroom;
    long old = totals.old, body = totals.body, reserved = totals.reserved, held = totals.held;
    long amount = totals.amount;
    Long actual = totals.actual;
    String content = totals.content, reservationState = totals.reservationState;
    int histories = totals.histories, reservations = totals.reservations;
    if ("PURGED".equals(content)) {
      // purge自身已经收敛并释放预算；最终阶段只验证封闭余额，绝不再次释放或套PRESENT预留。
      if (histories != 1
          || reservations != 1
          || body != 0
          || old != total
          || total > SessionHistoryPolicyV1.CLOSED_METADATA_BYTES
          || held != total
          || amount != SessionHistoryPolicyV1.TASK_RESERVE_BYTES
          || actual == null
          || actual != total
          || reserved != SessionHistoryPolicyV1.TASK_RESERVE_BYTES
          || !"SETTLED".equals(reservationState)) throw corrupt();
      var check = records.accountAndReadBack(old, total);
      if (check.metadata() != total
          || check.body() != 0
          || check.reserved() != reserved
          || check.held() != total
          || check.amount() != amount
          || !java.util.Objects.equals(check.actual(), actual)
          || !"SETTLED".equals(check.reservationState())
          || !"PURGED".equals(check.content())) throw corrupt();
      return;
    }
    if (histories != 1
        || reservations != 1
        || body < 0
        || body > 7L * 1024 * 1024
        || held != SessionHistoryPolicyV1.TASK_RESERVE_BYTES
        || reserved != held
        || amount != held
        || actual != null
        || !"RESERVED".equals(reservationState)) throw corrupt();
    long value = 0;
    for (int i = 0; i < SessionHistoryPolicyV1.ACCOUNTING_ROUNDS; i++) {
      long next = Math.addExact(total, scalarBytes(value));
      long cap =
          closing
              ? SessionHistoryPolicyV1.CLOSED_METADATA_BYTES
              : SessionHistoryPolicyV1.OPEN_METADATA_BYTES;
      if (next > cap || (!closing && Math.addExact(next, headroom) > cap)) throw limit();
      if (next == value) {
        var check = records.accountAndReadBack(old, value);
        if (check.metadata() != value
            || check.body() != body
            || check.reserved() != reserved
            || check.held() != held
            || check.amount() != amount
            || !java.util.Objects.equals(check.actual(), actual)
            || !reservationState.equals(check.reservationState())
            || !"PRESENT".equals(check.content())) throw corrupt();
        return;
      }
      value = next;
    }
    throw corrupt();
  }

  /** 最终消费者直接接收存活Result的标量；空表无摘要对象或零值累计交接。 */
  private static final class FinalTotals implements SessionMetadataAccounting.FinalRows {
    long total, headroom = SessionHistoryPolicyV1.CLEANUP_BASE_BYTES;
    long old = -1, body = -1, reserved = -1, held = -1, amount = -1;
    Long actual;
    String content, reservationState;
    int histories, reservations;

    public void accept(
        SessionMetadataAccounting.Table table,
        long rows,
        long bytes,
        long historyCount,
        SessionMetadataAccounting.HistoryScalars history,
        long reservationCount,
        SessionMetadataAccounting.HistoryReservation reservation) {
      if (rows == 0) return;
      total = Math.addExact(total, bytes);
      long cleanup = cleanupBytes(table);
      if (cleanup != 0) headroom = Math.addExact(headroom, Math.multiplyExact(rows, cleanup));
      if (history != null) {
        histories = Math.addExact(histories, Math.toIntExact(historyCount));
        content = history.content();
        if (!java.util.List.of("PRESENT", "PURGED").contains(content)) throw corrupt();
        old = history.metadata();
        body = history.body();
        reserved = history.reserved();
        held = history.held();
        if ("PRESENT".equals(content))
          total = Math.subtractExact(total, history.metadataScalarBytes());
      }
      if (reservation != null) {
        reservations = Math.addExact(reservations, Math.toIntExact(reservationCount));
        amount = reservation.amount();
        actual = reservation.actual();
        reservationState = reservation.state();
      }
    }
  }

  private void readFinal(SessionMetadataAccounting records, FinalTotals totals) {
    for (var table : SessionMetadataAccounting.Table.values()) {
      if (records.consumeFinal(table, totals)) continue;
      try (var batch = records.batch(table)) {
        for (var row : batch) {
          totals.headroom =
              Math.addExact(
                  totals.headroom,
                  switch (table) {
                    case TASK -> SessionHistoryPolicyV1.TASK_CLEANUP_BYTES;
                    case STEP,
                            INVOCATION,
                            INVOCATION_SESSION_SEARCH,
                            INVOCATION_SESSION_LINEAGE_TRACE,
                            TASK_SESSION_LINEAGE ->
                        SessionHistoryPolicyV1.STEP_CLEANUP_BYTES;
                    case MODEL_DISPATCH, SESSION_PUBLIC_EVENT ->
                        SessionHistoryPolicyV1.DISPATCH_CLEANUP_BYTES;
                    default -> 0;
                  });
          totals.total = Math.addExact(totals.total, SessionHistoryPolicyV1.ROW_HEADER_BYTES);
          for (var value : row.values())
            totals.total =
                Math.addExact(
                    totals.total,
                    Math.addExact(
                        SessionHistoryPolicyV1.FIELD_HEADER_BYTES, batch.scalarBytes(value)));
          if (table == SessionMetadataAccounting.Table.TASK_SESSION_HISTORY) {
            totals.histories++;
            totals.content = (String) row.value("content_state");
            if (!java.util.List.of("PRESENT", "PURGED").contains(totals.content)) throw corrupt();
            Object metadataValue = row.value("metadata_bytes");
            totals.old = number(metadataValue);
            totals.body = number(row.value("body_bytes"));
            totals.reserved = number(row.value("reserved_bytes"));
            totals.held = number(row.value("held_bytes"));
            if ("PRESENT".equals(totals.content))
              totals.total = Math.subtractExact(totals.total, scalarBytes(metadataValue));
          }
          if (table == SessionMetadataAccounting.Table.RESERVATION
              && "SESSION_HISTORY_STORAGE".equals(row.value("category"))) {
            totals.reservations++;
            totals.amount = number(row.value("amount"));
            Object actualValue = row.value("actual");
            totals.actual = actualValue == null ? null : number(actualValue);
            totals.reservationState = (String) row.value("state");
          }
        }
      }
    }
  }

  private static long cleanupBytes(SessionMetadataAccounting.Table table) {
    return switch (table) {
      case TASK -> SessionHistoryPolicyV1.TASK_CLEANUP_BYTES;
      case STEP,
              INVOCATION,
              INVOCATION_SESSION_SEARCH,
              INVOCATION_SESSION_LINEAGE_TRACE,
              TASK_SESSION_LINEAGE ->
          SessionHistoryPolicyV1.STEP_CLEANUP_BYTES;
      case MODEL_DISPATCH, SESSION_PUBLIC_EVENT -> SessionHistoryPolicyV1.DISPATCH_CLEANUP_BYTES;
      default -> 0;
    };
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static TaskFailure limit() {
    return new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
  }
}
