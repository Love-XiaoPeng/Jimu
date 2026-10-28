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
      for (var row : records.rows(table)) {
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
          total += SessionHistoryPolicyV1.FIELD_HEADER_BYTES + scalarBytes(value);
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
    for (int i = 0; i < SessionHistoryPolicyV1.ACCOUNTING_ROUNDS; i++) {
      long bytes = metadata(records, closing, 0);
      long old = records.metadataBytes();
      if (old == bytes) return;
      records.account(bytes);
    }
    throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static TaskFailure limit() {
    return new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
  }
}
