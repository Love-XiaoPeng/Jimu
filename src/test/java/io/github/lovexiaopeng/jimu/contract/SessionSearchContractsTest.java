package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchMatcher;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class SessionSearchContractsTest {
  private final SessionContracts v2 = new SessionContracts(2);
  private final SessionSearchContracts search = new SessionSearchContracts();
  private static final String TASK = "00000000-0000-4000-8000-000000000053";

  @TestFactory
  Stream<DynamicTest> strictInput() {
    return Stream.of(
            "{}",
            "null",
            "[]",
            "{\"query\":null}",
            "{\"query\":true}",
            "{\"query\":\"\"}",
            "{\"query\":\"  \\t\"}",
            "{\"query\":\"\\u0000\"}",
            "{\"query\":\"\\ud800\"}",
            "{\"query\":\"a\",\"query\":\"b\"}",
            "{\"query\":\"a\",\"unknown\":1}",
            "{\"query\":\"a\"} {}",
            "{\"query\":\"a\",\"seq_from\":1.0}",
            "{\"query\":\"a\",\"seq_from\":1e0}",
            "{\"query\":\"a\",\"seq_from\":\"1\"}",
            "{\"query\":\"a\",\"seq_from\":null}",
            "{\"query\":\"a\",\"seq_from\":-1}",
            "{\"query\":\"a\",\"seq_to\":9007199254740992}",
            "{\"query\":\"a\",\"seq_from\":2,\"seq_to\":1}",
            "{\"query\":\"a\",\"event_types\":[]}",
            "{\"query\":\"a\",\"event_types\":[\"model/response\"]}",
            "{\"query\":\"a\",\"event_types\":[\"user.message\",\"user.message\"]}",
            "{\"query\":\"a\",\"surfaces\":[]}",
            "{\"query\":\"a\",\"surfaces\":[\"fake\"]}",
            "{\"query\":\"a\",\"surfaces\":[\"current\",\"current\"]}",
            "{\"query\":\"a\",\"session_id\":\"00000000-0000-4000-8000-00000000005A\"}",
            Payloads.json(Map.of("query", "🧱".repeat(257))),
            " ".repeat(2048) + "{\"query\":\"a\"}")
        .map(
            raw ->
                DynamicTest.dynamicTest(
                    raw.length() > 110 ? "length boundary" : raw,
                    () ->
                        assertEquals(
                            "TOOL_INPUT_INVALID",
                            assertThrows(TaskFailure.class, () -> search.input(raw)).code())));
  }

  @TestFactory
  Stream<DynamicTest> strictTimes() {
    return Stream.of(
            "2026-02-29T00:00:00Z",
            "0000-01-01T00:00:00Z",
            "2026-01-01T24:00:00Z",
            "2026-01-01T00:00:60Z",
            "2026-01-01T00:00Z",
            "2026-01-01T00:00:00",
            "2026-01-01T00:00:00.1234567Z",
            "2026-01-01T00:00:00+18:01",
            "2026-01-01T00:00:00+23:59",
            "0001-01-01T00:00:00+01:00",
            "9999-12-31T23:59:59-01:00",
            "2026-01-01T00:00:00Z ")
        .map(
            time ->
                DynamicTest.dynamicTest(
                    time,
                    () ->
                        assertThrows(
                            TaskFailure.class,
                            () ->
                                search.input(
                                    Payloads.json(Map.of("query", "a", "time_from", time))))));
  }

  @Test
  void exactNormalizationUnicodeAndLimits() {
    String whitespace =
        "\t\n\u000b\f\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff";
    for (int c : whitespace.codePoints().toArray())
      assertEquals(
          "a b",
          SessionSearchContracts.normalize(" " + "a" + new String(Character.toChars(c)) + "b "));
    assertEquals("a\u0085\u180eb", SessionSearchContracts.normalize("a\u0085\u180eb"));
    assertEquals(
        "🧱".repeat(256), search.input(Payloads.json(Map.of("query", "🧱".repeat(256)))).query());
    assertThrows(TaskFailure.class, () -> search.input(new byte[] {(byte) 0xc0, (byte) 0xaf}));
    String raw = "{\"query\":\"a\"}";
    search.input(raw + " ".repeat(2048 - raw.length()));
    assertThrows(TaskFailure.class, () -> search.input(raw + " ".repeat(2049 - raw.length())));
    assertEquals(
        "SESSION_SEARCH_FILTER_UNSUPPORTED",
        assertThrows(
                TaskFailure.class,
                () -> search.input("{\"query\":\"a\",\"surfaces\":[\"current\"]}"))
            .code());
    var normalized =
        search.normalized(
            search.input(
                "{\"query\":\" a \",\"time_from\":\"2024-02-29T01:00:00.000001+01:00\",\"event_types\":[\"model.response\",\"user.message\"]}"),
            TASK,
            7);
    assertTrue(normalized.startsWith("{\"query\":\"a\",\"session_id\":\"" + TASK));
    assertTrue(normalized.contains("2024-02-29T00:00:00.000001Z"));
    assertTrue(normalized.endsWith("[\"user.message\",\"model.response\"]}"));
    assertThrows(
        TaskFailure.class,
        () ->
            search.input(
                "{\"query\":\"a\",\"time_from\":\"2026-01-01T00:00:00.000002Z\",\"time_to\":\"2026-01-01T00:00:00.000001Z\"}"));
  }

  private JsonNode event(int seq, String text, String time) {
    return v2.json(
        Payloads.json(
            Map.of(
                "seq",
                seq,
                "type",
                "user.message",
                "time",
                time,
                "source",
                Map.of("kind", "request"),
                "redacted",
                false,
                "data",
                Map.of("content", text))),
        65536);
  }

  private JsonNode output(SessionSearchMatcher matcher) {
    return v2.json(matcher.output(TASK, 127, "pre-step"), 32768);
  }

  @Test
  void exactFourKeyRankingNonoverlapAndCapping() {
    var matcher = new SessionSearchMatcher(search.input("{\"query\":\"aa\"}"), () -> {});
    matcher.accept(event(0, "aaaaa", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    matcher.accept(event(1, "aaaa", "2025-01-01T00:00:00Z"), "a".repeat(64), 127);
    matcher.accept(event(2, "aaaa", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    matcher.accept(event(3, "aaaa", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    assertEquals(
        List.of(3, 2, 1, 0),
        output(matcher).path("items").findValues("seq").stream().map(JsonNode::intValue).toList());
    var cap = new SessionSearchMatcher(search.input("{\"query\":\"x\"}"), () -> {});
    for (int seq = 0; seq < 16; seq++)
      cap.accept(event(seq, "x", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    assertFalse(output(cap).path("capped").booleanValue());
    cap.accept(event(16, "x", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    assertTrue(output(cap).path("capped").booleanValue());
    assertEquals(16, output(cap).path("returned_count").intValue());
  }

  @Test
  void literalCaseSensitiveFilterEndpointsAndSnippetCodepoints() {
    var matcher =
        new SessionSearchMatcher(
            search.input(
                "{\"query\":\".* OR\",\"seq_from\":2,\"seq_to\":3,\"time_from\":\"2026-01-01T01:00:00.000001+01:00\",\"time_to\":\"2026-01-01T00:00:00.000001Z\"}"),
            () -> {});
    matcher.accept(event(1, ".* OR", "2026-01-01T00:00:00.000001Z"), "a".repeat(64), 127);
    matcher.accept(event(2, ".* or", "2026-01-01T00:00:00.000001Z"), "a".repeat(64), 127);
    matcher.accept(
        event(3, "🧱".repeat(100) + ".* OR" + "尾".repeat(400), "2026-01-01T00:00:00.000001Z"),
        "a".repeat(64),
        127);
    var hit = output(matcher).path("items").get(0);
    assertEquals(1, output(matcher).path("returned_count").intValue());
    assertEquals("🧱".repeat(80) + ".* OR" + "尾".repeat(155), hit.path("snippet").asText());
    assertTrue(hit.path("left_truncated").asBoolean());
    assertTrue(hit.path("right_truncated").asBoolean());
    var structural =
        new SessionSearchMatcher(
            search.input("{\"query\":\"x\",\"event_types\":[\"step.started\"]}"), () -> {});
    structural.accept(event(0, "x", "2026-01-01T00:00:00Z"), "a".repeat(64), 127);
    assertEquals(0, output(structural).path("returned_count").asInt());
  }

  @Test
  void matchingHonorsDeadlineAndComparisonBudget() {
    var matcher = new SessionSearchMatcher(search.input("{\"query\":\"aaaaab\"}"), () -> {});
    var large = event(0, "a".repeat(16000), "2026-01-01T00:00:00Z");
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(
                TaskFailure.class,
                () -> {
                  for (int i = 0; i < 200; i++) matcher.accept(large, "a".repeat(64), 127);
                })
            .code());
    assertThrows(
        TaskFailure.class,
        () ->
            new SessionSearchMatcher(
                    search.input("{\"query\":\"a\"}"),
                    () -> {
                      throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
                    })
                .accept(large, "a".repeat(64), 127));
  }

  @Test
  void versionedExamplesAndPinsArePackagedWithoutReinterpretingV1() throws Exception {
    for (String name : List.of("search-output", "empty-output")) {
      try (var stream =
          getClass()
              .getClassLoader()
              .getResourceAsStream("session/v2/examples/" + name + ".json")) {
        search.output(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("session/v2/examples/read-output.json")) {
      String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      v2.output(raw);
      assertThrows(TaskFailure.class, () -> new SessionContracts().output(raw));
    }
    assertEquals("0.2.0", v2.descriptor().version());
    assertEquals("0.1.0", new SessionContracts().descriptor().version());
  }
}
