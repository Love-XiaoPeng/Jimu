package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionLineageServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionLineageServiceTest {
  private final SessionLineageContracts contracts = new SessionLineageContracts();
  private final SessionContracts json = new SessionContracts(5);
  private final Map<String, SessionLineageRecords.Fact> facts = new HashMap<>();
  private final Map<String, SessionLineage.Stored> rows = new HashMap<>();
  private final List<String> reads = new ArrayList<>();
  private final SessionLineageRecords records =
      new SessionLineageRecords() {
        public Fact fact(String tenant, String id) {
          reads.add("fact:" + id);
          return facts.get(id);
        }

        public SessionLineage.Stored load(String tenant, String id) {
          reads.add("row:" + id);
          return rows.get(id);
        }

        public void insert(String tenant, String id, SessionLineage.Stored row) {
          rows.put(id, row);
        }

        public void body(String tenant, String id, long bytes) {
          assertTrue(bytes > 0);
        }

        public String hash(String raw) {
          return Payloads.hash(raw);
        }

        public int bytes(String raw) {
          return SessionContracts.bytes(raw);
        }

        public String genesis(SessionLineage.Genesis value) {
          return contracts.genesis(value);
        }

        public SessionLineage.Genesis genesis(String raw) {
          return contracts.genesis(raw);
        }

        public String graph(SessionLineage.Graph value) {
          return contracts.graph(value);
        }

        public SessionLineage.Graph graph(String raw) {
          return contracts.graph(raw);
        }

        public String output(SessionLineage.Result value) {
          return contracts.output(value);
        }

        public void checkpoint(long count) {
          assertTrue(count > 0);
        }
      };
  private final SessionLineageServiceImpl service = new SessionLineageServiceImpl(records);

  private String add(int ordinal, List<String> sources, String parent) {
    String id = "00000000-0000-0000-0000-%012d".formatted(ordinal);
    String request = Payloads.json(Map.of("id", id, "sources", sources));
    facts.put(
        id,
        new SessionLineageRecords.Fact(
            "tenant",
            id,
            "2026-10-28T10:00:00.000000Z",
            request,
            Payloads.hash(request),
            "a".repeat(64),
            "b".repeat(64),
            sources,
            parent));
    service.create("tenant", id, parent);
    return id;
  }

  @Test
  void closedForestHasStableDfsAndNearestAncestorsWithoutConnectingReadDependencies() {
    String a = add(1, List.of(), null),
        b = add(2, List.of(a), a),
        c = add(3, List.of(a, b), b),
        d = add(4, List.of(a), a);
    String e = add(5, List.of(a, b, c, d), null);
    var value = json.json(service.trace("tenant", e, a), 8192);
    assertEquals(List.of(b, c, d), value.path("descendants").findValuesAsText("session_id"));
    var middle = json.json(service.trace("tenant", e, b), 8192);
    assertEquals(a, middle.path("ancestors").get(0).path("session_id").asText());
    assertEquals(1, middle.path("descendants").size());
    var own = json.json(service.trace("tenant", e, null), 8192);
    assertEquals("ROOT", own.path("target").path("kind").asText());
    assertEquals(0, own.path("ancestors").size());
    String original = service.trace("tenant", e, a);
    String f = add(6, List.of(a), a);
    reads.clear();
    assertEquals(original, service.trace("tenant", e, a));
    assertEquals(10, reads.size());
    assertTrue(reads.stream().noneMatch(s -> s.endsWith(f)));
    assertEquals(
        "SESSION_SCOPE_UNAVAILABLE",
        assertThrows(TaskFailure.class, () -> service.trace("tenant", e, f)).code());
  }

  @Test
  void changedOriginalAdmissionAndMissingDependencyFailClosed() {
    String a = add(1, List.of(), null), b = add(2, List.of(a), a);
    assertEquals(
        "SESSION_LINEAGE_SCOPE_INVALID",
        assertThrows(TaskFailure.class, () -> add(3, List.of(b), b)).code());
    var old = facts.get(a);
    facts.put(
        a,
        new SessionLineageRecords.Fact(
            old.tenant(),
            a,
            old.created(),
            "{}",
            old.requestDigest(),
            old.authorityDigest(),
            old.bindingDigest(),
            old.sources(),
            old.declaredParent()));
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.trace("tenant", b, a)).code());
  }

  @Test
  void maximumClosedDepthAndGraphHashAreIndependentlyVerified() {
    var sources = new ArrayList<String>();
    String parent = null;
    for (int index = 1; index <= 9; index++) {
      parent = add(index, List.copyOf(sources), parent);
      sources.add(parent);
    }
    var value = json.json(service.trace("tenant", parent, sources.getFirst()), 8192);
    assertEquals(8, value.path("descendants").size());
    assertEquals(8, value.path("descendants").get(7).path("depth").asInt());
    String caller = parent;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> add(10, List.copyOf(sources), caller)).code());
    var old = rows.get(caller);
    rows.put(
        caller,
        new SessionLineage.Stored(
            old.genesis(),
            old.genesisDigest(),
            old.genesisBytes(),
            old.graph(),
            "0".repeat(64),
            old.graphBytes(),
            old.state(),
            old.version()));
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.trace("tenant", caller, null)).code());
  }
}
