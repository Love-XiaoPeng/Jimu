package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ModelProfilesTest {
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
}
