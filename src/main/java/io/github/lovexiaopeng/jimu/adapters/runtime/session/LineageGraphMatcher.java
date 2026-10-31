package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;

/** 固定graph-v1/v2的窄比较器：字段任意顺序，节点按已核有序Node比较；不物化嵌入genesis。 */
final class LineageGraphMatcher {
  private final String raw;
  private final SessionQueryContext query;
  private final java.util.function.ToIntFunction<String> versionValidator;
  private final String schemaSha256;
  private int version, nodeFields;
  private int offset;

  LineageGraphMatcher(
      String raw,
      SessionQueryContext query,
      java.util.function.ToIntFunction<String> versionValidator,
      String schemaSha256) {
    this.raw = raw;
    this.query = query;
    this.versionValidator = versionValidator;
    this.schemaSha256 = schemaSha256;
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
    Object numberWorkspace = new Object();
    query.retainScratch(numberWorkspace, 4096L + 16L * (offset - start));
    try {
      String number =
          query.primitive("copy.graph.number", offset - start, () -> raw.substring(start, offset));
      version = versionValidator.applyAsInt(number);
    } catch (IllegalArgumentException | TaskFailure failure) {
      if (failure instanceof TaskFailure refusal
          && (refusal.status() == 429 || refusal.status() == 504)) throw refusal;
      throw corrupt();
    } finally {
      query.releaseScratch(numberWorkspace);
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
    query.operation(4);
    if (seen != 7 && seen != 5 || nodeFields != 0 && nodeFields != seen) throw corrupt();
    nodeFields = seen;
  }

  private void nodes(java.util.List<SessionLineage.Node> expected) {
    token('[');
    for (int i = 0; i < expected.size(); i++) {
      if (i > 0) token(',');
      node(expected.get(i));
    }
    token(']');
  }

  static void validateExpected(SessionLineage.Graph expected, SessionQueryContext query) {
    query.operation(7);
    if (expected.callerSessionId() == null || expected.authoritySha256() == null) throw corrupt();
    if (expected.version() != 1 || expected.nodes().isEmpty() || expected.nodes().size() > 9)
      throw corrupt();
    // 期望值来自逐份已核standalone genesis。这里再次约束graph自身的冻结标量schema。
    query.operation(expected.callerSessionId().length() + expected.authoritySha256().length());
    if (!expected.callerSessionId().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        || !expected.authoritySha256().matches("[0-9a-f]{64}")) throw corrupt();
    // Caller/source membership and order come from freshly verified standalone genesis.
    query.operation(4L + expected.nodes().size() * 3L);
    String previous = null;
    for (var node : expected.nodes()) {
      query.operation(5);
      if (node == null
          || node.sessionId() == null
          || node.genesisSha256() == null
          || node.genesisSnapshot() == null) throw corrupt();
      query.operation(
          node.sessionId().length()
              + node.genesisSha256().length()
              + (previous == null ? 0 : previous.length()));
      if (!node.sessionId().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
          || !node.genesisSha256().matches("[0-9a-f]{64}")
          || previous != null && previous.compareTo(node.sessionId()) >= 0) throw corrupt();
      previous = node.sessionId();
    }
  }

  void match(SessionLineage.Graph expected) {
    Object workspace = new Object();
    query.retainScratch(workspace, 4096);
    try {
      validateExpected(expected, query);
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
          case "codec_id" -> {
            seen = field(seen, 16);
            text(LineageGraphCodecs.ID);
          }
          case "schema_sha256" -> {
            seen = field(seen, 32);
            text(schemaSha256);
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
      query.operation(6);
      if (version != 1 && version != 2
          || offset != raw.length()
          || version == 1 && (seen != 15 || nodeFields != 7)
          || version == 2 && (seen != 63 || nodeFields != 5)) throw corrupt();
    } finally {
      query.releaseScratch(workspace);
    }
  }
}
