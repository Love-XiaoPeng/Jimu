package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionMatcher;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionScopeQueryBudget;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriorSessionContractsTest {
  private final PriorSessionContracts contracts = new PriorSessionContracts();
  private final SessionContracts history = new SessionContracts(4);

  @Test
  void asynchronousOwnerPhasesShareWholeQueryBudgets() {
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    QueryResources saved;
    try (var budget =
        new SessionScopeQueryBudget(
            new QueryResources(4095, 100, 2096128), checks::incrementAndGet)) {
      SessionScopeQueryBudget.charge(List.of(Map.of("id", "one")));
      SessionScopeQueryBudget.operation(1020);
      saved = budget.resources();
    }
    try (var budget = new SessionScopeQueryBudget(saved, checks::incrementAndGet)) {
      assertEquals(
          "SESSION_HISTORY_LIMIT",
          assertThrows(TaskFailure.class, () -> SessionScopeQueryBudget.charge(List.of())).code());
    }
    try (var budget =
        new SessionScopeQueryBudget(new QueryResources(0, 16777216, 0), checks::incrementAndGet)) {
      assertEquals(
          "SESSION_HISTORY_LIMIT",
          assertThrows(
                  TaskFailure.class,
                  () -> SessionScopeQueryBudget.charge(List.of(Map.of("id", "one"))))
              .code());
    }
    try (var budget =
        new SessionScopeQueryBudget(new QueryResources(0, 0, 2097152), checks::incrementAndGet)) {
      assertEquals(
          "SESSION_HISTORY_LIMIT",
          assertThrows(TaskFailure.class, () -> SessionScopeQueryBudget.operation(1)).code());
    }
    assertTrue(checks.get() >= 3);
  }

  @Test
  void newResourcesAndExamplesAreBytePinned() throws Exception {
    var root = Path.of("docs/verification/XIAO-55/architecture/contracts");
    try (var files = Files.walk(root)) {
      for (var file : files.filter(Files::isRegularFile).toList())
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            Files.readAllBytes(file),
            Files.readAllBytes(
                Path.of("src/main/resources/session/v4").resolve(root.relativize(file))));
    }
    history.event(Files.readString(root.resolve("examples/user-event.json")));
    history.event(Files.readString(root.resolve("examples/read-receipt.json")));
    history.output(Files.readString(root.resolve("examples/read-output.json")));
    history.context(Files.readString(root.resolve("examples/context.json")));
    history.owner(Files.readString(root.resolve("examples/owner-history.json")));
    contracts.output(Files.readString(root.resolve("examples/search-output.json")));
    contracts.authority(Files.readString(root.resolve("examples/scope.json")));
    assertEquals("0.4.0", history.descriptor().version());
  }

  @Test
  void strictInputsAndUnsupportedFactsAreDistinct() {
    for (String raw :
        List.of(
            "{}",
            "null",
            "[]",
            "{\"query\":\" \"}",
            "{\"query\":\"x\",\"query\":\"y\"}",
            "{\"query\":\"x\",\"event_seq_from\":-0}",
            "{\"query\":\"x\",\"event_seq_from\":1.0}",
            "{\"query\":\"x\",\"event_seq_from\":1e0}",
            "{\"query\":\"x\",\"event_seq_to\":9007199254740992}",
            "{\"query\":\"x\",\"event_types\":[]}",
            "{\"query\":\"x\",\"event_types\":[\"user.message\",\"user.message\"]}",
            "{\"query\":\"x\",\"created_at_from\":\"2026-02-30T00:00:00Z\"}",
            "{\"query\":\"x\",\"event_time_from\":\"2026-01-02T00:00:00Z\",\"event_time_to\":\"2026-01-01T00:00:00Z\"}",
            "{\"query\":\"\\uD800\"}",
            "{\"query\":\"x\"}{}"))
      assertEquals(
          "TOOL_INPUT_INVALID",
          assertThrows(TaskFailure.class, () -> contracts.input(raw)).code(),
          raw);
    for (String field :
        List.of(
            "\"parent_session_ids\":[\"00000000-0000-4000-8000-000000000001\"]",
            "\"include_root_sessions\":false",
            "\"include_root_sessions\":true",
            "\"availability\":[\"live\"]",
            "\"event_surfaces\":[\"current\"]"))
      assertEquals(
          "SESSION_SEARCH_FILTER_UNSUPPORTED",
          assertThrows(TaskFailure.class, () -> contracts.input("{\"query\":\"x\"," + field + "}"))
              .code());
    assertThrows(TaskFailure.class, () -> contracts.input(new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(TaskFailure.class, () -> history.input("{\"seq\":-0}"));
    assertEquals(
        9007199254740991L,
        contracts.input("{\"query\":\"x\",\"event_seq_from\":9007199254740991}").seqFrom());
    assertEquals("a b", contracts.input("{\"query\":\"  a\\uFEFFb  \"}").query());
  }

  @Test
  void queryAndWireBounds() {
    contracts.input(Payloads.json(java.util.Map.of("query", "😀".repeat(256))));
    assertThrows(
        TaskFailure.class,
        () -> contracts.input(Payloads.json(java.util.Map.of("query", "😀".repeat(257)))));
    String raw = "{\"query\":\"x\"}";
    contracts.input(raw + " ".repeat(4096 - raw.length()));
    assertThrows(TaskFailure.class, () -> contracts.input(raw + " ".repeat(4097 - raw.length())));
  }

  @Test
  void strongestRepresentativeStableGlobalTiesAndRealCap() throws Exception {
    var matcher = new PriorSessionMatcher(contracts.input("{\"query\":\"x\"}"), () -> {});
    var template =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            history.json(
                Files.readString(Path.of("src/main/resources/session/v4/examples/user-event.json")),
                16384);
    for (int i = 5; i >= 1; i--) {
      String id = "00000000-0000-4000-8000-00000000000" + i;
      var source =
          history.json(
              "{\"session_id\":\""
                  + id
                  + "\",\"history_version\":4,\"upper_seq\":3,\"created_at\":\"2026-10-23T00:00:00Z\"}",
              4096);
      var event = template.deepCopy();
      event.put("session_id", id);
      event.put("time", "2026-10-23T01:00:00Z");
      event.put("seq", 0);
      ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("data")).put("content", "x x");
      matcher.accept(event, Payloads.hash(Payloads.json(event)), source);
      event.put("seq", 2);
      matcher.accept(event, Payloads.hash(Payloads.json(event)), source);
      event.put("seq", 3);
      ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("data")).put("content", "x");
      matcher.accept(event, Payloads.hash(Payloads.json(event)), source);
    }
    String raw = matcher.output("00000000-0000-4000-8000-000000000055", "a".repeat(64));
    contracts.output(raw);
    var output = history.json(raw, 16384);
    assertEquals(4, output.path("returned_count").asInt());
    assertTrue(output.path("capped").asBoolean());
    for (int i = 0; i < 4; i++) {
      assertEquals(
          "00000000-0000-4000-8000-00000000000" + (i + 1),
          output.path("items").get(i).path("session_id").asText());
      assertEquals(2, output.path("items").get(i).path("best_match").path("seq").asInt());
    }
  }
}
