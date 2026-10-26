package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.PublicWebPolicy;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** An event digest binds a restricted result to its original intent, snapshots and plugin pin. */
final class WebFetchReceipts {
  private WebFetchReceipts() {}

  static String digest(
      Map<String, Object> task,
      Map<String, Object> step,
      Map<String, Object> invocation,
      String output) {
    try {
      if (!ToolCapability.WEB_FETCH.equals(invocation.get("tool"))
          || !"0.3.0".equals(invocation.get("plugin_version"))
          || !BuiltinManifestLoader.OUTPUT_SHA256.equals(invocation.get("plugin_digest"))
          || output == null
          || output.getBytes(StandardCharsets.UTF_8).length > 2097152)
        throw new IllegalArgumentException("Invalid public result");
      requireHash(invocation, "input_snapshot", "input_digest");
      requireHash(task, "request_snapshot", "request_digest");
      requireHash(task, "model_snapshot", "model_digest");
      requireHash(task, "tools_snapshot", "tools_digest");
      requireHash(step, "request_snapshot", "request_digest");
      requireHash(step, "response_snapshot", "response_digest");
      JsonNode request = Payloads.read((String) step.get("request_snapshot"), JsonNode.class);
      if (!Payloads.hash(request.path("requestBody").asText())
          .equals(request.path("bodyDigest").asText()))
        throw new IllegalArgumentException("Invalid wire digest");
      var pins = Payloads.read((String) task.get("tools_snapshot"), ToolSnapshot.class);
      var pin = pins.pins().get(ToolCapability.WEB_FETCH);
      if (pins.schemaVersion() != 1
          || pin == null
          || !pin.version().equals(invocation.get("plugin_version"))
          || !pin.digest().equals(invocation.get("plugin_digest"))
          || pin.installationEpoch() != ((Number) invocation.get("installation_epoch")).longValue()
          || !task.get("tenant_id").equals(invocation.get("tenant_id"))
          || !task.get("id").equals(invocation.get("task_id"))
          || !step.get("id").equals(invocation.get("step_id"))
          || !step.get("task_id").equals(task.get("id"))
          || !step.get("tenant_id").equals(task.get("tenant_id")))
        throw new IllegalArgumentException("Invalid result binding");
      // Match the original assistant tool call, not a caller's assertion of an input digest.
      JsonNode response = Payloads.read((String) step.get("response_snapshot"), JsonNode.class);
      int matches = 0;
      int identities = 0;
      int index = 0;
      for (var call : response.path("calls")) {
        if (call.path("id").asText().equals(invocation.get("provider_id"))) identities++;
        if (call.path("id").asText().equals(invocation.get("provider_id"))
            && call.path("name").asText().equals(ToolCapability.WEB_FETCH)
            && call.path("arguments").asText().equals(invocation.get("input_snapshot"))
            && (index + ":" + call.path("id").asText()).equals(invocation.get("slot"))) matches++;
        index++;
      }
      if (matches != 1 || identities != 1)
        throw new IllegalArgumentException("Original tool call missing or ambiguous");
      new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"))
          .validate(Payloads.read(output, JsonNode.class));
      var result = Payloads.read(output, FetchOutput.class);
      var input = Payloads.read((String) invocation.get("input_snapshot"), FetchInput.class);
      new PublicWebPolicy(Set.of(URI.create(input.url()).getHost().toLowerCase(Locale.ROOT)))
          .target(result.sourceUrl());
      var receipt = new TreeMap<String, Object>();
      receipt.put("version", 1);
      receipt.put("outputDigest", Payloads.hash(output));
      for (String key :
          Set.of(
              "tenant_id",
              "id",
              "task_id",
              "step_id",
              "attempt_id",
              "effect_intent_id",
              "slot",
              "provider_id",
              "tool",
              "input_digest",
              "plugin_version",
              "plugin_digest",
              "installation_epoch")) receipt.put("invocation." + key, invocation.get(key));
      receipt.put("task.toolsDigest", task.get("tools_digest"));
      receipt.put("task.requestDigest", task.get("request_digest"));
      receipt.put("task.modelDigest", task.get("model_digest"));
      receipt.put("step.requestDigest", step.get("request_digest"));
      receipt.put("step.responseDigest", step.get("response_digest"));
      return Payloads.hash(Payloads.json(receipt));
    } catch (IOException | IllegalArgumentException invalid) {
      throw new TaskFailure(409, TaskErrorCode.WEB_RESULT_PROOF_INVALID);
    }
  }

  private static void requireHash(Map<String, Object> row, String body, String digest) {
    if (!(row.get(body) instanceof String value) || !Payloads.hash(value).equals(row.get(digest)))
      throw new IllegalArgumentException("Invalid snapshot digest");
  }
}
