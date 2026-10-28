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
  private final SchemaContract input, output, admission, genesis, graph;
  private final ToolDescriptor descriptor;
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
      graph = contract(catalog, "graph.schema.json");
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

  public String genesis(SessionLineage.Genesis g) {
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
    String raw = Payloads.json(value);
    stored(genesis, raw, 1024);
    return raw;
  }

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
    String raw =
        Payloads.json(
            Map.of(
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
                    .toList()));
    stored(graph, raw, 16384);
    return raw;
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
    String raw = Payloads.json(value);
    output(raw);
    return raw;
  }
}
