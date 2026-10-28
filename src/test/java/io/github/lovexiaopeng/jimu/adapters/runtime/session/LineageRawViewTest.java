package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineageRawViewTest {
  private SessionQueryContext query() {
    return new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
  }

  @Test
  void strictEncodingOnceAndIndependentNewMaterialization() {
    var query = query();
    query.restrictLineage();
    for (String text : List.of("", "ascii", "积木😀", "\\uD800")) {
      long before = query.resources().operations(), live = query.scratchLive();
      var raw = new LineageRawView(text, query);
      assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, raw.bytes());
      assertEquals(Payloads.hash(text), raw.digest());
      raw.bytes();
      raw.digest();
      assertEquals(2L * text.length(), query.resources().operations() - before);
      assertEquals(live, query.scratchLive());
      new LineageRawView(text, query);
      assertEquals(4L * text.length(), query.resources().operations() - before);
    }
    long live = query.scratchLive();
    assertThrows(TaskFailure.class, () -> new LineageRawView("\uD800", query));
    assertThrows(TaskFailure.class, () -> new LineageRawView("\uDC00", query));
    assertEquals(live, query.scratchLive());
  }

  @Test
  void capacityIsReservedBeforeEncodingAndRejectedWorkIsNotCompleted() {
    var query = query();
    query.restrictLineage();
    var owner = new Object();
    query.retainScratch(owner, 980000);
    assertThrows(TaskFailure.class, () -> new LineageRawView("x".repeat(2000), query));
    assertFalse(query.completedWork().containsKey("encode"));
    query.releaseScratch(owner);
    var limited =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 65535));
    limited.restrictLineage();
    assertThrows(TaskFailure.class, () -> new LineageRawView("ab", limited));
    assertEquals(2L, limited.requestedWork().get("encode"));
    assertFalse(limited.completedWork().containsKey("encode"));
    assertEquals(65536, limited.scratchLive());
  }

  @Test
  void frozenUtf8BoundaryAndEscapedLoneSurrogate() {
    var contracts = new SessionLineageContracts();
    var query = query();
    String id = "00000000-0000-0000-0000-000000000001";
    String genesis =
        contracts.genesis(
            new SessionLineage.Genesis(
                1,
                id,
                "ROOT",
                "2026-10-28T10:00:00.000000Z",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                null,
                null));
    for (int n : List.of(1023, 1024, 1025)) {
      var raw = new LineageRawView(genesis + " ".repeat(n - genesis.length()), query);
      if (n <= 1024) assertEquals(id, contracts.genesis(raw, query).sessionId());
      else
        assertEquals(
            "SESSION_HISTORY_LIMIT",
            assertThrows(TaskFailure.class, () -> contracts.genesis(raw, query)).code());
    }
    String invalid = genesis.replace("ROOT", "RO\\uD800OT");
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(
                TaskFailure.class,
                () -> contracts.genesis(new LineageRawView(invalid, query), query))
            .code());
  }

  @Test
  void graphBoundaryNumericEquivalenceAndStrictGrammarMatchFrozenCodec() {
    var contracts = new SessionLineageContracts();
    var query = query();
    String id = "00000000-0000-0000-0000-000000000001";
    String genesis =
        contracts.genesis(
            new SessionLineage.Genesis(
                1,
                id,
                "ROOT",
                "2026-10-28T10:00:00.000000Z",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                null,
                null));
    var graph =
        new SessionLineage.Graph(
            1,
            id,
            "b".repeat(64),
            List.of(new SessionLineage.Node(id, genesis, Payloads.hash(genesis))));
    String original = contracts.graph(graph);
    String decimal = original.replace("\"version\":1", "\"version\":1.0");
    assertEquals(graph, contracts.graph(decimal));
    contracts.matchGraph(new LineageRawView(decimal, query), graph, query);
    for (int size : List.of(16383, 16384, 16385)) {
      var view = new LineageRawView(original + " ".repeat(size - original.length()), query);
      if (size <= 16384) contracts.matchGraph(view, graph, query);
      else
        assertEquals(
            "SESSION_HISTORY_LIMIT",
            assertThrows(TaskFailure.class, () -> contracts.matchGraph(view, graph, query)).code());
    }
    for (String raw :
        List.of(
            original.replace("\"version\":1", "\"version\":01"),
            original.replace("\"version\":1", "\"version\":1e1"),
            original + "\u00a0",
            original.replace(
                "\"session_id\":", "\"session_id\":\"" + id + "\",\"sess\\u0069on_id\":"),
            original.replace("\"version\":1", "\"version\":[[[[1]]]]"))) {
      assertEquals(
          "SESSION_HISTORY_CORRUPT",
          assertThrows(
                  TaskFailure.class,
                  () -> contracts.matchGraph(new LineageRawView(raw, query), graph, query))
              .code());
    }
    var encoded = contracts.graph(graph, query);
    assertEquals(graph, contracts.graph(encoded.raw()));
    assertEquals(Payloads.hash(encoded.raw()), encoded.digest());
  }

  @Test
  void numericTokensUseFrozenReaderAndConstAsDifferentialOracle() {
    var contracts = new SessionLineageContracts();
    String id = "00000000-0000-0000-0000-000000000001";
    var graph =
        new SessionLineage.Graph(
            1, id, "b".repeat(64), List.of(new SessionLineage.Node(id, "{}", "a".repeat(64))));
    String original = contracts.graph(graph);
    var numbers =
        new java.util.ArrayList<>(
            List.of(
                "1",
                "1.0",
                "1e0",
                "1E+0",
                "1.0000000000000001",
                "0.99999999999999999",
                "-0",
                "-0.0",
                "-0e0",
                "0",
                "01",
                "+1",
                "1.",
                "1e",
                "1e1",
                "1e-1",
                "1e1000000000",
                "1e-1000000000",
                "1." + "0".repeat(1100)));
    for (int length : List.of(999, 1000, 1001)) {
      numbers.add("1." + "0".repeat(length - 2));
      numbers.add("1e" + "0".repeat(length - 2));
      numbers.add("1".repeat(length));
    }
    for (String number : numbers) {
      String raw = original.replace("\"version\":1", "\"version\":" + number);
      boolean expected;
      try {
        contracts.graph(raw);
        expected = true;
      } catch (TaskFailure rejected) {
        expected = false;
      }
      var query = query();
      boolean actual;
      try {
        contracts.matchGraph(new LineageRawView(raw, query), graph, query);
        actual = true;
      } catch (TaskFailure rejected) {
        assertEquals("SESSION_HISTORY_CORRUPT", rejected.code());
        actual = false;
      }
      assertEquals(expected, actual, "version token: " + number);
      assertEquals(1048576, query.scratchLive());
    }
  }
}
