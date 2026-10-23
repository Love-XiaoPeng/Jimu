package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual host-loaded bytes, not client labels or self-reported material hashes. */
public final class EvaluationMaterials {
  public record Material(
      int version,
      String commonDigest,
      String sample,
      String goal,
      String systemTemplate,
      JsonNode tools,
      Map<String, JsonNode> sources,
      String target,
      String initialContent) {
    public Material {
      sources = Map.copyOf(sources);
    }
  }

  private EvaluationMaterials() {}

  private static byte[] bytes(String path) {
    try (var input = EvaluationMaterials.class.getResourceAsStream("/evaluation/frozen/" + path)) {
      if (input == null) throw new IllegalStateException("Frozen evaluation material unavailable");
      return input.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("Frozen material unavailable", e);
    }
  }

  private static String text(String path) {
    return new String(bytes(path), StandardCharsets.UTF_8);
  }

  private static JsonNode node(String path) {
    return Payloads.read(text(path), JsonNode.class);
  }

  private static void reference(JsonNode ref) {
    byte[] raw = bytes(ref.path("path").asText());
    if (raw.length != ref.path("bytes").asLong()
        || !Payloads.hash(raw).equals(ref.path("sha256").asText()))
      throw new IllegalStateException("Frozen material digest mismatch");
  }

  public static Material load(EvaluationPlans.Plan plan, String sample) {
    if (!Payloads.hash(bytes("common-materials.json")).equals(plan.materialsDigest()))
      throw new IllegalStateException("Frozen common materials mismatch");
    node("common-materials.json").path("references").forEach(EvaluationMaterials::reference);
    JsonNode selected = null;
    for (var candidate : node("samples.json").path("samples"))
      if (candidate.path("id").asText().equals(sample)) selected = candidate;
    if (selected == null) throw new IllegalArgumentException("Unknown frozen sample");
    reference(selected.path("goal"));
    reference(selected.path("request"));
    var sources = new LinkedHashMap<String, JsonNode>();
    for (var id : selected.path("sourceIds")) {
      for (var source : node("sources/source-manifest.json").path("sources")) {
        if (!id.asText().equals(source.path("id").asText())) continue;
        String path = source.path("canonicalOutputPath").asText();
        if (!Payloads.hash(bytes(path)).equals(source.path("canonicalOutputSha256").asText()))
          throw new IllegalStateException("Frozen source mismatch");
        sources.put(source.path("url").asText(), node(path));
      }
    }
    if (sources.size() != selected.path("sourceIds").size())
      throw new IllegalStateException("Frozen source missing");
    String draft = null;
    if (sample.equals("W4")) {
      var seed = node("drafts/seed-manifest.json");
      reference(seed.path("file"));
      draft = text(seed.path("file").path("path").asText());
    }
    return new Material(
        1,
        plan.materialsDigest(),
        sample,
        text(selected.path("goal").path("path").asText()),
        text("prompts/host-system.template.txt").stripTrailing(),
        node("contracts/tools.json"),
        sources,
        selected.path("target").asText(),
        draft);
  }

  public static void require(boolean valid) {
    if (!valid) throw new TaskFailure(409, "EVALUATION_MATERIAL_MISMATCH");
  }

  public static void body(Material material, String request, String reportTime) {
    var body = Payloads.read(request, JsonNode.class);
    require(body.path("tools").equals(material.tools()));
    var messages = body.path("messages");
    require(messages.isArray() && messages.size() >= 2);
    require(
        messages.get(0).path("role").asText().equals("system")
            && messages
                .get(0)
                .path("content")
                .asText()
                .equals(material.systemTemplate().replace("{{HOST_REPORT_TIME}}", reportTime)));
    require(
        messages.get(1).path("role").asText().equals("user")
            && messages.get(1).path("content").asText().equals(material.goal()));
    var calls = new java.util.HashMap<String, JsonNode>();
    for (int i = 2; i < messages.size(); i++) {
      var message = messages.get(i);
      String role = message.path("role").asText();
      require(role.equals("assistant") || role.equals("tool"));
      if (role.equals("assistant")) {
        for (var call : message.path("tool_calls"))
          calls.put(call.path("id").asText(), call.path("function"));
      } else {
        var call = calls.get(message.path("tool_call_id").asText());
        require(call != null);
        if (call.path("name").asText().equals("web_fetch")) {
          var input = Payloads.read(call.path("arguments").asText(), JsonNode.class);
          source(material, input.path("url").asText(), message.path("content").asText());
        }
      }
    }
  }

  public static void source(Material material, String url, String output) {
    var expected = material.sources().get(url);
    require(expected != null && expected.equals(Payloads.read(output, JsonNode.class)));
  }
}
