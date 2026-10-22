package io.github.lovexiaopeng.jimu.adapters.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpWireTest {
  private McpLedger.Profile profile(int port) {
    return new McpLedger.Profile(
        1,
        "tenant-a",
        "controlled",
        1,
        "host",
        "http://127.0.0.1:" + port + "/mcp",
        "unit",
        1,
        List.of("alice"),
        List.of(),
        List.of("jimu-fixture://tenant-a/docs/"),
        List.of(),
        "2025-06-18",
        new McpLedger.ServerInfo("unit", "1"),
        Map.of("resources", Map.of()),
        McpContracts.DIGEST);
  }

  private McpWire.Audit audit(AtomicInteger sent) {
    return new McpWire.Audit() {
      @Override
      public int dispatch(String method, String id, String digest, boolean cleanup) {
        return sent.incrementAndGet();
      }

      @Override
      public void responded(int ordinal, String error) {}

      @Override
      public void check() {}
    };
  }

  @Test
  void explicitNoProxyBypassesHostSelectorAndRedirectHasNoSecondConnection() throws Exception {
    var targetCount = new AtomicInteger();
    var destinationCount = new AtomicInteger();
    HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.createContext(
        "/mcp",
        exchange -> {
          targetCount.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .add("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/trap");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    destination.createContext(
        "/",
        exchange -> {
          destinationCount.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    target.start();
    destination.start();
    var previous = ProxySelector.getDefault();
    ProxySelector.setDefault(
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            return List.of(new Proxy(Proxy.Type.HTTP, destination.getAddress()));
          }

          @Override
          public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
    try (var wire =
        new McpWire(
            profile(target.getAddress().getPort()),
            "unit",
            Instant.now().plusSeconds(5),
            Duration.ofSeconds(5),
            audit(new AtomicInteger()))) {
      assertEquals("MCP_UPSTREAM_ERROR", assertThrows(TaskFailure.class, wire::initialize).code());
      assertEquals(1, targetCount.get());
      assertEquals(0, destinationCount.get());
    } finally {
      ProxySelector.setDefault(previous);
      target.stop(0);
      destination.stop(0);
    }
  }

  @Test
  void unsafeProfileIsRejectedBeforeAnyHttpSend() {
    var sent = new AtomicInteger();
    var p = profile(65535);
    for (String endpoint :
        List.of(
            "http://localhost:18764/mcp",
            "http://192.0.2.1:18764/mcp",
            "http://127.0.0.1:65536/mcp",
            "http://127.0.0.1:18764/other")) {
      var node =
          Payloads.read(Payloads.json(p), com.fasterxml.jackson.databind.node.ObjectNode.class);
      node.put("endpoint", endpoint);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new McpWire(
                  Payloads.read(node.toString(), McpLedger.Profile.class),
                  "unit",
                  Instant.now().plusSeconds(1),
                  Duration.ofSeconds(1),
                  audit(sent)));
    }
    assertEquals(0, sent.get());
  }
}
