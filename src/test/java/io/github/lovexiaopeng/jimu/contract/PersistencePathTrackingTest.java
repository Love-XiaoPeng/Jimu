package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Fails if the auditable source graph is stale; planned SQL IDs never count as loaded Mappers. */
class PersistencePathTrackingTest {
  @Test
  void everyCapturedProductionSourceAndAllTableStatesAreCurrent() throws Exception {
    JsonNode capture;
    try (var compressed =
        new GZIPInputStream(
            Files.newInputStream(
                Path.of("docs/verification/XIAO-60/evidence/path-tracking.json.gz")))) {
      capture =
          Payloads.read(
              new String(compressed.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
    }
    assertEquals(
        "620d5e0e5bfe7b5da530f10997936466159ba34f", capture.path("baselineSource").asText());
    for (var fields = capture.path("sourceHashes").fields(); fields.hasNext(); ) {
      var field = fields.next();
      assertEquals(
          field.getValue().asText(),
          Payloads.hash(Files.readAllBytes(Path.of(field.getKey()))),
          field.getKey());
    }
    assertEquals(36, capture.path("tables").size());
    for (var table : capture.path("tables")) {
      assertEquals("OPEN", table.path("state").asText());
      assertTrue(table.path("actualBusinessMapper").isNull());
    }
    assertTrue(capture.path("baseline").path("entries").size() >= 119);
    assertTrue(capture.path("baseline").path("sql").size() >= 623);
    assertTrue(capture.path("current").path("dynamicSql").size() > 0);
    for (var entry : capture.path("baseline").path("entries")) {
      assertTrue(entry.hasNonNull("targetService"));
      assertTrue(entry.has("transactionNodes"));
      assertTrue(entry.has("locks"));
      assertTrue(entry.has("verification"));
    }
  }
}
