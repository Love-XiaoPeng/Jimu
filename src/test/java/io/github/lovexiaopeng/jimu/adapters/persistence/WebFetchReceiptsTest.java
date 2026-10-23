package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.PublicWebPolicy;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebFetchReceiptsTest {
  @Test
  void admittedMixedCaseDomainCanPreserveItsExactOriginalInput() {
    String url = "https://EXAMPLE.com/";
    assertEquals(url, new PublicWebPolicy(Set.of("example.com")).target(url).toString());
    String input = Payloads.json(new FetchInput(url));
    String output = Payloads.json(new FetchOutput(200, url, "Safe local fixture", false));
    var task = new HashMap<String, Object>();
    task.put("id", "task");
    task.put("tenant_id", "tenant");
    snapshot(task, "request", "{}");
    snapshot(task, "model", "{}");
    snapshot(
        task,
        "tools",
        Payloads.json(
            new ToolSnapshot(
                1,
                Map.of(
                    "web_fetch", new PluginPin("0.3.0", BuiltinManifestLoader.OUTPUT_SHA256, 1)))));
    var step = new HashMap<String, Object>();
    step.put("id", "step");
    step.put("task_id", "task");
    step.put("tenant_id", "tenant");
    snapshot(
        step,
        "request",
        Payloads.json(Map.of("requestBody", "{}", "bodyDigest", Payloads.hash("{}"))));
    snapshot(
        step,
        "response",
        Payloads.json(
            Map.of(
                "calls", List.of(Map.of("id", "call", "name", "web_fetch", "arguments", input)))));
    var invocation = new HashMap<String, Object>();
    invocation.putAll(Map.of("id", "invocation", "task_id", "task", "step_id", "step"));
    invocation.put("tenant_id", "tenant");
    invocation.put("tool", "web_fetch");
    invocation.put("provider_id", "call");
    invocation.put("slot", "0:call");
    invocation.put("attempt_id", "attempt");
    invocation.put("effect_intent_id", "effect");
    invocation.put("plugin_version", "0.3.0");
    invocation.put("plugin_digest", BuiltinManifestLoader.OUTPUT_SHA256);
    invocation.put("installation_epoch", 1L);
    snapshot(invocation, "input", input);
    assertEquals(64, WebFetchReceipts.digest(task, step, invocation, output).length());
    assertEquals(input, invocation.get("input_snapshot"));
    assertEquals(Payloads.hash(input), invocation.get("input_digest"));
  }

  private static void snapshot(Map<String, Object> row, String name, String body) {
    row.put(name + "_snapshot", body);
    row.put(name + "_digest", Payloads.hash(body));
  }
}
