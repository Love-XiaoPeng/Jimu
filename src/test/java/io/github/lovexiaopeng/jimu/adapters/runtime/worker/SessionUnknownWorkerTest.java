package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionOutcome;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Real advisor/HTTP/agent/worker propagation; storage is a fault-injecting port, not a DB claim.
 */
@Timeout(25)
class SessionUnknownWorkerTest {
  @TempDir Path root;

  @Test
  void rawOwnerUnknownAtEveryModelRootDrainsWithoutOrdinaryStop() throws Exception {
    for (String phase :
        List.of(
            "toolSnapshot",
            "modelSnapshot",
            "checkpoint",
            "prepare",
            "dispatchModel",
            "modelResponded",
            "finish")) {
      var receipt =
          new SessionTransactionOutcome(
              SessionTransactionOutcome.Completion.UNKNOWN,
              SessionTransactionOutcome.Phase.BEFORE_COMMIT,
              11,
              12,
              13,
              System.nanoTime() + 2_000_000_000L);
      var cause = new java.sql.SQLException("synthetic ACK loss", "08007", 0);
      var failure = new SessionTransactionUnconfirmed(cause, receipt);
      run(phase, failure, true);
      assertSame(cause, failure.getCause());
      assertSame(receipt, failure.outcome());
    }
  }

  @Test
  void definiteFailureStillUsesOrdinaryStop() throws Exception {
    run("modelResponded", new TaskFailure(409, "SNAPSHOT_CORRUPT"), false);
  }

  private void run(String phase, RuntimeException failure, boolean unknown) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                      + "\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}],"
                      + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (var out = exchange.getResponseBody()) {
            out.write(response);
          }
        });
    server.start();
    try {
      var env = new StandardEnvironment();
      env.getPropertySources().remove("systemEnvironment");
      env.getPropertySources().remove("systemProperties");
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "jimu.test-mode",
                      "true",
                      "jimu.provider-origin",
                      origin,
                      "jimu.fixture.credentials",
                      "tenant:synthetic-token")));
      var models = new ModelProfiles(env);
      var descriptor = new SessionLineageContracts().descriptor();
      var lease =
          new ExecutionLedger.Lease(
              "tenant",
              "actor",
              "task",
              "attempt",
              1,
              "worker",
              Instant.now().plusSeconds(60),
              "test",
              List.of("session_trace"),
              1);
      var stops = new AtomicInteger();
      var renews = new AtomicInteger();
      var quiescent = new AtomicInteger();
      var injected = new AtomicInteger();
      var operations = new OperationThreads();
      var ledger =
          (ExecutionLedger)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {ExecutionLedger.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals(phase)) {
                      injected.incrementAndGet();
                      throw failure;
                    }
                    return switch (method.getName()) {
                      case "claim" -> Optional.of(lease);
                      case "toolSnapshot" ->
                          new ExecutionLedger.ToolSnapshot(
                              1,
                              Map.of(
                                  "session_trace",
                                  new ExecutionLedger.PluginPin(
                                      descriptor.version(), descriptor.outputSha256(), 1)));
                      case "modelSnapshot" -> models.selected("tenant");
                      case "checkpoint" -> Optional.empty();
                      case "prepare" ->
                          new ExecutionLedger.Step("step", 1, (String) args[1], null, null);
                      case "dispatchModel" -> "dispatch";
                      case "modelResponded", "finish" -> null;
                      case "stop" -> {
                        stops.incrementAndGet();
                        yield null;
                      }
                      case "renew" -> {
                        renews.incrementAndGet();
                        yield true;
                      }
                      case "quiescent" -> {
                        assertEquals(0, operations.activeCount());
                        quiescent.incrementAndGet();
                        yield null;
                      }
                      default ->
                          throw new AssertionError(
                              method.getName() + ":" + java.util.Arrays.toString(args));
                    };
                  });
      Path barrier = Files.createDirectory(root.resolve(phase + "-" + unknown));
      Files.writeString(barrier.resolve("release"), "release");
      var fetch =
          new FetchRuntime(new BuiltinManifestLoader().load(), URI.create(origin), Map.of());
      try (var agent =
              new DurableAgent(
                  ledger, null, fetch, new BoundaryProbe("SESSION_UNCONFIRMED", barrier), models);
          var loop = new WorkerLoop(ledger, agent, "host", () -> true, operations)) {
        loop.tick();
        assertEquals(1, injected.get(), phase);
        assertEquals(unknown ? 0 : 1, stops.get(), phase);
        assertEquals(1, quiescent.get(), phase);
        assertEquals(0, renews.get(), phase);
        if (unknown)
          assertTrue(
              Files.readString(barrier.resolve("reached")).startsWith("SESSION_UNCONFIRMED"));
        else assertFalse(Files.exists(barrier.resolve("reached")));
        if (phase.equals("modelResponded") || phase.equals("finish"))
          assertEquals(1, requests.get());
      }
    } finally {
      server.stop(0);
    }
  }
}
