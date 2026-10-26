package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.api.TaskErrors;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionDeadlineTest {
  @Test
  void acceptedTcpWithoutGreetingClosesAtDeadline() throws Exception {
    stalled(false, 0);
  }

  @Test
  void delayedGreetingAndStalledAuthenticationShareDeadline() throws Exception {
    stalled(true, 900);
  }

  @Test
  void contextConnectionUsesSameDeadlineAndSafeFailure() throws Exception {
    stalled(false, 0, true);
  }

  private void stalled(boolean authenticate, long greetingDelay) throws Exception {
    stalled(authenticate, greetingDelay, false);
  }

  private void stalled(boolean authenticate, long greetingDelay, boolean context) throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      var peer =
          new FutureTask<>(
              () -> {
                try (var socket = server.accept()) {
                  socket.setSoTimeout(5000);
                  if (authenticate) {
                    Thread.sleep(greetingDelay);
                    byte[] greeting = greeting();
                    socket.getOutputStream().write(new byte[] {(byte) greeting.length, 0, 0, 0});
                    socket.getOutputStream().write(greeting);
                    socket.getOutputStream().flush();
                    var input = socket.getInputStream();
                    byte[] header = input.readNBytes(4);
                    assertEquals(4, header.length, "client sends its authentication packet");
                    int length =
                        (header[0] & 255) | ((header[1] & 255) << 8) | ((header[2] & 255) << 16);
                    assertEquals(length, input.readNBytes(length).length);
                  }
                  assertEquals(
                      -1,
                      socket.getInputStream().read(),
                      "client closes physical socket without peer timeout");
                  return true;
                }
              });
      Thread thread = Thread.ofPlatform().name("session-deadline-test-peer").start(peer);
      try {
        var source =
            new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + server.getLocalPort() + "/jimu?sslMode=DISABLED",
                "probe",
                "synthetic");
        var environment = new StandardEnvironment();
        environment
            .getPropertySources()
            .addFirst(
                new org.springframework.core.env.MapPropertySource(
                    "test", java.util.Map.of("jimu.test-mode", true)));
        var ledger = new JdbcLedger(source, new ModelProfiles(environment));
        long started = System.nanoTime();
        TaskFailure failure =
            assertThrows(
                TaskFailure.class,
                () -> {
                  if (context) ledger.sessionContext(null);
                  else
                    ledger.sessionHistory(
                        new Identity("tenant-a", "actor-a"),
                        "00000000-0000-4000-8000-000000000000");
                });
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(504, failure.status());
        assertEquals("SESSION_QUERY_TIMEOUT", failure.code());
        assertNotNull(failure.getCause(), "Internal diagnostic cause is retained");
        var response = new TaskErrors().failure(failure);
        assertEquals(504, response.getStatusCode().value());
        var json =
            new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response.getBody());
        assertEquals(3, json.size(), "Public response has only safe code/message/traceId");
        assertEquals("SESSION_QUERY_TIMEOUT", json.get("code").asText());
        assertEquals("SESSION_QUERY_TIMEOUT", json.get("message").asText());
        assertNotNull(java.util.UUID.fromString(json.get("traceId").asText()));
        assertTrue(elapsed >= 1800 && elapsed < 2600, "whole connection budget elapsed=" + elapsed);
        assertTrue(peer.get(1, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        server.close();
        thread.join(6000);
        assertFalse(thread.isAlive(), "foreground peer collected");
      }
    }
  }

  private byte[] greeting() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(10);
    bytes.write("8.0.46\0".getBytes(StandardCharsets.US_ASCII));
    bytes.write(new byte[] {1, 0, 0, 0});
    bytes.write("12345678\0".getBytes(StandardCharsets.US_ASCII));
    int flags = 0x00088209;
    bytes.write(
        new byte[] {
          (byte) flags,
          (byte) (flags >> 8),
          45,
          2,
          0,
          (byte) (flags >> 16),
          (byte) (flags >> 24),
          21
        });
    bytes.write(new byte[10]);
    bytes.write("123456789012\0mysql_native_password\0".getBytes(StandardCharsets.US_ASCII));
    return bytes.toByteArray();
  }
}
