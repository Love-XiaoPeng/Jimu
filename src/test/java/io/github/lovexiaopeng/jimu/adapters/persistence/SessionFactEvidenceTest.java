package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionFactEvidenceTest {
  @Test
  void responseFixtureCarriesKnownUsageLikeTheNormalWorkerResult() {
    var usage = SessionFactScenario.usage();
    assertEquals(usage.totalTokens(), usage.inputTokens() + usage.outputTokens());
    assertEquals(17L, usage.totalTokens());
  }

  @Test
  void evidenceMarksDatesWithoutReencodingOriginalFrozenStrings() {
    String raw = "{\"z\":null,\"a\":1}";
    var value = new LinkedHashMap<String, Object>();
    value.put("raw", raw);
    value.put("nullable", null);
    value.put("rows", List.of(Map.of("time", LocalDateTime.of(2026, 10, 25, 8, 40, 0, 123456000))));
    var encoded =
        Payloads.read(
            Payloads.json(SessionFactScenario.evidenceValue(value)),
            com.fasterxml.jackson.databind.JsonNode.class);
    assertEquals(raw, encoded.path("raw").asText());
    assertTrue(encoded.has("nullable") && encoded.path("nullable").isNull());
    assertEquals(
        "2026-10-25T08:40:00.123456",
        encoded.path("rows").get(0).path("time").path("isoValue").asText());
    assertEquals(
        "java.time.LocalDateTime",
        encoded.path("rows").get(0).path("time").path("javaType").asText());
  }
}
