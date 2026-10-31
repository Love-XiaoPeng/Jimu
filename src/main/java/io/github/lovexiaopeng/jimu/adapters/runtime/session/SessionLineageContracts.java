package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5权威资源、严格输入和可清理证明的显式wire映射；不采用全局JSON命名策略。 */
public final class SessionLineageContracts {
  private final SessionContracts history = new SessionContracts(5);
  private final SchemaContract input, output, admission, genesis, genesisTime, graph, graphVersion;
  private final ToolDescriptor descriptor;
  private final LineageGraphCodecs codecs;
  private final String schema, description;

  public SessionLineageContracts() {
    try {
      var catalog =
          history.json(
              new String(SessionContracts.resource("v5/catalog-v5.json"), StandardCharsets.UTF_8),
              8192);
      var entry = catalog.path("tools").get(2);
      String root = "v5/session_trace-0.1.0/";
      byte[] in =
          SessionContracts.checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] out =
          SessionContracts.checked(
              root + "output.schema.json", entry.path("outputSha256").asText());
      SessionContracts.checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      description =
          new String(
              SessionContracts.checked(
                  root + "description.txt", entry.path("descriptionSha256").asText()),
              StandardCharsets.UTF_8);
      input = new SchemaContract(in);
      output = new SchemaContract(out);
      schema = new String(in, StandardCharsets.UTF_8);
      admission = contract(catalog, "admission-input.schema.json");
      genesis = contract(catalog, "genesis.schema.json");
      // Keep the pinned format assertion implementation (including RFC3339 edge cases).
      var genesisSchema =
          history.json(
              new String(
                  SessionContracts.checked(
                      "v5/genesis.schema.json",
                      catalog.path("schemas").path("genesis.schema.json").asText()),
                  StandardCharsets.UTF_8),
              16384);
      genesisTime =
          new SchemaContract(
              Payloads.json(
                      Map.of(
                          "$schema",
                          genesisSchema.path("$schema"),
                          "allOf",
                          List.of(genesisSchema.path("properties").path("created_at"))))
                  .getBytes(StandardCharsets.UTF_8));
      graph = contract(catalog, "graph.schema.json");
      var graphSchema =
          history.json(
              new String(
                  SessionContracts.checked(
                      "v5/graph.schema.json",
                      catalog.path("schemas").path("graph.schema.json").asText()),
                  StandardCharsets.UTF_8),
              16384);
      codecs = new LineageGraphCodecs(history);
      graphVersion =
          new SchemaContract(
              Payloads.json(
                      Map.of(
                          "$schema", graphSchema.path("$schema"),
                          "anyOf",
                              List.of(
                                  graphSchema.path("properties").path("version"), codecs.version)))
                  .getBytes(StandardCharsets.UTF_8));
      descriptor =
          new ToolDescriptor(
              "jimu.session.trace",
              "0.1.0",
              ToolCapability.SESSION_TRACE,
              "session/" + root + "input.schema.json",
              "session/" + root + "output.schema.json",
              Payloads.hash(out),
              "io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionLineagePlugin");
    } catch (IOException failure) {
      throw new IllegalStateException("Packaged lineage contract unavailable", failure);
    }
  }

  private SchemaContract contract(JsonNode catalog, String name) throws IOException {
    return new SchemaContract(
        SessionContracts.checked("v5/" + name, catalog.path("schemas").path(name).asText()));
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public String inputSchema() {
    return schema;
  }

  public String description() {
    return description;
  }

  public record Admission(String goal, List<String> historySessionIds, String parentSessionId) {
    public Admission {
      historySessionIds = List.copyOf(historySessionIds);
    }
  }

  public Admission admission(byte[] raw) {
    try {
      var value = history.strictJson(PriorSessionContracts.utf8(raw), 16384);
      admission.validate(value);
      String goal = value.path("goal").textValue();
      if (goal.isBlank() || SessionContracts.bytes(goal) > 8192) throw invalid();
      var ids = new ArrayList<String>();
      for (var id : value.path("history_session_ids")) ids.add(id.textValue());
      String parent =
          value.has("parent_session_id") ? value.path("parent_session_id").textValue() : null;
      if (parent != null && !ids.contains(parent))
        throw new TaskFailure(400, SessionErrorCode.SESSION_LINEAGE_SCOPE_INVALID);
      return new Admission(goal, ids.stream().sorted().toList(), parent);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  public String input(byte[] raw) {
    return input(PriorSessionContracts.utf8(raw));
  }

  public String input(String raw) {
    try {
      var value = history.strictJson(raw, 1024);
      input.validate(value);
      return value.has("session_id") ? value.path("session_id").textValue() : null;
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static TaskFailure invalid() {
    return new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
  }

  private JsonNode stored(SchemaContract contract, String raw, int max) {
    if (SessionContracts.bytes(raw) > max)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    try {
      var value = history.json(raw, max);
      contract.validate(value);
      return value;
    } catch (IllegalArgumentException | TaskFailure failure) {
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    }
  }

  public void output(String raw) {
    stored(output, raw, 8192);
  }

  public SessionLineage.Genesis genesis(String raw) {
    var g = stored(genesis, raw, 1024);
    return genesisValue(g);
  }

  public SessionLineage.Genesis genesis(
      LineageRawView raw,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    if (raw.bytes() > 1024) throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    Object tree = new Object();
    query.retainScratch(tree, raw.bytes() * 6L + 4096);
    try {
      var value =
          query.primitive("parse.genesis", raw.raw().length(), () -> history.json(raw, 1024));
      query.primitive(
          "schema.genesis",
          raw.raw().length(),
          () -> {
            genesis.validate(value);
            return null;
          });
      return genesisValue(value);
    } catch (IllegalArgumentException | TaskFailure failure) {
      if (failure instanceof TaskFailure refusal
          && (refusal.status() == 429 || refusal.status() == 504)) throw refusal;
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    } finally {
      query.releaseScratch(tree);
    }
  }

  private static SessionLineage.Genesis genesisValue(JsonNode g) {
    return new SessionLineage.Genesis(
        g.path("version").intValue(),
        g.path("session_id").textValue(),
        g.path("kind").textValue(),
        g.path("created_at").textValue(),
        g.path("request_sha256").textValue(),
        g.path("authority_sha256").textValue(),
        g.path("binding_sha256").textValue(),
        g.path("parent_session_id").textValue(),
        g.path("parent_genesis_sha256").textValue());
  }

  public void matchGraph(
      LineageRawView raw,
      SessionLineage.Graph expected,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    if (raw.bytes() > 16384) throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    new LineageGraphMatcher(
            raw.raw(),
            query,
            number -> {
              var value =
                  query.primitive(
                      "parse.graph.number", number.length(), () -> history.json(number, 16384));
              query.primitive(
                  "schema.graph.number",
                  number.length(),
                  () -> {
                    graphVersion.validate(value);
                    return null;
                  });
              return value.intValue();
            },
            codecs.schemaSha256)
        .match(expected);
  }

  public String genesis(SessionLineage.Genesis g) {
    String raw = Payloads.json(genesisWire(g));
    stored(genesis, raw, 1024);
    return raw;
  }

  public LineageRawView genesis(
      SessionLineage.Genesis g,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    Object owner = new Object();
    query.retainScratch(owner, 12288);
    Map<String, Object> wire = null;
    try {
      // Only this local typed-to-wire route can omit parsing its own writer output.
      // The externally supplied LineageRawView overload always parses and validates anew.
      // Preserve writer/UTF8 limits before schema rejection, including malformed typed input.
      // Prepay the bounded map construction, up to nine entries and their final clearing.
      query.operation(40);
      wire = genesisWire(g);
      var raw = LineageJsonWriter.encode(wire, 1024, query);
      query.operation(24);
      if (g.version() != 1) throw corrupt();
      generatedText(g.sessionId(), UUID_PATTERN, query);
      generatedText(g.requestSha256(), HASH_PATTERN, query);
      generatedText(g.authoritySha256(), HASH_PATTERN, query);
      generatedText(g.bindingSha256(), HASH_PATTERN, query);
      query.operation(20);
      boolean continuation = "CONTINUATION".equals(g.kind());
      if (!continuation && !"ROOT".equals(g.kind())) throw corrupt();
      if (g.createdAt() == null) throw corrupt();
      query.primitive(
          "schema.genesis.time",
          g.createdAt().length(),
          () -> {
            genesisTime.validateObject(g.createdAt());
            return null;
          });
      query.operation(5);
      if (g.parentSessionId() != null) {
        if (!continuation) throw corrupt();
        generatedText(g.parentSessionId(), UUID_PATTERN, query);
        generatedText(g.parentGenesisSha256(), HASH_PATTERN, query);
      } else if (continuation) throw corrupt();
      // genesisWire historically omits both parent fields when parentSessionId is null.
      return raw;
    } catch (IllegalArgumentException failure) {
      throw corrupt();
    } finally {
      if (wire != null) wire.clear();
      wire = null;
      query.releaseScratch(owner);
    }
  }

  private static final java.util.regex.Pattern UUID_PATTERN =
      java.util.regex.Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final java.util.regex.Pattern HASH_PATTERN =
      java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static void generatedText(
      String value,
      java.util.regex.Pattern pattern,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    query.operation(3);
    if (value == null) throw corrupt();
    if (!query.primitive(
        "schema.genesis.scalar", value.length(), () -> pattern.matcher(value).matches()))
      throw corrupt();
  }

  private static Map<String, Object> genesisWire(SessionLineage.Genesis g) {
    var value = new LinkedHashMap<String, Object>();
    value.put("version", g.version());
    value.put("session_id", g.sessionId());
    value.put("kind", g.kind());
    value.put("created_at", g.createdAt());
    value.put("request_sha256", g.requestSha256());
    value.put("authority_sha256", g.authoritySha256());
    value.put("binding_sha256", g.bindingSha256());
    if (g.parentSessionId() != null) {
      value.put("parent_session_id", g.parentSessionId());
      value.put("parent_genesis_sha256", g.parentGenesisSha256());
    }
    return value;
  }

  /** Legacy complete graph-v1 only. Internal v2 requires matchGraph with fresh verified sources. */
  public SessionLineage.Graph graph(String raw) {
    var g = stored(graph, raw, 16384);
    var nodes = new ArrayList<SessionLineage.Node>();
    for (var n : g.path("nodes"))
      nodes.add(
          new SessionLineage.Node(
              n.path("session_id").textValue(),
              n.path("genesis_snapshot").textValue(),
              n.path("genesis_sha256").textValue()));
    return new SessionLineage.Graph(
        g.path("version").intValue(),
        g.path("caller_session_id").textValue(),
        g.path("authority_sha256").textValue(),
        nodes);
  }

  public String graph(SessionLineage.Graph g) {
    String raw = Payloads.json(graphWire(g));
    stored(graph, raw, 16384);
    return raw;
  }

  public LineageRawView graph(
      SessionLineage.Graph g,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    var raw = LineageJsonWriter.encode(graphWire(g), 16384, query);
    matchGraph(raw, g, query);
    return raw;
  }

  /** Internal persistence only; the public/legacy graph encoder remains graph-v1. */
  public LineageRawView storageGraph(
      SessionLineage.Graph g,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      java.util.function.Consumer<LineageRawView> handoff) {
    Object owner = new Object();
    query.retainScratch(owner, 32768L + 4096L + 512L * g.nodes().size());
    Map<String, Object> wire = null;
    List<Map<String, Object>> nodes = null;
    try {
      query.operation(12L + 7L * g.nodes().size());
      nodes = new ArrayList<>(g.nodes().size());
      for (var n : g.nodes())
        nodes.add(Map.of("session_id", n.sessionId(), "genesis_sha256", n.genesisSha256()));
      wire =
          Map.of(
              "version",
              2,
              "codec_id",
              LineageGraphCodecs.ID,
              "schema_sha256",
              codecs.schemaSha256,
              "caller_session_id",
              g.callerSessionId(),
              "authority_sha256",
              g.authoritySha256(),
              "nodes",
              nodes);
      var raw = LineageJsonWriter.encode(wire, 16384, query);
      // Fixed keys/pins came from the host mapping. Check typed scalar/count/order
      // constraints after the original size refusal, without reparsing the writer output.
      LineageGraphMatcher.validateExpected(g, query);
      query.operation(3);
      handoff.accept(raw);
      return raw;
    } finally {
      // Cleanup was prepaid above. Clear aliases before refund, including Error exits.
      if (nodes != null) {
        nodes.clear();
      }
      wire = null;
      nodes = null;
      query.releaseScratch(owner);
    }
  }

  private static Map<String, Object> graphWire(SessionLineage.Graph g) {
    return Map.of(
        "version",
        g.version(),
        "caller_session_id",
        g.callerSessionId(),
        "authority_sha256",
        g.authoritySha256(),
        "nodes",
        g.nodes().stream()
            .map(
                n ->
                    Map.of(
                        "session_id",
                        n.sessionId(),
                        "genesis_snapshot",
                        n.genesisSnapshot(),
                        "genesis_sha256",
                        n.genesisSha256()))
            .toList());
  }

  private static Map<String, Object> node(SessionLineage.PublicNode n) {
    var value = new LinkedHashMap<String, Object>();
    value.put("session_id", n.sessionId());
    value.put("kind", n.kind());
    value.put("created_at", n.createdAt());
    value.put("genesis_sha256", n.genesisSha256());
    if (n.parentSessionId() != null) value.put("parent_session_id", n.parentSessionId());
    return value;
  }

  public String output(SessionLineage.Result result) {
    String raw = Payloads.json(outputWire(result));
    output(raw);
    return raw;
  }

  public LineageRawView output(
      SessionLineage.Result result,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    var raw = LineageJsonWriter.encode(outputWire(result), 8192, query);
    Object tree = new Object();
    query.retainScratch(tree, raw.bytes() * 6L + 4096);
    try {
      var value =
          query.primitive("parse.output", raw.raw().length(), () -> history.json(raw, 8192));
      query.primitive(
          "schema.output",
          raw.raw().length(),
          () -> {
            output.validate(value);
            return null;
          });
      return raw;
    } catch (IllegalArgumentException failure) {
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    } finally {
      query.releaseScratch(tree);
    }
  }

  private static Map<String, Object> outputWire(SessionLineage.Result result) {
    var value = new LinkedHashMap<String, Object>();
    value.put("version", 1);
    value.put("caller_session_id", result.caller());
    value.put("lineage_policy", "owner-declared-continuation-v1");
    value.put("coverage", "admission-scope");
    value.put("complete_within_scope", true);
    value.put("authority_sha256", result.authority());
    value.put("graph_sha256", result.graph());
    value.put("target", node(result.target()));
    value.put("root", node(result.root()));
    value.put("ancestors", result.ancestors().stream().map(SessionLineageContracts::node).toList());
    value.put(
        "descendants",
        result.descendants().stream()
            .map(d -> Map.of("node", node(d.node()), "depth", d.depth()))
            .toList());
    return value;
  }
}
