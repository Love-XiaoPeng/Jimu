package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ModelProfilesTest {
  @Test
  void largerWholeRequestLimitRequiresTheExactUnpaidLocalProof() {
    var p = local();
    String prefix = "jimu.model.profiles.local.";
    p.put("jimu.test-mode", "true");
    p.put(prefix + "paid", "false");
    p.put(prefix + "model", "local-byte-accounted-v1");
    p.put(prefix + "revision", "local-byte-accounted-v1");
    p.put(prefix + "input-limit.version", "1");
    p.put(prefix + "input-limit.max-tokens", "65536");
    p.put(prefix + "input-limit.proof", "test-whole-request-utf8-v1");
    var allowed = profiles(p);
    allowed.checkInput(allowed.selected("tenant"), "x".repeat(65536));
    assertThrows(
        TaskFailure.class, () -> allowed.checkInput(allowed.selected("tenant"), "x".repeat(65537)));
    for (String key :
        new String[] {
          "jimu.test-mode", prefix + "model", prefix + "revision", prefix + "input-limit.proof"
        }) {
      var denied = new HashMap<>(p);
      denied.put(key, key.equals("jimu.test-mode") ? "false" : "unproven");
      assertThrows(IllegalArgumentException.class, () -> profiles(denied));
    }
    p.put(prefix + "input-limit.max-tokens", "65537");
    assertThrows(IllegalArgumentException.class, () -> profiles(p));
  }

  @Test
  void closedPlanNeverEnablesCloudEvenWithDispatchAndTestFlags() {
    var p = local();
    p.put("jimu.test-mode", "true");
    p.put("jimu.model.evaluation-plan-ref", "qwen-main-v1");
    p.put("jimu.model.evaluation-phase", "A");
    p.put("jimu.model.evaluation-sample", "W1");
    p.put("jimu.model.evaluation-run-id", "original-run");
    p.put("jimu.model.paid-dispatch-enabled", "true");
    p.put("jimu.model.allowed-origins[0]", "https://dashscope.aliyuncs.com");
    String prefix = "jimu.model.profiles.local.";
    Map.ofEntries(
            Map.entry("base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            Map.entry("model", "qwen3.7-plus-2026-05-26"),
            Map.entry("revision", "qwen3.7-plus-2026-05-26"),
            Map.entry("transport-policy", "cloud-https-v1"),
            Map.entry("paid", "true"),
            Map.entry("max-output-tokens", "4096"),
            Map.entry("output-limit-field", "max_completion_tokens"),
            Map.entry("output-margin-tokens", "10"),
            Map.entry("capabilities.context-limit", "1000000"),
            Map.entry("capabilities.evidence", "modelstudio-nonthinking-v1"),
            Map.entry("capabilities.stream-text", "true"),
            Map.entry("capabilities.stream-tools", "true"),
            Map.entry("capabilities.usage-call", "true"),
            Map.entry("capabilities.usage-stream", "true"),
            Map.entry("provider-options.enable_thinking", "false"),
            Map.entry("provider-options.enable_search", "false"),
            Map.entry("price.version", "reviewed-list-20261016"),
            Map.entry("price.currency", "CNY"),
            Map.entry("price.input-micros-per-million", "6000000"),
            Map.entry("price.output-micros-per-million", "24000000"),
            Map.entry("price.input-token-upper", "1000000"),
            Map.entry("price.bound-evidence", "reviewed-full-context"))
        .forEach((k, v) -> p.put(prefix + k, v));
    var profiles = profiles(p);
    assertEquals(
        "MODEL_EVALUATION_CLOSED",
        assertThrows(TaskFailure.class, () -> profiles.admit(profiles.selected("tenant"))).code());
    p.remove("jimu.model.evaluation-plan-ref");
    assertThrows(IllegalArgumentException.class, () -> profiles(p));
  }

  @Test
  void secretEnvironmentNamesAreNotTreatedAsProfileProperties() {
    var env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "secret-env", Map.of("JIMU_MODEL_KEY_V1", "synthetic")));
    env.getPropertySources().addFirst(new MapPropertySource("test", local()));
    assertEquals("local", new ModelProfiles(env).selected("tenant").profile());
  }

  private static Map<String, Object> local() {
    var p = new HashMap<String, Object>();
    p.put("jimu.model.default-profile-ref", "local");
    p.put("jimu.model.allowed-origins[0]", "http://127.0.0.1:12345");
    String prefix = "jimu.model.profiles.local.";
    Map.ofEntries(
            Map.entry("version", "2"),
            Map.entry("profile", "local"),
            Map.entry("config-version", "1"),
            Map.entry("adapter", "openai-chat-v1"),
            Map.entry("base-url", "http://127.0.0.1:12345/prefix/v1/"),
            Map.entry("model", "local-model"),
            Map.entry("revision", "local-v1"),
            Map.entry("auth-mode", "none"),
            Map.entry("transport-policy", "trusted-local-v1"),
            Map.entry("connect-timeout-ms", "2000"),
            Map.entry("request-timeout-ms", "20000"),
            Map.entry("response-bytes", "1048576"),
            Map.entry("max-output-tokens", "512"),
            Map.entry("output-limit-field", "max_tokens"),
            Map.entry("capabilities.call", "true"),
            Map.entry("capabilities.tools", "true"),
            Map.entry("capabilities.context-limit", "8192"),
            Map.entry("capabilities.evidence", "local-test-v1"))
        .forEach((k, v) -> p.put(prefix + k, v));
    return p;
  }

  private static ModelProfiles profiles(Map<String, Object> p) {
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", p));
    return new ModelProfiles(env);
  }

  @Test
  void operatorAndRegistryShareFullStructureValidationWithoutCredentialResolution() {
    var valid = profiles(local()).selected("tenant");
    assertDoesNotThrow(() -> ModelProfiles.validateStructure(null, valid, false));
    for (var entry :
        Map.<String, Object>of(
                "version",
                3,
                "adapter",
                "unsupported",
                "configVersion",
                0,
                "connectTimeoutMs",
                0,
                "outputLimitField",
                "unsupported")
            .entrySet()) {
      var body = (ObjectNode) Payloads.read(Payloads.json(valid), JsonNode.class);
      body.set(entry.getKey(), new ObjectMapper().valueToTree(entry.getValue()));
      var invalid = Payloads.read(body.toString(), ModelSnapshot.class);
      assertThrows(
          IllegalArgumentException.class,
          () -> ModelProfiles.validateStructure(null, invalid, false));
    }
    assertThrows(
        IllegalArgumentException.class, () -> ModelProfiles.validateStructure(null, null, false));
    var auth = local();
    auth.put("jimu.model.profiles.local.auth-mode", "bearer");
    auth.put("jimu.model.profiles.local.credential-ref", "env:UNAVAILABLE_SCOPE_FIXTURE_V1");
    auth.put("jimu.model.profiles.local.credential-version", "1");
    var closed = profiles(auth).selected("tenant");
    assertDoesNotThrow(() -> ModelProfiles.validateStructure(null, closed, false));
  }

  @Test
  void bindingNormalizesPrefixAndDefaultsToNoPaidDispatch() {
    var models = profiles(local());
    var snapshot = models.selected("tenant");
    assertEquals("http://127.0.0.1:12345/prefix/v1", snapshot.baseUrl());
    models.admit(snapshot);
    assertTrue(models.ready());
    assertFalse(snapshot.paid());
    assertEquals(64, ModelProfiles.digest(snapshot).length());
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.providerOptions().put("x", true));
  }

  @Test
  void fixtureRequiresExplicitTestAssemblyAndUnknownPropertiesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> profiles(Map.of()));
    var p = local();
    p.put("jimu.model.profiles.local.api-key", "synthetic-not-a-key");
    assertThrows(RuntimeException.class, () -> profiles(p));
  }

  @Test
  void urlsCredentialsCapabilitiesAndPrivateFieldsFailClosed() {
    String prefix = "jimu.model.profiles.local.";
    for (String url :
        new String[] {
          "http://user@127.0.0.1:12345/v1",
          "http://127.0.0.1:12345/v1?q=x",
          "http://127.0.0.1:12345/v1#x",
          "http://127.0.0.1:12345/v1/chat/completions",
          "http://127.0.0.1:12346/v1"
        }) {
      var p = local();
      p.put(prefix + "base-url", url);
      assertThrows(RuntimeException.class, () -> profiles(p));
    }
    var auth = local();
    auth.put(prefix + "credential-ref", "env:JIMU_TEST_MISSING_V1");
    assertThrows(IllegalArgumentException.class, () -> profiles(auth));
    auth.put(prefix + "auth-mode", "bearer");
    auth.put(prefix + "credential-version", "1");
    var missing = profiles(auth);
    assertFalse(missing.ready());
    assertEquals(
        "CREDENTIAL_UNAVAILABLE",
        assertThrows(TaskFailure.class, () -> missing.admit(missing.selected("tenant"))).code());
    var tools = local();
    tools.put(prefix + "capabilities.tools", "false");
    assertEquals(
        "MODEL_CAPABILITY_UNSUPPORTED",
        assertThrows(TaskFailure.class, () -> profiles(tools)).code());
    var privateField = local();
    privateField.put(prefix + "provider-options.enable_search", "true");
    assertThrows(RuntimeException.class, () -> profiles(privateField));
  }

  @Test
  void bailianContextCannotBeShrunkToClaimSmallInputAndUnknownProofNeverAuthorizesIt() {
    var p = local();
    String prefix = "jimu.model.profiles.local.";
    p.put("jimu.model.allowed-origins[0]", "https://dashscope.aliyuncs.com");
    Map.ofEntries(
            Map.entry("base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            Map.entry("model", "qwen3.7-plus-2026-05-26"),
            Map.entry("revision", "qwen3.7-plus-2026-05-26"),
            Map.entry("transport-policy", "cloud-https-v1"),
            Map.entry("paid", "true"),
            Map.entry("capabilities.context-limit", "1000000"),
            Map.entry("capabilities.evidence", "modelstudio-nonthinking-v1"),
            Map.entry("output-limit-field", "max_completion_tokens"),
            Map.entry("output-margin-tokens", "10"),
            Map.entry("provider-options.enable_thinking", "false"),
            Map.entry("provider-options.enable_search", "false"),
            Map.entry("price.version", "official-context-20261016"),
            Map.entry("price.currency", "CNY"),
            Map.entry("price.input-micros-per-million", "6000000"),
            Map.entry("price.output-micros-per-million", "24000000"),
            Map.entry("price.input-token-upper", "1000000"),
            Map.entry("price.bound-evidence", "official-full-context-1000000"))
        .forEach((k, v) -> p.put(prefix + k, v));
    var full = profiles(p).selected("tenant");
    assertEquals(
        6012288,
        ModelCosts.amount(full.price(), full.price().inputTokenUpper(), full.maxOutputTokens()));
    for (String key : new String[] {"capabilities.context-limit", "price.input-token-upper"}) {
      var altered = new HashMap<>(p);
      altered.put(prefix + key, "8192");
      assertThrows(IllegalArgumentException.class, () -> profiles(altered));
    }
    for (String key : new String[] {"output-limit-field", "output-margin-tokens"}) {
      var altered = new HashMap<>(p);
      altered.put(prefix + key, key.endsWith("field") ? "max_tokens" : "0");
      assertThrows(IllegalArgumentException.class, () -> profiles(altered));
    }
    p.put(prefix + "price.input-token-upper", "8192");
    p.put(prefix + "input-limit.version", "1");
    p.put(prefix + "input-limit.max-tokens", "8192");
    p.put(prefix + "input-limit.proof", "test-whole-request-utf8-v1");
    var unproven = profiles(p);
    assertEquals(
        "MODEL_INPUT_BOUND_UNAVAILABLE",
        assertThrows(
                TaskFailure.class, () -> unproven.checkInput(unproven.selected("tenant"), "{}"))
            .code());
  }
}
