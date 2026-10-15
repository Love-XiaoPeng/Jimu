package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

class RuntimeIT {
  @TempDir Path directory;
  private final List<Process> children = new ArrayList<>();
  private int readinessRequests;

  private Process start(String name, String... properties) throws Exception {
    Path jar = Path.of(System.getProperty("jimu.jar"));
    assertTrue(Files.isRegularFile(jar), "Required packaged application is missing");
    String executable =
        System.getProperty(
            "jimu.it.java", Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var command =
        new ArrayList<>(
            List.of(executable, "-jar", jar.toAbsolutePath().toString(), "--server.port=0"));
    command.addAll(List.of(properties));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve(name + ".log").toFile())
            .start();
    children.add(process);
    return process;
  }

  private URI started(Process process, String name) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    Path log = directory.resolve(name + ".log");
    var pattern = Pattern.compile("Tomcat started on port (\\d+)");
    while (process.isAlive() && System.nanoTime() < deadline) {
      String text = Files.readString(log);
      var matcher = pattern.matcher(text);
      if (matcher.find()) return URI.create("http://127.0.0.1:" + matcher.group(1) + "/readyz");
      Thread.sleep(100);
    }
    throw new AssertionError("Required " + name + " failed to start: " + Files.readString(log));
  }

  private HttpResponse<String> readiness(HttpClient client, URI uri) throws Exception {
    Path evidence = directory.resolve("readiness-" + ++readinessRequests + ".txt");
    long started = System.nanoTime();
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      Files.writeString(
          evidence,
          "uri="
              + uri
              + " status="
              + response.statusCode()
              + " elapsedMillis="
              + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      return response;
    } catch (Exception failure) {
      try {
        StringBuilder state =
            new StringBuilder(
                "uri="
                    + uri
                    + " failure="
                    + failure
                    + " elapsedMillis="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + "\n");
        for (Process process : children) {
          state
              .append("pid=")
              .append(process.pid())
              .append(" alive=")
              .append(process.isAlive())
              .append(" info=")
              .append(process.info())
              .append('\n');
          Path status = Path.of("/proc", Long.toString(process.pid()), "status");
          if (Files.isRegularFile(status)) state.append(Files.readString(status));
        }
        Files.writeString(evidence, state);
        // Capture before finally destroys the live worker/control; jcmd failures stay visible.
        for (Process process : children) {
          if (!process.isAlive()) continue;
          String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
          Process dump =
              new ProcessBuilder(jcmd, Long.toString(process.pid()), "Thread.print")
                  .redirectErrorStream(true)
                  .redirectOutput(directory.resolve("threads-" + process.pid() + ".txt").toFile())
                  .start();
          try {
            dump.waitFor(5, TimeUnit.SECONDS);
          } finally {
            if (dump.isAlive()) {
              dump.destroyForcibly();
              dump.waitFor();
            }
          }
        }
      } catch (Exception diagnosticFailure) {
        failure.addSuppressed(diagnosticFailure);
      }
      throw failure;
    }
  }

  private void stop(Process process) throws Exception {
    if (process != null) {
      process.destroy();
      if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
      assertFalse(process.isAlive(), "Run-owned Java process must be collected");
      Files.writeString(
          directory.resolve("cleanup-" + process.pid() + ".txt"),
          "pid="
              + process.pid()
              + " exit="
              + process.exitValue()
              + " alive="
              + process.isAlive()
              + System.lineSeparator());
    }
  }

  @AfterEach
  void preserveProcessEvidence(TestInfo test) throws Exception {
    Path target =
        Path.of(
            "target/failsafe-reports/runtime-evidence",
            test.getTestMethod().orElseThrow().getName());
    Files.createDirectories(target);
    try (var files = Files.list(directory)) {
      for (Path file : files.toList())
        Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Test
  void independentWorkerAndControlRequireRealResourcesAndCleanUp() throws Exception {
    var available = new AtomicBoolean(true);
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext(
        "/",
        exchange -> {
          byte[] body =
              "P1 independent HTTP fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(available.get() ? 200 : 503, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    fixture.start();
    Process worker = null;
    Process control = null;
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      worker =
          start(
              "worker",
              "--jimu.role=worker",
              "--jimu.provider-origin=http://127.0.0.1:" + fixture.getAddress().getPort());
      URI workerReady = started(worker, "worker");
      var response = readiness(client, workerReady);
      assertEquals(200, response.statusCode(), response.body());
      var snapshot = new ObjectMapper().readTree(response.body());
      assertEquals("worker", snapshot.path("role").asText());
      assertEquals(1, snapshot.path("plugins").asInt());
      String workerOrigin = "http://127.0.0.1:" + workerReady.getPort();
      control = start("control", "--jimu.role=control", "--jimu.worker-origin=" + workerOrigin);
      URI controlReady = started(control, "control");
      assertEquals(200, readiness(client, controlReady).statusCode());
      assertEquals(
          "control",
          new ObjectMapper()
              .readTree(readiness(client, controlReady).body())
              .path("role")
              .asText());
      available.set(false);
      assertEquals(503, readiness(client, workerReady).statusCode());
      assertEquals(503, readiness(client, controlReady).statusCode());
      available.set(true);
      assertEquals(200, readiness(client, controlReady).statusCode());
      stop(worker);
      assertEquals(503, readiness(client, controlReady).statusCode());
    } finally {
      try {
        stop(control);
      } finally {
        try {
          stop(worker);
        } finally {
          fixture.stop(0);
        }
      }
    }
  }

  @Test
  void readinessTimeoutPreservesLiveProcessEvidence() throws Exception {
    // A deliberately unanswered local request checks diagnostics without relaxing the gate timeout.
    HttpServer stalled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stalled.createContext("/readyz", exchange -> {});
    stalled.start();
    Process worker = null;
    try (var client = HttpClient.newHttpClient()) {
      worker =
          start(
              "diagnostic-worker",
              "--jimu.role=worker",
              "--jimu.provider-origin=http://127.0.0.1:" + stalled.getAddress().getPort());
      started(worker, "diagnostic-worker");
      assertThrows(
          HttpTimeoutException.class,
          () ->
              readiness(
                  client,
                  URI.create("http://127.0.0.1:" + stalled.getAddress().getPort() + "/readyz")));
      String state = Files.readString(directory.resolve("readiness-1.txt"));
      assertTrue(state.contains("HttpTimeoutException"), state);
      assertTrue(state.contains("pid=" + worker.pid() + " alive=true"), state);
      assertTrue(
          Files.readString(directory.resolve("threads-" + worker.pid() + ".txt"))
              .contains("Full thread dump"));
    } finally {
      try {
        stop(worker);
      } finally {
        stalled.stop(0);
      }
    }
  }

  @Test
  void missingRequiredWorkerConfigurationFailsStartup() throws Exception {
    Process process = start("missing-resource", "--jimu.role=worker");
    try {
      assertTrue(
          process.waitFor(40, TimeUnit.SECONDS),
          "Missing required configuration must fail startup");
      assertTrue(process.exitValue() != 0);
      assertTrue(
          Files.readString(directory.resolve("missing-resource.log"))
              .contains("jimu.provider-origin"));
    } finally {
      stop(process);
    }
  }
}
