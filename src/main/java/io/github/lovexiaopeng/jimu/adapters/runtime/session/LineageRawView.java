package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 一次物化原串的严格UTF8长度和独立摘要；编码缓冲从不离开本方法。 */
public final class LineageRawView {
  private final String raw;
  private final int bytes;
  private final String digest;

  public LineageRawView(String raw, SessionQueryContext context) {
    if (raw == null) throw corrupt();
    this.raw = raw;
    Object bufferOwner = new Object();
    long capacity = Math.multiplyExact(3L, raw.length());
    context.retainScratch(bufferOwner, capacity + 128);
    try {
      var buffer = ByteBuffer.allocate(Math.toIntExact(capacity));
      context.primitive(
          "encode",
          raw.length(),
          () -> {
            var encoder =
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var result = encoder.encode(CharBuffer.wrap(raw), buffer, true);
            if (result.isError() || result.isOverflow()) throw corrupt();
            if (encoder.flush(buffer).isError()) throw corrupt();
            return null;
          });
      bytes = buffer.position();
      buffer.flip();
      digest =
          context.primitive(
              "hash",
              raw.length(),
              () -> {
                try {
                  var hash = MessageDigest.getInstance("SHA-256");
                  hash.update(buffer);
                  return HexFormat.of().formatHex(hash.digest());
                } catch (NoSuchAlgorithmException impossible) {
                  throw new IllegalStateException(impossible);
                }
              });
    } finally {
      context.releaseScratch(bufferOwner);
    }
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  public String raw() {
    return raw;
  }

  public int bytes() {
    return bytes;
  }

  public String digest() {
    return digest;
  }
}
