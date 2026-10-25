package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionMetadataAccountingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
    long cleanupHeadroom = 1024;
    for (var table : SessionMetadataAccounting.Table.values()) {
      for (var row : records.rows(table)) {
        cleanupHeadroom +=
            switch (table) {
              case TASK -> 768;
              case STEP, INVOCATION, INVOCATION_SESSION_SEARCH -> 512;
              case MODEL_DISPATCH, SESSION_PUBLIC_EVENT -> 256;
              default -> 0;
            };
        total += 32;
        for (Object value : row.values()) total += 8 + scalarBytes(value);
      }
    }
    if (total > (closing ? 65536 : 61440)) throw limit();
    // Cleanup receipts consume capacity without being charged as actual stored bytes.
    if (!closing && total + cleanupHeadroom + planned > 61440) throw limit();
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
    for (int i = 0; i < 4; i++) {
      long bytes = metadata(records, closing, 0);
      long old = records.metadataBytes();
      if (old == bytes) return;
      records.account(bytes);
    }
    throw new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }

  private static TaskFailure limit() {
    return new TaskFailure(429, "SESSION_HISTORY_LIMIT");
  }
}
