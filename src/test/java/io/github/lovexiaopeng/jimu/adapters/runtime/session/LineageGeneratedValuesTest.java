package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Graph;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Node;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineageGeneratedValuesTest {
  private final SessionLineageContracts contracts = new SessionLineageContracts();
  private static final String A = "00000000-0000-0000-0000-000000000001";
  private static final String B = "00000000-0000-0000-0000-000000000002";
  private static final String HASH = "a".repeat(64);
  private static final String TIME = "2026-10-28T10:00:00.000000Z";

  private SessionQueryContext query(long prior) {
    var q =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, prior));
    q.restrictLineage();
    return q;
  }

  private Genesis root() {
    return new Genesis(1, A, "ROOT", TIME, HASH, HASH, HASH, null, null);
  }

  @Test
  void generatedGenesisMatchesLegacyBytesAndPinnedSchemaAcrossTypedConstraints() {
    var cases = new ArrayList<Genesis>();
    cases.add(root());
    cases.add(new Genesis(1, B, "CONTINUATION", TIME, HASH, HASH, HASH, A, HASH));
    // The legacy mapping omits both parent fields when the parent ID is absent.
    cases.add(new Genesis(1, A, "ROOT", TIME, HASH, HASH, HASH, null, "ignored"));
    cases.add(new Genesis(2, A, "ROOT", TIME, HASH, HASH, HASH, null, null));
    cases.add(new Genesis(1, A, "ROOT", "x".repeat(1025), HASH, HASH, HASH, null, null));
    cases.add(new Genesis(1, A, "ROOT", "界".repeat(400), HASH, HASH, HASH, null, null));
    for (String id : new String[] {null, "", A + "\n", "A" + A.substring(1), "\ud800"})
      cases.add(new Genesis(1, id, "ROOT", TIME, HASH, HASH, HASH, null, null));
    for (String hash : new String[] {null, "", "A".repeat(64), HASH + "\n", "0".repeat(63)}) {
      cases.add(new Genesis(1, A, "ROOT", TIME, hash, HASH, HASH, null, null));
      cases.add(new Genesis(1, A, "ROOT", TIME, HASH, hash, HASH, null, null));
      cases.add(new Genesis(1, A, "ROOT", TIME, HASH, HASH, hash, null, null));
      cases.add(new Genesis(1, B, "CONTINUATION", TIME, HASH, HASH, HASH, A, hash));
    }
    for (String time :
        new String[] {
          null,
          "",
          TIME + "\n",
          "2026-02-30T10:00:00.000000Z",
          "2024-02-29T10:00:00.000000Z",
          "2016-12-31T23:59:60.000000Z",
          "2026-10-28T10:00:00.000000+00:00",
          "2026-10-28T10:00:00Z"
        }) cases.add(new Genesis(1, A, "ROOT", time, HASH, HASH, HASH, null, null));
    for (String kind : new String[] {null, "", "root", "CONTINUATION"})
      cases.add(new Genesis(1, A, kind, TIME, HASH, HASH, HASH, null, null));
    cases.add(new Genesis(1, A, "ROOT", TIME, HASH, HASH, HASH, B, HASH));
    cases.add(new Genesis(1, B, "CONTINUATION", TIME, HASH, HASH, HASH, "invalid", HASH));
    for (var g : cases) {
      String old;
      try {
        old = contracts.genesis(g);
      } catch (TaskFailure failure) {
        var q = query(0);
        assertEquals(
            failure.code(),
            assertThrows(TaskFailure.class, () -> contracts.genesis(g, q)).code(),
            g.toString());
        assertEquals(65536, q.scratchLive());
        continue;
      }
      var q = query(0);
      var generated = contracts.genesis(g, q);
      assertEquals(old, generated.raw());
      assertEquals(Payloads.hash(old), generated.digest());
      assertEquals(old.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, generated.bytes());
      assertFalse(q.completedWork().containsKey("parse.genesis"));
      assertEquals(65536, q.scratchLive());
    }
  }

  private Graph graph(int count) {
    var nodes = new ArrayList<Node>();
    for (int i = 1; i <= count; i++) {
      String id = "00000000-0000-0000-0000-" + "%012d".formatted(i);
      String raw =
          contracts.genesis(new Genesis(1, id, "ROOT", TIME, HASH, HASH, HASH, null, null));
      nodes.add(new Node(id, raw, Payloads.hash(raw)));
    }
    return new Graph(1, A, HASH, nodes);
  }

  @Test
  void generatedGraphKeepsWriterBytesPinsSchemaAndOrderedSetConstraints() throws Exception {
    var codecs = new LineageGraphCodecs(new SessionContracts(5));
    var schema =
        new SchemaContract(
            SessionContracts.checked(
                "storage/lineage-graph-refs-v2.schema.json", codecs.schemaSha256));
    for (int count : List.of(1, 2, 9)) {
      var g = graph(count);
      var wire =
          Map.of(
              "version",
              2,
              "codec_id",
              LineageGraphCodecs.ID,
              "schema_sha256",
              codecs.schemaSha256,
              "caller_session_id",
              g.callerSessionId(),
              "authority_sha256",
              g.authoritySha256(),
              "nodes",
              g.nodes().stream()
                  .map(
                      n -> Map.of("session_id", n.sessionId(), "genesis_sha256", n.genesisSha256()))
                  .toList());
      String old = Payloads.json(wire);
      var q = query(0);
      var result = contracts.storageGraph(g, q, raw -> {});
      assertEquals(old, result.raw());
      schema.validateObject(wire);
      assertEquals(Payloads.hash(old), result.digest());
      assertFalse(q.completedWork().containsKey("parse.graph"));
      assertEquals(65536, q.scratchLive());
      contracts.matchGraph(new LineageRawView(old, q), g, q);
      assertTrue(q.completedWork().get("parse.graph") > 0);
    }
    var g = graph(2);
    for (var invalid :
        List.of(
            graph(0),
            graph(10),
            new Graph(2, A, HASH, g.nodes()),
            new Graph(1, "invalid", HASH, g.nodes()),
            new Graph(1, A, "invalid", g.nodes()),
            new Graph(1, A, HASH, List.of(g.nodes().getLast(), g.nodes().getFirst())),
            new Graph(1, A, HASH, List.of(g.nodes().getFirst(), g.nodes().getFirst())),
            new Graph(1, A, HASH, List.of(new Node(A, "raw", "invalid"))))) {
      var q = query(0);
      assertThrows(
          TaskFailure.class,
          () ->
              contracts.storageGraph(
                  invalid,
                  q,
                  raw -> {
                    throw new AssertionError("invalid typed input reached handoff");
                  }));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void everyExternalReadRetainsIndependentRawAndSchemaChecks() {
    var q = query(0);
    var generated = contracts.genesis(root(), q);
    for (int i = 1; i <= 2; i++) {
      assertEquals(root(), contracts.genesis(new LineageRawView(generated.raw(), q), q));
      assertEquals((long) generated.raw().length() * i, q.completedWork().get("parse.genesis"));
      assertEquals((long) generated.raw().length() * i, q.completedWork().get("schema.genesis"));
    }
    assertThrows(
        TaskFailure.class,
        () ->
            contracts.genesis(
                new LineageRawView(generated.raw().replace("ROOT", "INVALID"), q), q));
    assertEquals(65536, q.scratchLive());
    var fresh = query(0);
    assertThrows(
        TaskFailure.class,
        () ->
            contracts.genesis(
                new LineageRawView(
                    generated.raw().replace("\"version\":1", "\"version\":2"), fresh),
                fresh));
    assertEquals(65536, fresh.scratchLive());
  }

  @Test
  void genesisPrechargesActualBoundaryAndReleasesOwnersWithoutReplacingFirstFailure() {
    var measure = query(0);
    contracts.genesis(root(), measure);
    long cost = measure.resources().operations();
    for (int delta : List.of(-1, 0, 1)) {
      var limited = query(65536 - cost + delta);
      if (delta <= 0) {
        contracts.genesis(root(), limited);
        assertEquals(65536 + delta, limited.resources().operations());
      } else {
        var first = assertThrows(TaskFailure.class, () -> contracts.genesis(root(), limited));
        assertSame(first, limited.recordedFailure());
        assertSame(first, assertThrows(TaskFailure.class, limited::checkHealthy));
      }
      assertEquals(65536, limited.scratchLive());
    }
  }
}
