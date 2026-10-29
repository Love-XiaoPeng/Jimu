package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;

/** 原UTF8/十进制/UTC/Boolean元数据公式；不分配整串编码副本。 */
final class SessionMetadataScalars {
  private static final java.time.format.DateTimeFormatter UTC =
      java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  private SessionMetadataScalars() {}

  static long bytes(Object value, SessionQueryContext query) {
    if (value == null) {
      operation(query, 1);
      return 1;
    }
    if (value instanceof String text) {
      long bytes = 0;
      for (int i = 0; i < text.length(); i++) {
        operation(query, 1);
        char c = text.charAt(i);
        if (c < 128) bytes++;
        else if (c < 2048) bytes += 2;
        else if (Character.isHighSurrogate(c)) {
          if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
            throw JdbcSessionRecords.corrupt();
          operation(query, 1);
          bytes += 4;
        } else if (Character.isLowSurrogate(c)) throw JdbcSessionRecords.corrupt();
        else bytes += 3;
      }
      return bytes;
    }
    if (value instanceof Boolean bool) {
      operation(query, 1);
      return bool ? 4 : 5;
    }
    if (value instanceof java.time.LocalDateTime time) {
      Object temporary = new Object();
      if (query != null) query.retainScratch(temporary, 2048);
      try {
        operation(query, 27);
        return bytes(UTC.format(time), query);
      } finally {
        if (query != null) query.releaseScratch(temporary);
      }
    }
    if (!(value instanceof Integer) && !(value instanceof Long)) throw JdbcSessionRecords.corrupt();
    long number = ((Number) value).longValue(), size = number < 0 ? 1 : 0;
    if (number > 0) number = -number;
    do {
      operation(query, 1);
      size++;
      number /= 10;
    } while (number != 0);
    return size;
  }

  static void operation(SessionQueryContext query, long count) {
    if (query != null) query.operation(count);
  }
}
