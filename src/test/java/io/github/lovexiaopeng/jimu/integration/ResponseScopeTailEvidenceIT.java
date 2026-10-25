package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Retrospective evidence contract; synthetic SQL controls are not successful product responses. */
class ResponseScopeTailEvidenceIT {
  private static JsonNode json(Path path) throws Exception {
    return Payloads.read(Files.readString(path), JsonNode.class);
  }

  @Test
  void preservesNaturalFailureAndSeparatelyAttributesControlledTranslation() throws Exception {
    Path root = Path.of("target/xiao60/response-scope-tail");
    for (String run : new String[] {"run1", "run2"}) {
      Path archive = root.resolve(run + "/executed-driver");
      for (JsonNode row : json(archive.resolve("index.json"))) {
        byte[] bytes = Files.readAllBytes(archive.resolve(row.path("path").asText()));
        assertEquals(row.path("bytes").asLong(), bytes.length);
        assertEquals(
            row.path("sha256").asText(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
      var cleanup = json(root.resolve(run + "/scope-three/cleanup.json"));
      for (String key :
          new String[] {"containerRemoved", "rootRemoved", "allChildrenExited", "fixtureStopped"})
        assertTrue(cleanup.path(key).asBoolean());
      assertTrue(cleanup.path("failures").isEmpty());
    }
    var natural = json(root.resolve("run1/scope-three/diagnostic-summary.json"));
    var old = natural.path("old").path("outcomes").get(0);
    assertEquals("EXCEPTION", old.path("production").asText());
    assertEquals("EXCEPTION", old.path("outerTransaction").asText());
    assertTrue(old.path("primary").toString().contains("SESSION_QUERY_TIMEOUT"));
    assertEquals(
        "org.mybatis.spring.MyBatisSystemException", old.path("primary").path("class").asText());
    assertEquals(
        "NORMAL_RETURN", natural.path("new").path("outcomes").get(0).path("production").asText());
    var control = json(root.resolve("run2/scope-three/phase-analysis.json"));
    for (String tag : new String[] {"old", "new"}) {
      var row = control.path(tag);
      assertEquals("EXCEPTION", row.path("production").asText());
      assertEquals("ROLLBACK_RETURNED", row.path("outerTransaction").asText());
      assertEquals(1, row.path("syntheticAccountFaults").asInt());
      assertTrue(row.path("primary").toString().contains("PRIVATE_ACCOUNT_TRANSLATION_CONTROL"));
      assertTrue(row.path("primary").toString().contains("08S01"));
      var interval = row.path("failureInterval");
      assertTrue(interval.path("sqlFailureToDriverCatchMicros").asLong() > 0);
      boolean initialized = false;
      for (JsonNode duration : row.path("methodDurations")) {
        if (duration
            .path("method")
            .asText()
            .endsWith("MyBatisExceptionTranslator.initExceptionTranslator()V")) {
          assertTrue(duration.path("durationMicros").asLong() > 0);
          assertEquals("RETURN", duration.path("exit").asText());
          initialized = true;
        }
      }
      assertTrue(initialized);
      for (JsonNode stage : interval.path("stages"))
        assertFalse(stage.path("phase").asText().contains("ENTER datasource.getConnection"));
      String log = Files.readString(root.resolve("run2/scope-three/response-" + tag + ".log"));
      String raw = log.lines().filter(s -> s.startsWith("ROLLBACK ")).findFirst().orElseThrow();
      var facts = Payloads.read(raw.substring(9), JsonNode.class);
      assertEquals(facts.path("before"), facts.path("after"));
    }
    assertTrue(Files.exists(root.resolve("run1/scope-three/failure.txt")));
  }
}
