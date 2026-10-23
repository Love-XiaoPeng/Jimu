package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class BusinessProfilesTest {
  private static ModelSnapshot model() {
    return new ModelSnapshot(
        2,
        "flash",
        1,
        BusinessPolicies.STRATEGY,
        "https://api.deepseek.com",
        "deepseek-flash",
        "DeepSeek-V4.1-Flash-1017",
        "bearer",
        "env:UNAVAILABLE_BUSINESS_FIXTURE_V1",
        1,
        "cloud-https-v1",
        2000,
        20000,
        1048576,
        true,
        4096,
        "max_tokens",
        10,
        new ModelSnapshot.Capabilities(
            true, true, true, true, true, true, false, 1000000, "deepseek-nonthinking-v1"),
        Map.of(),
        true,
        new ModelSnapshot.Price(
            "synthetic-not-provider-price",
            "CNY",
            1000,
            2000,
            0,
            1000000,
            "full-context-reservation-not-cache-estimate"),
        null);
  }

  private static BusinessPolicies.Policy policy(ModelSnapshot model) {
    return new BusinessPolicies.Policy(
        1,
        "v1",
        "BUSINESS",
        new ExecutionScopes.Requirement(
            1, "XIAO-40", "instance", "schema", "tenant", "original-run", "a".repeat(64)),
        "b".repeat(64),
        Map.of(model.profile(), ModelProfiles.digest(model)),
        BusinessPolicies.STRATEGY,
        false);
  }

  private static void flatten(Map<String, Object> out, String prefix, JsonNode node) {
    node.properties()
        .forEach(
            entry -> {
              String key =
                  entry
                      .getKey()
                      .replaceAll("([a-z])([A-Z])", "$1-$2")
                      .toLowerCase(java.util.Locale.ROOT);
              if (entry.getValue().isObject()) flatten(out, prefix + key + ".", entry.getValue());
              else if (!entry.getValue().isNull()) out.put(prefix + key, entry.getValue().asText());
            });
  }

  private static Map<String, Object> properties(ModelSnapshot model) {
    var p = new HashMap<String, Object>();
    flatten(p, "jimu.model.profiles.flash.", Payloads.read(Payloads.json(model), JsonNode.class));
    flatten(
        p,
        "jimu.business.policies.v1.",
        Payloads.read(Payloads.json(policy(model)), JsonNode.class));
    p.put("jimu.business.current-ref", "v1");
    p.put("jimu.model.default-profile-ref", "flash");
    p.put("jimu.model.allowed-origins[0]", "https://api.deepseek.com");
    p.put("jimu.model.evaluation-run-id", "original-run");
    p.put("jimu.model.paid-dispatch-enabled", "false");
    return p;
  }

  private static ModelProfiles profiles(Map<String, Object> p) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove("systemEnvironment");
    env.getPropertySources().remove("systemProperties");
    env.getPropertySources().addFirst(new MapPropertySource("fixture", p));
    return new ModelProfiles(env);
  }

  @Test
  void explicitStrategyHasExactNonthinkingWireAndStaysClosed() {
    var model = model();
    var profiles = profiles(properties(model));
    assertEquals(model, profiles.selected("tenant"));
    assertEquals(
        "MODEL_COST_BOUND_UNAVAILABLE",
        assertThrows(TaskFailure.class, () -> profiles.admit(model)).code());
    var body =
        Payloads.read(
            new OpenAiProtocol()
                .body(
                    model,
                    List.of(new WireMessage("user", "normal business", List.of(), List.of())),
                    List.of()),
            JsonNode.class);
    assertEquals("deepseek-flash", body.path("model").asText());
    assertEquals("disabled", body.path("thinking").path("type").asText());
    assertEquals(4086, body.path("max_tokens").asInt());
    for (String field :
        List.of(
            "n",
            "parallel_tool_calls",
            "enable_thinking",
            "enable_search",
            "max_completion_tokens")) assertFalse(body.has(field));
  }

  @Test
  void unapprovedStrategiesAndProfileChangesCannotLoosenLegacyLimits() {
    var p = properties(model());
    p.keySet().removeIf(k -> k.startsWith("jimu.business."));
    assertThrows(IllegalArgumentException.class, () -> profiles(p));
    for (var mutation :
        Map.of(
                "model",
                "deepseek-reasoner",
                "output-limit-field",
                "max_completion_tokens",
                "output-margin-tokens",
                "0",
                "base-url",
                "https://api.deepseek.com/v1",
                "provider-options.enable_thinking",
                "true",
                "capabilities.parallel-tool-field",
                "true")
            .entrySet()) {
      var changed = properties(model());
      changed.put("jimu.model.profiles.flash." + mutation.getKey(), mutation.getValue());
      assertThrows(IllegalArgumentException.class, () -> profiles(changed));
    }
  }

  @Test
  void retainedPolicyCannotFollowDefaultsOrBeSilentlyReplaced() {
    var p = properties(model());
    var original = profiles(p).businessPolicy();
    var changed = new HashMap<>(p);
    changed.put("jimu.business.policies.v1.instruction-digest", "c".repeat(64));
    assertEquals(
        "BUSINESS_POLICY_CONFLICT",
        assertThrows(TaskFailure.class, () -> profiles(changed).retainedBusiness(original)).code());
  }

  @Test
  void streamUsageOnFinalNonemptyChoiceIsProviderFact() throws Exception {
    String sse =
        "data: {\"id\":\"local\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":10},\"completion_tokens_details\":{\"reasoning_tokens\":0}}}\n\ndata: [DONE]\n\n";
    var response =
        new OpenAiProtocol().parse(sse.getBytes(StandardCharsets.UTF_8), true, List.of());
    assertEquals(150L, response.usage().totalTokens());
    assertEquals(10L, response.usage().cachedTokens());
    assertEquals(
        10L,
        new OpenAiProtocol()
            .parse(
                sse.replace(
                        "\"prompt_tokens_details\":{\"cached_tokens\":10}",
                        "\"prompt_cache_hit_tokens\":10")
                    .getBytes(StandardCharsets.UTF_8),
                true,
                List.of())
            .usage()
            .cachedTokens());
  }
}
