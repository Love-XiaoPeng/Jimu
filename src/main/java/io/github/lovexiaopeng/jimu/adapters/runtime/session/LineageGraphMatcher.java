package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.math.BigDecimal;

/** 冻结graph-v1的窄比较器：字段任意顺序，节点按已核有序Node比较；不物化嵌入genesis。 */
final class LineageGraphMatcher {
  private final String raw;
  private final SessionQueryContext query;
  private int offset;

  LineageGraphMatcher(String raw, SessionQueryContext query) {
    this.raw = raw;
    this.query = query;
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private char take() {
    if (offset >= raw.length()) throw corrupt();
    return query.primitive("parse.graph", 1, () -> raw.charAt(offset++));
  }

  private void whitespace() {
    while (offset < raw.length() && " \t\r\n".indexOf(raw.charAt(offset)) >= 0) take();
  }

  private void token(char expected) {
    whitespace();
    if (take() != expected) throw corrupt();
  }

  private boolean end(char closing) {
    whitespace();
    if (offset < raw.length() && raw.charAt(offset) == closing) {
      take();
      return true;
    }
    return false;
  }

  private char decoded() {
    char c = take();
    if (c == '\\') {
      c = take();
      return switch (c) {
        case '"', '\\', '/' -> c;
        case 'b' -> '\b';
        case 'f' -> '\f';
        case 'n' -> '\n';
        case 'r' -> '\r';
        case 't' -> '\t';
        case 'u' -> {
          int value = 0;
          for (int i = 0; i < 4; i++) {
            char hex = take();
            int digit =
                hex >= '0' && hex <= '9'
                    ? hex - '0'
                    : hex >= 'a' && hex <= 'f'
                        ? hex - 'a' + 10
                        : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
            if (digit < 0) throw corrupt();
            value = value * 16 + digit;
          }
          yield (char) value;
        }
        default -> throw corrupt();
      };
    }
    if (c < 32 || c == '"') throw corrupt();
    return c;
  }

  private String key() {
    token('"');
    var key = new StringBuilder(32);
    while (offset < raw.length() && raw.charAt(offset) != '"') {
      if (key.length() == 32) throw corrupt();
      char c = decoded();
      query.primitive(
          "copy.graph.key",
          1,
          () -> {
            key.append(c);
            return null;
          });
    }
    if (take() != '"') throw corrupt();
    token(':');
    return key.toString();
  }

  private void text(String expected) {
    if (expected == null) throw corrupt();
    token('"');
    int index = 0;
    while (offset < raw.length() && raw.charAt(offset) != '"') {
      char decoded = decoded();
      final int at = index++;
      boolean equal =
          query.primitive(
              "compare.graph", 1, () -> at < expected.length() && decoded == expected.charAt(at));
      if (!equal) throw corrupt();
    }
    if (take() != '"' || index != expected.length()) throw corrupt();
  }

  private void version() {
    whitespace();
    int start = offset;
    while (offset < raw.length() && "0123456789+-.eE".indexOf(raw.charAt(offset)) >= 0) take();
    String number = raw.substring(start, offset);
    query.primitive("copy.graph.number", number.length(), () -> null);
    if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw corrupt();
    try {
      if (new BigDecimal(number).compareTo(BigDecimal.ONE) != 0) throw corrupt();
    } catch (NumberFormatException failure) {
      throw corrupt();
    }
  }

  private int field(int seen, int bit) {
    if ((seen & bit) != 0) throw corrupt();
    return seen | bit;
  }

  private void node(SessionLineage.Node expected) {
    token('{');
    int seen = 0;
    if (end('}')) throw corrupt();
    do {
      String key = key();
      switch (key) {
        case "session_id" -> {
          seen = field(seen, 1);
          text(expected.sessionId());
        }
        case "genesis_snapshot" -> {
          seen = field(seen, 2);
          text(expected.genesisSnapshot());
        }
        case "genesis_sha256" -> {
          seen = field(seen, 4);
          text(expected.genesisSha256());
        }
        default -> throw corrupt();
      }
      if (end('}')) break;
      token(',');
    } while (true);
    if (seen != 7) throw corrupt();
  }

  private void nodes(java.util.List<SessionLineage.Node> expected) {
    token('[');
    for (int i = 0; i < expected.size(); i++) {
      if (i > 0) token(',');
      node(expected.get(i));
    }
    token(']');
  }

  void match(SessionLineage.Graph expected) {
    if (expected.version() != 1 || expected.nodes().isEmpty() || expected.nodes().size() > 9)
      throw corrupt();
    // 期望值来自逐份已核standalone genesis。这里再次约束graph自身的冻结标量schema。
    if (!expected.callerSessionId().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        || !expected.authoritySha256().matches("[0-9a-f]{64}")) throw corrupt();
    Object workspace = new Object();
    query.retainScratch(workspace, 4096);
    try {
      token('{');
      int seen = 0;
      if (end('}')) throw corrupt();
      do {
        String key = key();
        switch (key) {
          case "version" -> {
            seen = field(seen, 1);
            version();
          }
          case "caller_session_id" -> {
            seen = field(seen, 2);
            text(expected.callerSessionId());
          }
          case "authority_sha256" -> {
            seen = field(seen, 4);
            text(expected.authoritySha256());
          }
          case "nodes" -> {
            seen = field(seen, 8);
            nodes(expected.nodes());
          }
          default -> throw corrupt();
        }
        if (end('}')) break;
        token(',');
      } while (true);
      whitespace();
      if (seen != 15 || offset != raw.length()) throw corrupt();
    } finally {
      query.releaseScratch(workspace);
    }
  }
}
