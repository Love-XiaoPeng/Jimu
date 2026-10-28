package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * Experimental internal typed writer. External/persisted raw still uses frozen reader/schema.
 * Deliberately test-only until differential validation and complete ownership are established.
 */
public final class TypedLineageOutputCandidate {
  private static final JsonFactory JSON = new JsonFactory();
  // Preserve current Payloads Map.of descendant field order, including JDK iteration salt.
  private static final boolean NODE_FIRST =
      Map.of("node", true, "depth", true).keySet().iterator().next().equals("node");

  private TypedLineageOutputCandidate() {}

  public static LineageRawView output(SessionLineage.Result r, SessionQueryContext q) {
    Object owner = new Object();
    q.retainScratch(owner, 8192L * 4 + 32768 + 4096);
    try {
      return reserved(r, q);
    } finally {
      q.releaseScratch(owner);
    }
  }

  private static LineageRawView reserved(SessionLineage.Result r, SessionQueryContext q) {
    validate(r, q);
    var sink = new Sink(q);
    try (var j = JSON.createGenerator(sink)) {
      j.writeStartObject();
      j.writeNumberField("version", 1);
      string(j, "caller_session_id", r.caller(), q);
      string(j, "lineage_policy", "owner-declared-continuation-v1", q);
      string(j, "coverage", "admission-scope", q);
      j.writeBooleanField("complete_within_scope", true);
      string(j, "authority_sha256", r.authority(), q);
      string(j, "graph_sha256", r.graph(), q);
      j.writeFieldName("target");
      node(j, r.target(), q);
      j.writeFieldName("root");
      node(j, r.root(), q);
      j.writeArrayFieldStart("ancestors");
      for (var n : r.ancestors()) {
        q.operation(1);
        node(j, n, q);
      }
      j.writeEndArray();
      j.writeArrayFieldStart("descendants");
      for (var d : r.descendants()) {
        q.operation(1);
        j.writeStartObject();
        if (NODE_FIRST) {
          j.writeFieldName("node");
          node(j, d.node(), q);
          j.writeNumberField("depth", d.depth());
        } else {
          j.writeNumberField("depth", d.depth());
          j.writeFieldName("node");
          node(j, d.node(), q);
        }
        j.writeEndObject();
      }
      j.writeEndArray();
      j.writeEndObject();
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
    String raw = q.primitive("copy.wire", sink.buffer.length(), sink.buffer::toString);
    var view = new LineageRawView(raw, q);
    if (view.bytes() > 8192) throw new IllegalArgumentException("output size");
    return view;
  }

  private static void string(JsonGenerator j, String name, String value, SessionQueryContext q)
      throws IOException {
    q.operation(name.length()); // field-name traversal/escaping is real work as well
    j.writeFieldName(name);
    q.primitive(
        "escape.output",
        value.length(),
        () -> {
          try {
            j.writeString(value);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return null;
        });
  }

  private static void node(JsonGenerator j, SessionLineage.PublicNode n, SessionQueryContext q)
      throws IOException {
    j.writeStartObject();
    string(j, "session_id", n.sessionId(), q);
    string(j, "kind", n.kind(), q);
    string(j, "created_at", n.createdAt(), q);
    string(j, "genesis_sha256", n.genesisSha256(), q);
    if (n.parentSessionId() != null) string(j, "parent_session_id", n.parentSessionId(), q);
    j.writeEndObject();
  }

  static void validate(SessionLineage.Result r, SessionQueryContext q) {
    q.operation(11);
    hex(r.caller(), true, q);
    hex(r.authority(), false, q);
    hex(r.graph(), false, q);
    nodeValid(r.target(), q);
    nodeValid(r.root(), q);
    equal(r.root().kind(), "ROOT", q);
    if (r.ancestors().size() > 8 || r.descendants().size() > 8)
      throw new IllegalArgumentException("items");
    for (var n : r.ancestors()) {
      q.operation(1);
      nodeValid(n, q);
    }
    for (var d : r.descendants()) {
      q.operation(2);
      nodeValid(d.node(), q);
      equal(d.node().kind(), "CONTINUATION", q);
      if (d.depth() < 1 || d.depth() > 8) throw new IllegalArgumentException("depth");
    }
  }

  private static void equal(String a, String b, SessionQueryContext q) {
    q.primitive(
        "typed.compare",
        Math.max(a.length(), b.length()),
        () -> {
          if (!a.equals(b)) throw new IllegalArgumentException("constant");
          return null;
        });
  }

  private static void hex(String s, boolean uuid, SessionQueryContext q) {
    if (s == null || s.length() != (uuid ? 36 : 64)) throw new IllegalArgumentException("length");
    q.primitive(
        uuid ? "typed.uuid" : "typed.hash",
        s.length(),
        () -> {
          for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean dash = uuid && (i == 8 || i == 13 || i == 18 || i == 23);
            if (dash ? c != '-' : !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))
              throw new IllegalArgumentException("hex");
          }
          return null;
        });
  }

  private static void nodeValid(SessionLineage.PublicNode n, SessionQueryContext q) {
    q.operation(5);
    hex(n.sessionId(), true, q);
    hex(n.genesisSha256(), false, q);
    boolean continuation = "CONTINUATION".equals(n.kind());
    q.operation(n.kind().length());
    if (!continuation && !"ROOT".equals(n.kind())) throw new IllegalArgumentException("kind");
    if (continuation != (n.parentSessionId() != null)) throw new IllegalArgumentException("parent");
    if (n.parentSessionId() != null) hex(n.parentSessionId(), true, q);
    String t = n.createdAt();
    if (t == null || t.length() != 27) throw new IllegalArgumentException("time");
    q.primitive(
        "typed.time-pattern",
        27,
        () -> {
          for (int i = 0; i < 27; i++) {
            char c = t.charAt(i);
            char expected =
                switch (i) {
                  case 4, 7 -> '-';
                  case 10 -> 'T';
                  case 13, 16 -> ':';
                  case 19 -> '.';
                  case 26 -> 'Z';
                  default -> 0;
                };
            if (expected == 0 ? c < '0' || c > '9' : c != expected)
              throw new IllegalArgumentException("time pattern");
          }
          return null;
        });
    q.primitive(
        "typed.time-format",
        29,
        () -> {
          if (t.charAt(11) > '2' || t.charAt(11) == '2' && t.charAt(12) > '3')
            throw new IllegalArgumentException("hour");
          return java.time.Instant.parse(t);
        });
  }

  private static final class Sink extends Writer {
    private final StringBuilder buffer = new StringBuilder(8192);
    private final SessionQueryContext q;

    Sink(SessionQueryContext q) {
      this.q = q;
    }

    public void write(char[] b, int start, int count) {
      if (count > 8192 - buffer.length()) throw new IllegalArgumentException("output size");
      q.primitive(
          "serialize",
          count,
          () -> {
            buffer.append(b, start, count);
            return null;
          });
    }

    public void flush() {}

    public void close() {}
  }
}
