package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.ToolDefinitionMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.HttpResourceProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.ConfiguredOriginReader;
import io.github.lovexiaopeng.jimu.kernel.plugin.service.impl.PluginCatalogServiceImpl;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebFetchPluginContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private static ExecutionContext context() {
    return new ExecutionContext("p1-fixture", Instant.now().plusSeconds(10), () -> {});
  }

  @Test
  void realHttpFixtureProducesCanonicalOutputIncludingNonSuccessStatus() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/page",
        exchange -> {
          byte[] body = "controlled unavailable page".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(503, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    try (var reader = new ConfiguredOriginReader(origin)) {
      var descriptor = new BuiltinManifestLoader().load();
      var plugin = new WebFetchPlugin(reader, descriptor);
      String url = origin + "/page";
      new SchemaContract(BuiltinManifestLoader.resource("input.schema.json"))
          .validateObject(new FetchInput(url));
      FetchOutput output = plugin.execute(new FetchInput(url), context()).output();
      assertEquals(new FetchOutput(503, url, "controlled unavailable page", false), output);
      new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"))
          .validateObject(output);
      assertEquals(4, mapper.valueToTree(output).size());
      assertEquals(
          "web_fetch",
          new ToolDefinitionMapper()
              .map(
                  descriptor,
                  new String(
                      BuiltinManifestLoader.resource("input.schema.json"), StandardCharsets.UTF_8))
              .name());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void canonicalSchemaRejectsMissingExtraMistypedAndInvalidUriFields() throws Exception {
    var contract = new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"));
    var fixture =
        mapper.valueToTree(new FetchOutput(200, "https://example.com/page", "fixture", false));
    contract.validate(fixture);
    for (String json :
        List.of(
            "{\"httpStatus\":200,\"sourceUrl\":\"https://example.com\",\"truncated\":false}",
            "{\"httpStatus\":200,\"sourceUrl\":\"https://example.com\",\"content\":\"\",\"truncated\":false,\"text\":\"extra\"}",
            "{\"httpStatus\":\"200\",\"sourceUrl\":\"https://example.com\",\"content\":\"\",\"truncated\":false}",
            "{\"httpStatus\":200,\"sourceUrl\":\"not a uri\",\"content\":\"\",\"truncated\":false}")) {
      assertThrows(IllegalArgumentException.class, () -> contract.validate(mapper.readTree(json)));
    }
  }

  @Test
  void registryRejectsDuplicateIdentityAndDuplicateDiscoveryName() throws Exception {
    ToolDescriptor descriptor = new BuiltinManifestLoader().load();
    assertEquals(1, new PluginCatalogServiceImpl(List.of(descriptor)).descriptors().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCatalogServiceImpl(List.of(descriptor, descriptor)));
    var second =
        new ToolDescriptor(
            descriptor.pluginId(),
            "0.3.0",
            descriptor.capability(),
            descriptor.inputSchema(),
            descriptor.outputSchema(),
            descriptor.outputSha256(),
            descriptor.entryClass());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginCatalogServiceImpl(List.of(descriptor, second)));
  }

  @Test
  void catalogPreservesDiscoveryOrderAndIsolatedImmutableSnapshot() throws Exception {
    ToolDescriptor first = new BuiltinManifestLoader().load();
    var second =
        new ToolDescriptor(
            "jimu.test.second",
            first.version(),
            "second_tool",
            first.inputSchema(),
            first.outputSchema(),
            first.outputSha256(),
            first.entryClass());
    var input = new ArrayList<>(List.of(second, first));
    var catalog = new PluginCatalogServiceImpl(input);
    input.clear();
    assertEquals(List.of(second, first), catalog.descriptors());
    assertThrows(UnsupportedOperationException.class, () -> catalog.descriptors().clear());
  }

  @Test
  void manifestRejectsNonPackagedSchemasAndUnreviewedEntry() throws Exception {
    var loader = new BuiltinManifestLoader();
    String manifest =
        new String(BuiltinManifestLoader.resource("plugin.yaml"), StandardCharsets.UTF_8);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            loader.parse(
                manifest
                    .replace("input.schema.json", "../../outside.json")
                    .getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            loader.parse(
                manifest
                    .replace("WebFetchPlugin", "OtherPlugin")
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void schemaRejectsExternalReferencesAndUnknownDialect() {
    for (String schema :
        List.of(
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$ref\":\"https://example.com/schema\"}",
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$ref\":\"../outside.json\"}",
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SchemaContract(schema.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void cancellationIsCheckedBeforeAndAfterProviderExecution() throws Exception {
    var descriptor = new BuiltinManifestLoader().load();
    var requests = new AtomicInteger();
    var cancelled =
        new ExecutionContext(
            "cancelled",
            Instant.now().plusSeconds(10),
            () -> {
              throw new CancellationException();
            });
    var plugin =
        new WebFetchPlugin(
            (url, context) -> {
              requests.incrementAndGet();
              return new FetchOutput(200, url, "fixture", false);
            },
            descriptor);
    assertThrows(
        CancellationException.class,
        () -> plugin.execute(new FetchInput("https://example.com"), cancelled));
    assertEquals(0, requests.get());
    var checks = new AtomicInteger();
    var cancelAfter =
        new ExecutionContext(
            "cancel-after",
            Instant.now().plusSeconds(10),
            () -> {
              if (checks.incrementAndGet() == 2) throw new CancellationException();
            });
    assertThrows(
        CancellationException.class,
        () -> plugin.execute(new FetchInput("https://example.com"), cancelAfter));
    assertEquals(1, requests.get());
  }

  @Test
  void providerRejectsUnapprovedOriginAndExpiredDeadline() {
    try (var reader = new ConfiguredOriginReader(URI.create("http://127.0.0.1:1"))) {
      assertThrows(
          IllegalArgumentException.class, () -> reader.fetch("http://127.0.0.1:2/", context()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              reader.fetch(
                  "http://127.0.0.1:1/", new ExecutionContext("expired", Instant.EPOCH, () -> {})));
    }
  }

  @Test
  void providerBoundsResponsesAndDoesNotFollowRedirects() throws Exception {
    var redirected = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/large",
        exchange -> {
          byte[] bytes = "x".repeat(1048578).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          }
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/destination");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/destination",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    try (var reader = new ConfiguredOriginReader(origin)) {
      var output = reader.fetch(origin + "/large", context());
      assertTrue(output.truncated());
      assertEquals(1048576, output.content().getBytes(StandardCharsets.UTF_8).length);
      assertEquals(302, reader.fetch(origin + "/redirect", context()).httpStatus());
      assertEquals(0, redirected.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void stalledResponseBodyCannotHoldProviderOrReadinessOpen() throws Exception {
    var release = new CountDownLatch(1);
    var headersSent = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
              body.write('x');
              body.flush();
              headersSent.countDown();
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
      server.start();
      URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      try (var reader = new ConfiguredOriginReader(origin);
          var probe = new HttpResourceProbe(origin, true)) {
        var deadline = new ExecutionContext("slow-body", Instant.now().plusMillis(500), () -> {});
        var failure =
            assertThrows(IllegalStateException.class, () -> reader.fetch(origin + "/", deadline));
        assertTrue(failure.getCause() instanceof java.net.http.HttpTimeoutException);
        assertEquals(0L, headersSent.getCount());
        assertTrue(!probe.ready());
      } finally {
        release.countDown();
        server.stop(0);
      }
    }
  }

  @Test
  void validatorEnforces202012KeywordsAndLocalReferences() throws Exception {
    String prefixItems =
        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"}],\"items\":false}";
    var array = new SchemaContract(prefixItems.getBytes(StandardCharsets.UTF_8));
    array.validate(mapper.readTree("[\"first\"]"));
    assertThrows(IllegalArgumentException.class, () -> array.validate(mapper.readTree("[1]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> array.validate(mapper.readTree("[\"first\",\"second\"]")));
    String localReference =
        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$defs\":{\"text\":{\"type\":\"string\"}},\"$ref\":\"#/$defs/text\"}";
    var local = new SchemaContract(localReference.getBytes(StandardCharsets.UTF_8));
    local.validate(mapper.readTree("\"valid\""));
    assertThrows(IllegalArgumentException.class, () -> local.validate(mapper.readTree("42")));
  }
}
