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
      assertTrue(table.path("actualBusinessMapper").isArray());
    }
    assertEquals(153, capture.path("baseline").path("entries").size());
    // A SQL verb must be followed by whitespace; created_at/SETTLED were old false positives.
    assertTrue(capture.path("baseline").path("sql").size() > 500);
    assertTrue(capture.path("current").path("dynamicSql").size() > 0);
    assertTrue(capture.path("mapperSql").size() >= 48);
    for (var mapper : capture.path("mapperSql")) {
      assertTrue(!mapper.path("sql").asText().contains("${"));
      // These exact installation locks retain their original SELECT * projections.
      if (java.util.Set.of(
              "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEligibilityMapper.lockInstallation",
              "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryEligibilityMapper.lockInstallation",
              "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoEligibilityMapper.lockInstallation")
          .contains(mapper.path("id").asText()))
        assertEquals(
            "SELECT * FROM installation WHERE tenant_id=#{tenant} AND plugin_id=#{plugin} FOR SHARE",
            mapper.path("sql").asText());
      else assertTrue(!mapper.path("sql").asText().matches("(?is).*SELECT\\s+(?:\\w+\\.)?\\*.*"));
    }
    for (var entry : capture.path("baseline").path("entries")) {
      assertTrue(entry.hasNonNull("targetService"));
      assertTrue(entry.has("transactionNodes"));
      assertTrue(entry.has("locks"));
      assertTrue(entry.has("verification"));
    }
  }

  @Test
  void extractedCoreSqlPreservesAcceptedStatementsExactly() throws Exception {
    JsonNode capture;
    try (var compressed =
        new GZIPInputStream(
            Files.newInputStream(
                Path.of("docs/verification/XIAO-60/evidence/path-tracking.json.gz")))) {
      capture =
          Payloads.read(
              new String(compressed.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
    }
    var accepted = new java.util.HashSet<String>();
    for (var statement : capture.path("baseline").path("sql"))
      accepted.add(statement.path("sql").asText());
    int checked = 0;
    for (var statement : capture.path("current").path("sql")) {
      if (!statement
          .path("file")
          .asText()
          .matches(".*Jdbc(ExecutionRecords|ModelWorkflows|InvocationWorkflows)\\.java")) continue;
      assertTrue(accepted.contains(statement.path("sql").asText()), statement.toString());
      checked++;
    }
    var modelCutover =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-model-removed-sql.json")),
            JsonNode.class);
    for (var statement : modelCutover.path("removed")) {
      assertTrue(accepted.contains(statement.path("originalSql").asText()), statement.toString());
      checked++;
    }
    var invocationCutover =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-invocation-removed-sql.json")),
            JsonNode.class);
    for (var statement : invocationCutover.path("removed")) {
      assertTrue(accepted.contains(statement.path("originalSql").asText()), statement.toString());
      checked++;
    }
    assertTrue(checked > 40, "Real extracted SQL inventory is required");
  }
}
