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

/**
 * Checks executed diagnostic evidence; never retries a product call or treats controls as success.
 */
class ResponseTranslatorColdEvidenceIT {
  private static JsonNode json(Path file) throws Exception {
    return Payloads.read(Files.readString(file), JsonNode.class);
  }

  @Test
  void retainsExecutedInputsAndSeparatesNaturalAndSyntheticOutcomes() throws Exception {
    Path root = Path.of("target/xiao60/response-translator-cold");
    var setup = json(root.resolve("run2/scope-three/cleanup.json"));
    assertFalse(setup.path("containerRemoved").asBoolean());
    assertTrue(setup.path("rootRemoved").asBoolean());
    assertTrue(
        Files.readString(root.resolve("run2/scope-three/failure.txt")).contains("TimeoutExpired"));
    for (String run : new String[] {"run1", "run3"}) {
      Path archive = root.resolve(run + "/executed-driver");
      for (JsonNode row : json(archive.resolve("index.json"))) {
        byte[] bytes = Files.readAllBytes(archive.resolve(row.path("path").asText()));
        assertEquals(row.path("bytes").asLong(), bytes.length);
        assertEquals(
            row.path("sha256").asText(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
      Path group = root.resolve(run + "/scope-three");
      for (String key :
          new String[] {"containerRemoved", "rootRemoved", "allChildrenExited", "fixtureStopped"})
        assertTrue(json(group.resolve("cleanup.json")).path(key).asBoolean());
      assertTrue(json(group.resolve("cleanup.json")).path("failures").isEmpty());
      assertTrue(
          java.util.Arrays.equals(
              Files.readAllBytes(group.resolve("worker-original-response.json")),
              Files.readAllBytes(group.resolve("pending-response.json"))));
      var summary = json(group.resolve("diagnostic-summary.json"));
      for (String tag : new String[] {"old", "new"}) {
        var outcome = summary.path(tag).path("outcomes").get(0);
        String production = outcome.path("production").asText();
        assertTrue(production.equals("NORMAL_RETURN") || production.equals("EXCEPTION"));
        String log = Files.readString(group.resolve("response-" + tag + ".log"));
        if (outcome.path("outerTransaction").asText().equals("ROLLBACK_RETURNED")) {
          var line = log.lines().filter(s -> s.startsWith("ROLLBACK ")).findFirst().orElseThrow();
          var facts = Payloads.read(line.substring(9), JsonNode.class);
          assertEquals(facts.path("before"), facts.path("after"));
        } else {
          assertFalse(log.lines().anyMatch(s -> s.startsWith("ROLLBACK ")));
        }
        if (run.equals("run3")) {
          assertEquals("EXCEPTION", production);
          assertEquals(1, outcome.path("syntheticAccountFaults").asInt());
          assertTrue(
              outcome.path("primary").toString().contains("PRIVATE_ACCOUNT_TRANSLATION_CONTROL"));
          String phases = Files.readString(group.resolve("phases-" + tag + "/phases.tsv"));
          assertTrue(
              phases.contains(
                  "ENTER org/springframework/jdbc/support/SQLErrorCodesFactory.<init>()V"));
          assertTrue(
              phases.contains(
                  "RETURN org/springframework/beans/factory/xml/DefaultDocumentLoader.loadDocument"));
          assertTrue(
              phases.contains(
                  "RETURN org/springframework/beans/factory/support/DefaultListableBeanFactory.getBeansOfType"));
        } else {
          assertEquals(0, outcome.path("syntheticAccountFaults").asInt());
        }
      }
    }
  }
}
