package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class FetchRuntimeTest {
  private static StandardEnvironment environment(Map<String, Object> properties) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove("systemProperties");
    env.getPropertySources().remove("systemEnvironment");
    env.getPropertySources().addFirst(new MapPropertySource("test", properties));
    return env;
  }

  @Test
  void fixturePolicyRequiresExplicitTestAssembly() {
    assertFalse(FetchRuntime.fixtureMode(environment(Map.of())));
    assertTrue(FetchRuntime.fixtureMode(environment(Map.of("jimu.test-mode", "true"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchRuntime.fixtureMode(environment(Map.of("jimu.web.mode", "fixture"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> FetchRuntime.fixtureMode(environment(Map.of("jimu.web.mode", "other"))));
  }

  @Test
  void disabledPublicRuntimeCannotReachInjectedTransport() throws Exception {
    var calls = new AtomicInteger();
    var runtime =
        new FetchRuntime(
            new BuiltinManifestLoader().loadPublic(),
            Set.of("fixture.example"),
            (url, context) -> {
              calls.incrementAndGet();
              throw new AssertionError("No dispatch permitted");
            },
            false);
    var failure =
        assertThrows(
            TaskFailure.class,
            () ->
                runtime.execute(
                    new FetchInput("https://fixture.example/page"),
                    "tenant-a",
                    "host-effect",
                    new ExecutionContext("invocation", Instant.now().plusSeconds(30), () -> {})));
    assertEquals("PUBLIC_WEB_NOT_CONFIGURED", failure.code());
    assertEquals(0, calls.get());
  }

  @Test
  void runtimeVersionsAndPublicPolicyCannotBeMixed() throws Exception {
    var loader = new BuiltinManifestLoader();
    assertThrows(
        IllegalArgumentException.class,
        () -> new FetchRuntime(loader.loadPublic(), URI.create("http://127.0.0.1"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FetchRuntime(loader.load(), Set.of(), (url, context) -> null, false));
    var runtime =
        new FetchRuntime(
            loader.loadPublic(), Set.of("fixture.example"), (url, context) -> null, true);
    runtime.validate(new FetchInput("https://fixture.example/page"));
    for (String url :
        java.util.List.of(
            "http://fixture.example/page",
            "https://127.0.0.1/page",
            "https://fixture.example:8443/page",
            "https://user@fixture.example/page",
            "https://other.example/page"))
      assertThrows(IllegalArgumentException.class, () -> runtime.validate(new FetchInput(url)));
  }
}
