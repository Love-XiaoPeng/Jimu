package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.io.Writer;

/** 分配前持有builder、Jackson缓冲和最终String；每次实际序列化写入单独累计。 */
final class LineageJsonWriter extends Writer {
  private final StringBuilder buffer;
  private final int max;
  private final SessionQueryContext query;

  private LineageJsonWriter(int max, SessionQueryContext query) {
    this.max = max;
    this.query = query;
    buffer = new StringBuilder(max);
  }

  static LineageRawView encode(Object value, int max, SessionQueryContext query) {
    Object workspace = new Object();
    query.retainScratch(workspace, max * 4L + 32768);
    try {
      var writer = new LineageJsonWriter(max, query);
      Payloads.json(value, writer);
      String raw = query.primitive("copy.wire", writer.buffer.length(), writer.buffer::toString);
      var view = new LineageRawView(raw, query);
      if (view.bytes() > max) throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
      return view;
    } finally {
      query.releaseScratch(workspace);
    }
  }

  public void write(char[] chars, int start, int length) {
    if (length > max - buffer.length())
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    query.primitive(
        "serialize",
        length,
        () -> {
          buffer.append(chars, start, length);
          return null;
        });
  }

  public void write(String text, int start, int length) {
    if (length > max - buffer.length())
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    query.primitive(
        "serialize",
        length,
        () -> {
          buffer.append(text, start, start + length);
          return null;
        });
  }

  public void flush() {}

  public void close() {}
}
