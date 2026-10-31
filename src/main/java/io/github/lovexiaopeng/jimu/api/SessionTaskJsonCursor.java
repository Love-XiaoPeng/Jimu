package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.time.Instant;

/** 固定Tasks.View的可恢复UTF8游标；状态仅前进，不重编码已经准备的byte。 */
final class SessionTaskJsonCursor implements AutoCloseable {
  // 明确的内部同时驻留上界账模，见response-accounting.csv；不含容器socket缓冲。
  static final int WORKSPACE_BYTES = 12288;
  private SessionTaskResponse response;
  private Tasks.View view;
  private Tasks.Artifact artifact;
  private int field, artifactIndex;
  private String text;
  private int token, offset, textStage;
  private long pending;
  private int pendingBytes;
  private long negative, divisor;
  private int padding;
  private boolean sign;
  private int instantStage;
  private long year;
  private int month, day, second, nano;

  SessionTaskJsonCursor(SessionTaskResponse response, Tasks.View view) {
    this.response = response;
    this.view = view;
  }

  private void work(String name, int count) {
    response.primitive(name, count, () -> null);
  }

  /** -1仅表示整个JSON结束；暂停不会改变token/UTF8/数字/日期状态。 */
  int next() {
    for (; ; ) {
      work("response.serialize", 1); // token分派条件，每一次真实迭代。
      if (pendingBytes != 0) {
        work("response.encode", 4);
        int value = (int) (pending & 255);
        pending >>>= 8;
        pendingBytes--;
        return value;
      }
      switch (token) {
        case 1 -> {
          work("response.serialize", 4); // length/比较/charAt/offset推进。
          if (offset < text.length()) return text.charAt(offset++);
          text = null;
          token = 0;
        }
        case 2 -> {
          int value = textByte();
          if (value >= 0) return value;
        }
        case 3 -> {
          int value = numberByte();
          if (value >= 0) return value;
          token = instantStage == 0 ? 0 : 4;
        }
        case 4 -> instantPart();
        default -> {
          if (!field()) return -1;
        }
      }
    }
  }

  private boolean field() {
    work("response.serialize", 2); // ordinal读取与推进；各accessor单独在下列分支执行。
    switch (field++) {
      case 0 -> raw("{\"taskId\":");
      case 1 -> string(response.primitive("response.serialize", 1, view::taskId));
      case 2 -> raw(",\"state\":");
      case 3 -> string(response.primitive("response.serialize", 1, view::state));
      case 4 -> raw(",\"revision\":");
      case 5 -> number(response.primitive("response.serialize", 1, view::revision), 0);
      case 6 -> raw(",\"createdAt\":");
      case 7 -> instant(response.primitive("response.serialize", 1, view::createdAt));
      case 8 -> raw(",\"updatedAt\":");
      case 9 -> instant(response.primitive("response.serialize", 1, view::updatedAt));
      case 10 -> raw(",\"cancelRequestedAt\":");
      case 11 -> instant(response.primitive("response.serialize", 1, view::cancelRequestedAt));
      case 12 -> raw(",\"code\":");
      case 13 -> string(response.primitive("response.serialize", 1, view::code));
      case 14 -> raw(",\"summary\":");
      case 15 -> string(response.primitive("response.serialize", 1, view::summary));
      case 16 -> raw(",\"artifacts\":[");
      case 17 -> {
        int size = response.primitive("response.serialize", 2, () -> view.artifacts().size());
        work("response.serialize", 1);
        if (artifactIndex == size) {
          raw("]}");
          field = 30;
        } else {
          artifact =
              response.primitive(
                  "response.serialize", 2, () -> view.artifacts().get(artifactIndex));
          raw(artifactIndex == 0 ? "{\"artifactId\":" : ",{\"artifactId\":");
          work("response.serialize", 1);
        }
      }
      case 18 -> string(response.primitive("response.serialize", 1, artifact::artifactId));
      case 19 -> raw(",\"mediaType\":");
      case 20 -> string(response.primitive("response.serialize", 1, artifact::mediaType));
      case 21 -> raw(",\"size\":");
      case 22 -> number(response.primitive("response.serialize", 1, artifact::size), 0);
      case 23 -> raw(",\"sha256\":");
      case 24 -> string(response.primitive("response.serialize", 1, artifact::sha256));
      case 25 -> raw(",\"expiresAt\":");
      case 26 -> instant(response.primitive("response.serialize", 1, artifact::expiresAt));
      case 27 -> {
        raw("}");
        artifact = null;
        artifactIndex++;
        field = 17;
        work("response.serialize", 2);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private void raw(String value) {
    text = value;
    offset = 0;
    token = 1;
  }

  private void string(String value) {
    work("response.serialize", 1);
    if (value == null) raw("null");
    else {
      text = value;
      offset = 0;
      textStage = 0;
      token = 2;
    }
  }

  private int textByte() {
    work("response.serialize", 1);
    if (textStage == 0) {
      textStage = 1;
      return '"';
    }
    work("response.serialize", 2);
    if (offset == text.length()) {
      text = null;
      token = 0;
      return '"';
    }
    int c = response.primitive("response.serialize", 2, () -> (int) text.charAt(offset++));
    work("response.serialize", 1); // escape分派。
    switch (c) {
      case '"' -> pair('"');
      case '\\' -> pair('\\');
      case '\b' -> pair('b');
      case '\t' -> pair('t');
      case '\n' -> pair('n');
      case '\f' -> pair('f');
      case '\r' -> pair('r');
      default -> {
        work("response.encode", 1);
        if (c < 32) escape(c);
        else {
          work("response.encode", 1);
          if (c < 128) return c;
          work("response.encode", 1);
          if (Character.isSurrogate((char) c)) {
            work("response.encode", 1);
            if (Character.isHighSurrogate((char) c)) {
              work("response.encode", 2);
              if (offset == text.length()) {
                escape(c);
                break;
              }
              int low =
                  response.primitive("response.serialize", 1, () -> (int) text.charAt(offset));
              work("response.encode", 1);
              if (Character.isLowSurrogate((char) low)) {
                offset++;
                c = Character.toCodePoint((char) c, (char) low);
                work("response.encode", 5);
                utf8(c);
              } else escape(c);
            } else escape(c);
          } else utf8(c);
        }
      }
    }
    return -1;
  }

  private void pair(int value) {
    work("response.encode", 2);
    pending = '\\' | ((long) value << 8);
    pendingBytes = 2;
  }

  private void escape(int value) {
    pending = '\\' | ((long) 'u' << 8);
    for (int i = 0; i < 4; i++) {
      work("response.encode", 10); // 条件、移位/掩码、hex条件/算术、拼接和循环推进。
      int digit = (value >> (12 - i * 4)) & 15;
      pending |= (long) (digit < 10 ? '0' + digit : 'A' + digit - 10) << (16 + i * 8);
    }
    work("response.encode", 1);
    pendingBytes = 6;
  }

  private void utf8(int c) {
    work("response.encode", 1);
    if (c < 128) {
      pending = c;
      pendingBytes = 1;
    } else if (c < 2048) {
      work("response.encode", 7);
      pending = (0xC0 | (c >> 6)) | ((long) (0x80 | (c & 63)) << 8);
      pendingBytes = 2;
    } else if (c < 65536) {
      work("response.encode", 13);
      pending =
          (0xE0 | (c >> 12))
              | ((long) (0x80 | ((c >> 6) & 63)) << 8)
              | ((long) (0x80 | (c & 63)) << 16);
      pendingBytes = 3;
    } else {
      work("response.encode", 18);
      pending =
          (0xF0 | (c >> 18))
              | ((long) (0x80 | ((c >> 12) & 63)) << 8)
              | ((long) (0x80 | ((c >> 6) & 63)) << 16)
              | ((long) (0x80 | (c & 63)) << 24);
      pendingBytes = 4;
    }
  }

  /** month/day/hour/minute/second已由epoch转换限定0..59；不建立日期缓存。 */
  private void twoDigits(int value) {
    work("response.number", 8); // 两次除/余、两次加法、移位/或、两次状态写入。
    pending = ('0' + value / 10) | ((long) ('0' + value % 10) << 8);
    pendingBytes = 2;
  }

  private void number(long value, int width) {
    work("response.number", 5);
    sign = value < 0;
    negative = value > 0 ? -value : value;
    divisor = 1;
    int digits = 1;
    for (; ; ) {
      work("response.number", 2);
      if (negative / divisor > -10) break;
      work("response.number", 2);
      divisor *= 10;
      digits++;
    }
    padding = Math.max(0, width - digits);
    work("response.number", 2);
    token = 3;
  }

  private int numberByte() {
    work("response.number", 1);
    if (sign) {
      sign = false;
      return '-';
    }
    work("response.number", 1);
    if (padding > 0) {
      padding--;
      return '0';
    }
    work("response.number", 1);
    if (divisor == 0) return -1;
    work("response.number", 4);
    int result = (int) ('0' - negative / divisor % 10);
    divisor /= 10;
    return result;
  }

  private void instant(Instant value) {
    work("response.instant", 1);
    if (value == null) {
      raw("null");
      return;
    }
    long epoch = response.primitive("response.instant", 1, value::getEpochSecond);
    nano = response.primitive("response.instant", 1, value::getNano);
    work("response.instant", 4);
    long zero = Math.floorDiv(epoch, 86400) + 719528 - 60;
    long adjust = 0;
    if (zero < 0) {
      work("response.instant", 6);
      long cycles = (zero + 1) / 146097 - 1;
      adjust = cycles * 400;
      zero -= cycles * 146097;
    }
    work("response.instant", 4);
    year = (400 * zero + 591) / 146097;
    long doy = dayOfYear(zero, year);
    if (doy < 0) {
      work("response.instant", 1);
      year--;
      doy = dayOfYear(zero, year);
    }
    work("response.instant", 16);
    int march = ((int) doy * 5 + 2) / 153;
    month = (march + 2) % 12 + 1;
    day = (int) doy - (march * 306 + 5) / 10 + 1;
    year += adjust + march / 10;
    second = (int) Math.floorMod(epoch, 86400);
    instantStage = 1;
    token = 4;
  }

  private long dayOfYear(long days, long y) {
    work("response.instant", 8);
    return days - (365 * y + y / 4 - y / 100 + y / 400);
  }

  private void instantPart() {
    work("response.instant", 2);
    switch (instantStage++) {
      case 1 -> pending('"');
      case 2 -> {
        work("response.instant", 1);
        if (year > 9999) pending('+');
      }
      case 3 -> number(year, 4);
      case 4, 6 -> pending('-');
      case 5 -> twoDigits(month);
      case 7 -> twoDigits(day);
      case 8 -> pending('T');
      case 9 -> {
        work("response.instant", 1);
        twoDigits(second / 3600);
      }
      case 10, 12 -> pending(':');
      case 11 -> {
        work("response.instant", 2);
        twoDigits(second / 60 % 60);
      }
      case 13 -> {
        work("response.instant", 1);
        twoDigits(second % 60);
      }
      case 14 -> {
        work("response.instant", 1);
        if (nano == 0) instantStage = 16;
        else pending('.');
      }
      case 15 -> {
        work("response.instant", 1);
        if (nano % 1000000 == 0) {
          work("response.instant", 1);
          number(nano / 1000000, 3);
        } else {
          work("response.instant", 1);
          if (nano % 1000 == 0) {
            work("response.instant", 1);
            number(nano / 1000, 6);
          } else number(nano, 9);
        }
      }
      case 16 -> pending('Z');
      case 17 -> {
        pending('"');
        instantStage = 0;
        token = 0;
      }
      default -> throw new IllegalStateException("Instant cursor state");
    }
  }

  private void pending(int value) {
    pending = value;
    pendingBytes = 1;
  }

  @Override
  public void close() {
    text = null;
    artifact = null;
    view = null;
    response = null;
  }
}
