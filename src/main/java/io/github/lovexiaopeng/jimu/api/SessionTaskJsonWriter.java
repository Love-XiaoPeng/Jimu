package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;

/** 固定View wire的单遍UTF8出口；不建树、String、日期对象或编码缓冲。 */
final class SessionTaskJsonWriter implements AutoCloseable {
  // 固定对象/同时存活闭包与五组诊断槽的保守账模；不包含外部sink，也不是堆实测。
  static final int WORKSPACE_BYTES = 2048;
  private SessionTaskResponse response;
  private OutputStream output;

  SessionTaskJsonWriter(SessionTaskResponse response, OutputStream output) {
    this.response = response;
    this.output = output;
  }

  void write(Tasks.View view) {
    raw("{\"taskId\":");
    text(view.taskId());
    raw(",\"state\":");
    text(view.state());
    raw(",\"revision\":");
    number(view.revision(), 0);
    raw(",\"createdAt\":");
    instant(view.createdAt());
    raw(",\"updatedAt\":");
    instant(view.updatedAt());
    raw(",\"cancelRequestedAt\":");
    instant(view.cancelRequestedAt());
    raw(",\"code\":");
    text(view.code());
    raw(",\"summary\":");
    text(view.summary());
    raw(",\"artifacts\":[");
    for (int i = 0; i < view.artifacts().size(); i++) {
      int index = i;
      var artifact = response.primitive("response.serialize", 1, () -> view.artifacts().get(index));
      if (i != 0) put(',');
      raw("{\"artifactId\":");
      text(artifact.artifactId());
      raw(",\"mediaType\":");
      text(artifact.mediaType());
      raw(",\"size\":");
      number(artifact.size(), 0);
      raw(",\"sha256\":");
      text(artifact.sha256());
      raw(",\"expiresAt\":");
      instant(artifact.expiresAt());
      put('}');
    }
    raw("]}");
  }

  private void raw(String ascii) {
    for (int i = 0; i < ascii.length(); i++) {
      int index = i;
      int value = response.primitive("response.serialize", 1, () -> (int) ascii.charAt(index));
      put(value);
    }
  }

  private void text(String value) {
    if (value == null) {
      raw("null");
      return;
    }
    put('"');
    for (int i = 0; i < value.length(); i++) {
      int index = i;
      char c = response.primitive("response.serialize", 1, () -> value.charAt(index));
      switch (c) {
        case '"' -> raw("\\\"");
        case '\\' -> raw("\\\\");
        case '\b' -> raw("\\b");
        case '\t' -> raw("\\t");
        case '\n' -> raw("\\n");
        case '\f' -> raw("\\f");
        case '\r' -> raw("\\r");
        default -> {
          if (c < 32) escape(c);
          else if (Character.isSurrogate(c)) {
            if (Character.isHighSurrogate(c) && i + 1 < value.length()) {
              int next = i + 1;
              char low = response.primitive("response.serialize", 1, () -> value.charAt(next));
              if (Character.isLowSurrogate(low)) {
                utf8(Character.toCodePoint(c, low));
                i++;
              } else escape(c);
            } else escape(c);
          } else utf8(c);
        }
      }
    }
    put('"');
  }

  private void escape(char c) {
    raw("\\u");
    for (int shift = 12; shift >= 0; shift -= 4) {
      int digit = (c >> shift) & 15;
      put(digit < 10 ? '0' + digit : 'A' + digit - 10);
    }
  }

  private void utf8(int codepoint) {
    response.primitive(
        "response.encode",
        1,
        () -> {
          if (codepoint < 128) put(codepoint);
          else if (codepoint < 2048) {
            put(0xC0 | (codepoint >> 6));
            put(0x80 | (codepoint & 63));
          } else if (codepoint < 65536) {
            put(0xE0 | (codepoint >> 12));
            put(0x80 | ((codepoint >> 6) & 63));
            put(0x80 | (codepoint & 63));
          } else {
            put(0xF0 | (codepoint >> 18));
            put(0x80 | ((codepoint >> 12) & 63));
            put(0x80 | ((codepoint >> 6) & 63));
            put(0x80 | (codepoint & 63));
          }
          return null;
        });
  }

  private void number(long value, int width) {
    if (value < 0) put('-');
    // 负域避免MIN_VALUE溢出；不分配十进制String或digits数组。
    long negative = value > 0 ? -value : value;
    long divisor = 1;
    int count = 1;
    while (quotient(negative, divisor) <= -10) {
      long previous = divisor;
      divisor = response.primitive("response.number", 2, () -> previous * 10);
      count++;
    }
    for (int i = count; i < width; i++) put('0');
    do {
      long div = divisor;
      put(response.primitive("response.number", 3, () -> (int) ('0' - negative / div % 10)));
      divisor = quotient(divisor, 10);
    } while (divisor != 0);
  }

  private long quotient(long value, long divisor) {
    return response.primitive("response.number", 1, () -> value / divisor);
  }

  private void instant(Instant value) {
    if (value == null) {
      raw("null");
      return;
    }
    put('"');
    long epoch = response.primitive("response.instant", 1, value::getEpochSecond);
    long zero =
        response.primitive("response.instant", 3, () -> Math.floorDiv(epoch, 86400) + 719528 - 60);
    long adjust = 0;
    if (zero < 0) {
      long prior = zero;
      long cycles = response.primitive("response.instant", 3, () -> (prior + 1) / 146097 - 1);
      adjust = response.primitive("response.instant", 1, () -> cycles * 400);
      zero = response.primitive("response.instant", 2, () -> prior - cycles * 146097);
    }
    long days = zero;
    long year = response.primitive("response.instant", 3, () -> (400 * days + 591) / 146097);
    long day = dayOfYear(days, year);
    if (day < 0) {
      long previousYear = year;
      year = response.primitive("response.instant", 1, () -> previousYear - 1);
      day = dayOfYear(days, year);
    }
    long doy = day;
    int march = response.primitive("response.instant", 3, () -> ((int) doy * 5 + 2) / 153);
    int month = response.primitive("response.instant", 3, () -> (march + 2) % 12 + 1);
    int dom =
        response.primitive("response.instant", 5, () -> (int) doy - (march * 306 + 5) / 10 + 1);
    long previousYear = year, yearAdjust = adjust;
    year = response.primitive("response.instant", 3, () -> previousYear + yearAdjust + march / 10);
    if (year > 9999) put('+');
    number(year, 4);
    put('-');
    number(month, 2);
    put('-');
    number(dom, 2);
    put('T');
    int second = response.primitive("response.instant", 1, () -> (int) Math.floorMod(epoch, 86400));
    number(quotient(second, 3600), 2);
    put(':');
    number(response.primitive("response.instant", 2, () -> second / 60 % 60), 2);
    put(':');
    number(response.primitive("response.instant", 1, () -> second % 60), 2);
    int nano = response.primitive("response.instant", 1, value::getNano);
    if (nano != 0) {
      put('.');
      if (response.primitive("response.instant", 1, () -> nano % 1000000) == 0)
        number(quotient(nano, 1000000), 3);
      else if (response.primitive("response.instant", 1, () -> nano % 1000) == 0)
        number(quotient(nano, 1000), 6);
      else number(nano, 9);
    }
    put('Z');
    put('"');
  }

  private long dayOfYear(long days, long year) {
    return response.primitive(
        "response.instant", 8, () -> days - (365 * year + year / 4 - year / 100 + year / 400));
  }

  private void put(int value) {
    response.primitive(
        "response.wire",
        1,
        () -> {
          try {
            output.write(value);
          } catch (IOException failure) {
            throw new UncheckedIOException(failure);
          }
          return null;
        });
  }

  public void close() {
    // 失败不再flush，禁止重发；sink不属于本句柄，先断引用再由外层清View/退owner。
    output = null;
    response = null;
  }
}
