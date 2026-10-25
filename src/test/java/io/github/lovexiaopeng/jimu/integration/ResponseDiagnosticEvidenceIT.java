package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 核验已执行的窄诊断原件；不重跑产品，不把已知 scope 失败转换为产品验证通过。 */
class ResponseDiagnosticEvidenceIT {
  private static JsonNode json(Path file) throws Exception {
    return Payloads.read(Files.readString(file), JsonNode.class);
  }

  @Test
  void retainsSeparateActualProductionRollbackAndSourceEvidence() throws Exception {
    Path root = Path.of("target/xiao60/response-diagnostic");
    Path trace = root.resolve("run1/trace");
    Path scope = root.resolve("run2/scope-three");
    for (Path group : new Path[] {trace, scope}) {
      var cleanup = json(group.resolve("cleanup.json"));
      assertTrue(cleanup.path("allChildrenExited").asBoolean());
      assertTrue(cleanup.path("containerRemoved").asBoolean());
      assertTrue(cleanup.path("failures").isEmpty());
    }
    var traces = json(trace.resolve("diagnostic-summary.json"));
    for (String tag : new String[] {"old", "new"}) {
      var value = traces.path(tag);
      assertEquals(0, value.path("exit").asInt());
      assertEquals("NORMAL_RETURN", value.path("outcomes").get(0).path("production").asText());
      assertEquals(
          "ROLLBACK_RETURNED", value.path("outcomes").get(0).path("outerTransaction").asText());
      String log = Files.readString(trace.resolve("response-" + tag + ".log"));
      assertTrue(log.contains("RESULT success"));
      String row = log.lines().filter(s -> s.startsWith("ROLLBACK ")).findFirst().orElseThrow();
      var rollback = Payloads.read(row.substring(9), JsonNode.class);
      assertEquals(rollback.path("before"), rollback.path("after"));
    }
    var scopes = json(scope.resolve("diagnostic-summary.json"));
    assertEquals(1, scopes.path("old").path("exit").asInt());
    var old = scopes.path("old").path("outcomes").get(0);
    assertEquals("EXCEPTION", old.path("production").asText());
    assertEquals("EXCEPTION", old.path("outerTransaction").asText());
    assertTrue(old.path("primary").toString().contains("08S01"));
    assertTrue(old.path("primary").toString().contains("SocketTimeoutException"));
    assertTrue(old.path("transactionException").toString().contains("08003"));
    assertEquals(0, scopes.path("new").path("exit").asInt());
    assertEquals(
        "NORMAL_RETURN", scopes.path("new").path("outcomes").get(0).path("production").asText());
    String raw = Files.readString(scope.resolve("worker-original-response.json"));
    assertEquals(raw, Files.readString(scope.resolve("pending-response.json")));
    var proof = json(scope.resolve("response-source-proof.json"));
    assertEquals(Payloads.hash(raw), proof.path("workerOrSampleSHA256").asText());
    assertEquals(
        raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, proof.path("bytes").asInt());
    assertFalse(proof.path("rawEqual").asBoolean());
    assertTrue(proof.path("jsonValueEqual").asBoolean());
    // The original failure remains an explicit diagnostic artifact, not a passed product group.
    assertTrue(
        Files.readString(scope.resolve("failure.txt")).contains("diagnostic failures preserved"));
  }
}
