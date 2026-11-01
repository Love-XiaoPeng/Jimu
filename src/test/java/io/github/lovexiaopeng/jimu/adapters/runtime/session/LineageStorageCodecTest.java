package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineageStorageCodecTest {
  private final SessionLineageContracts contracts = new SessionLineageContracts();
  private final SessionContracts json = new SessionContracts(5);
  private static final String A = "00000000-0000-0000-0000-000000000001";
  private static final String B = "00000000-0000-0000-0000-000000000002";
  private static final String HASH = "a".repeat(64);

  private SessionQueryContext query(long prior) {
    var query =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, prior));
    query.restrictLineage();
    return query;
  }

  private SessionLineage.Graph graph() {
    var nodes = new ArrayList<SessionLineage.Node>();
    for (String id : List.of(A, B)) {
      String genesis =
          contracts.genesis(
              new SessionLineage.Genesis(
                  1, id, "ROOT", "2026-10-28T10:00:00.000000Z", HASH, HASH, HASH, null, null));
      nodes.add(new SessionLineage.Node(id, genesis, Payloads.hash(genesis)));
    }
    return new SessionLineage.Graph(1, B, HASH, nodes);
  }

  private String compact(SessionLineage.Graph expected) {
    return contracts.storageGraph(expected, query(0), raw -> {}).raw();
  }

  private void match(String raw, SessionLineage.Graph expected) {
    var query = query(0);
    try {
      contracts.matchGraph(new LineageRawView(raw, query), expected, query);
    } finally {
      assertEquals(65536, query.scratchLive());
    }
  }

  @Test
  void completeV1AndPinnedV2ShareFreshSourcesWhileLegacyExportStaysV1() throws Exception {
    var expected = graph();
    String old = contracts.graph(expected);
    String raw = compact(expected);
    match(old, expected);
    match(raw, expected);
    assertEquals(expected, contracts.graph(old));
    assertThrows(TaskFailure.class, () -> contracts.graph(raw));
    assertEquals(old, contracts.graph(expected));
    var value = json.json(raw, 16384);
    assertEquals(2, value.path("version").intValue());
    assertFalse(raw.contains("genesis_snapshot"));
    var codecs = new LineageGraphCodecs(json);
    assertEquals(codecs.schemaSha256, value.path("schema_sha256").textValue());
    new SchemaContract(
            SessionContracts.checked(
                "storage/lineage-graph-refs-v2.schema.json", codecs.schemaSha256))
        .validate(value);
    assertTrue(raw.length() < old.length());
    var query = query(0);
    var encoded =
        contracts.storageGraph(
            expected,
            query,
            result -> {
              assertTrue(query.scratchLive() >= 65536 + 32768);
              assertEquals(Payloads.hash(result.raw()), result.digest());
            });
    assertEquals(Payloads.hash(encoded.raw()), encoded.digest());
    assertTrue(query.completedWork().get("serialize") > 0);
    assertFalse(query.completedWork().containsKey("compare.graph"));
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void rejectPinsRelabelsDuplicateFieldsAndInexactOrderedSets() {
    var expected = graph();
    String raw = compact(expected);
    var cases = new ArrayList<String>();
    for (String key : List.of("codec_id", "schema_sha256", "version")) {
      var value = (ObjectNode) json.json(raw, 16384);
      value.remove(key);
      cases.add(value.toString());
    }
    cases.add(raw.replace("jimu.lineage.graph.refs.v2", "jimu.lineage.graph.refs.v3"));
    cases.add(raw.replace("\"version\":2", "\"version\":1"));
    cases.add(raw.replace("\"version\":2", "\"version\":3"));
    var value = (ObjectNode) json.json(raw, 16384);
    value.put("schema_sha256", "0".repeat(64));
    cases.add(value.toString());
    value = (ObjectNode) json.json(raw, 16384);
    value.put("authority_sha256", "0".repeat(64));
    cases.add(value.toString());
    cases.add(raw.replace(A, B));
    cases.add(raw.replace("\"version\":2", "\"version\":2,\"version\":2"));
    cases.add(raw.replace("\"nodes\":", "\"unknown\":0,\"nodes\":"));
    value = (ObjectNode) json.json(raw, 16384);
    var nodes = value.withArray("nodes");
    var first = nodes.get(0);
    nodes.set(0, nodes.get(1));
    nodes.set(1, first);
    cases.add(value.toString());
    nodes.remove(1);
    cases.add(value.toString());
    nodes.add(first);
    nodes.add(first);
    cases.add(value.toString());
    value = (ObjectNode) json.json(raw, 16384);
    ((ObjectNode) value.path("nodes").get(0))
        .put("genesis_snapshot", expected.nodes().getFirst().genesisSnapshot());
    cases.add(value.toString());
    cases.add(contracts.graph(expected).replace("\"version\":1", "\"version\":2"));
    for (String invalid : cases)
      assertEquals(
          "SESSION_HISTORY_CORRUPT",
          assertThrows(TaskFailure.class, () -> match(invalid, expected), invalid).code());
    var changed =
        new SessionLineage.Graph(
            1,
            B,
            HASH,
            List.of(
                new SessionLineage.Node(
                    A, expected.nodes().getFirst().genesisSnapshot(), "0".repeat(64)),
                expected.nodes().getLast()));
    assertThrows(TaskFailure.class, () -> match(raw, changed));
  }

  @Test
  void storageWriterPrechargesExactOperationBoundaryAndCleansRejectedHandoff() {
    var expected = graph();
    var measure = query(0);
    contracts.storageGraph(expected, measure, raw -> {});
    long cost = measure.resources().operations();
    assertTrue(cost > 0 && cost < 65536);
    for (int delta : List.of(-1, 0, 1)) {
      var limited = query(65536 - cost + delta);
      if (delta <= 0) {
        contracts.storageGraph(expected, limited, raw -> {});
        assertEquals(65536 + delta, limited.resources().operations());
      } else {
        var failure =
            assertThrows(
                TaskFailure.class, () -> contracts.storageGraph(expected, limited, raw -> {}));
        assertSame(failure, limited.recordedFailure());
        assertEquals("SESSION_HISTORY_LIMIT", failure.code());
      }
      assertEquals(65536, limited.scratchLive());
    }
  }

  @Test
  void packagedDescriptorAndSchemaBytesFailClosedAtStartup() throws Exception {
    for (String path :
        List.of(
            "session/storage/lineage-graph-codecs-v1.json",
            "session/storage/lineage-graph-refs-v2.schema.json")) {
      for (boolean missing : List.of(true, false)) {
        try (var loader =
            new java.net.URLClassLoader(
                new java.net.URL[] {
                  SessionLineageContracts.class.getProtectionDomain().getCodeSource().getLocation()
                },
                SessionLineageContracts.class.getClassLoader()) {
              @Override
              public java.io.InputStream getResourceAsStream(String name) {
                if (name.equals(path))
                  return missing
                      ? null
                      : new java.io.ByteArrayInputStream(
                          "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return super.getResourceAsStream(name);
              }

              @Override
              protected Class<?> loadClass(String name, boolean resolve)
                  throws ClassNotFoundException {
                if (!name.startsWith("io.github.lovexiaopeng.jimu.adapters.runtime.session."))
                  return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
              }
            }) {
          var type = loader.loadClass(SessionLineageContracts.class.getName());
          var failure =
              assertThrows(
                  java.lang.reflect.InvocationTargetException.class,
                  () -> type.getConstructor().newInstance());
          assertTrue(
              failure.getCause() instanceof IllegalArgumentException
                  || failure.getCause() instanceof IllegalStateException);
        }
      }
    }
  }

  @Test
  void newOwnersReleaseOnLimitAndErrorWithoutReplacingFirstFailure() {
    var expected = graph();
    var query = query(65535);
    var first =
        assertThrows(TaskFailure.class, () -> contracts.storageGraph(expected, query, raw -> {}));
    assertSame(first, query.recordedFailure());
    assertSame(first, assertThrows(TaskFailure.class, query::checkHealthy));
    assertEquals(65536, query.scratchLive());
    assertFalse(query.completedWork().containsKey("serialize"));
    var errorQuery = query(0);
    var error = new AssertionError("handoff failed");
    assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () ->
                contracts.storageGraph(
                    expected,
                    errorQuery,
                    raw -> {
                      throw error;
                    })));
    assertEquals(65536, errorQuery.scratchLive());
    var full = query(0);
    Object other = new Object();
    full.retainScratch(other, 970000);
    assertThrows(TaskFailure.class, () -> contracts.storageGraph(expected, full, raw -> {}));
    full.releaseScratch(other);
    assertEquals(65536, full.scratchLive());
  }
}
