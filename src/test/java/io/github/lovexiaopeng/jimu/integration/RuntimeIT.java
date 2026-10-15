package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.junit.jupiter.api.io.TempDir;

class RuntimeIT {
  @TempDir Path directory;

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
    return new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(directory.resolve(name + ".log").toFile())
        .start();
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

  private static HttpResponse<String> readiness(HttpClient client, URI uri) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
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
  void preserveProcessEvidence() throws Exception {
    Path target = Path.of("target/failsafe-reports/runtime-evidence");
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
