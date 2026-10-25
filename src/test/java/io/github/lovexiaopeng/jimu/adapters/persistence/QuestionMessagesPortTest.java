package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestionMessagesPortTest {
  @Test
  void constructorIsLazyAndCapabilityContractIsOriginal() {
    var poison =
        new AbstractMap<String, Object>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError();
          }

          public Object get(Object key) {
            throw new AssertionError("read " + key);
          }
        };
    var port = new MapperQuestionMessages(null, poison, null);
    assertThrows(AssertionError.class, port::tenant);
    assertEquals("a", new MapperQuestionMessages(null, Map.of("tenant_id", "a"), null).tenant());
  }

  @Test
  void castsAndNullsRemainLazyFieldReads() {
    var p = new MapperQuestionMessages(null, Map.of("tenant_id", 1, "id", 2), null);
    assertThrows(ClassCastException.class, p::tenant);
    assertThrows(ClassCastException.class, p::taskId);
    assertThrows(ClassCastException.class, () -> p.answer(Map.of("answer_snapshot", 1)));
    assertEquals(null, p.id(Map.of()));
    assertThrows(ClassCastException.class, () -> p.provider(Map.of("provider_id", 1)));
    assertThrows(ClassCastException.class, () -> p.answerDigest(Map.of("answer_digest", 1)));
  }

  @Test
  void loadedJoinRetainsOriginalFullProjectionAndTwoStringBindings() throws Exception {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var statement =
        session
            .getConfiguration()
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionMessagesMapper.pending");
    var bound = statement.getBoundSql(new java.util.HashMap<>(Map.of("tenant", "t", "task", "q")));
    try (var stream =
        new java.util.zip.GZIPInputStream(
            java.nio.file.Files.newInputStream(
                java.nio.file.Path.of(
                    "docs/verification/XIAO-60/evidence/path-tracking.json.gz")))) {
      var capture =
          io.github.lovexiaopeng.jimu.adapters.codec.Payloads.read(
              new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
              com.fasterxml.jackson.databind.JsonNode.class);
      String original = null;
      for (var row : capture.path("baseline").path("sql"))
        if (row.path("sql").asText().startsWith("SELECT q.*,i.provider_id FROM user_question"))
          original = row.path("sql").asText();
      assertNotNull(original);
      assertEquals(original, bound.getSql());
    }
    assertEquals(
        List.of("tenant", "task"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    assertTrue(session.getConfiguration().isCallSettersOnNulls());
    assertEquals(java.util.LinkedHashMap.class, statement.getResultMaps().getFirst().getType());
  }
}
