package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryProofServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryPolicyCodecTest {
  private final SessionContracts contracts = new SessionContracts(5);

  private SessionQueryContext query(long prior) {
    var query =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, prior));
    query.restrictLineage();
    return query;
  }

  @Test
  void oldPoliciesRemainExactAndNewWireUsesTwoDistinctDigests() throws Exception {
    for (int v : List.of(1, 2, 3, 4)) {
      var old = new SessionContracts(v);
      assertSame(old.binding(), old.historySnapshot(null));
      assertTrue(old.historySnapshotMatches(old.binding(), null));
    }
    assertEquals(
        new String(
            SessionContracts.resource("storage/history-policy-v5.json"),
            java.nio.charset.StandardCharsets.UTF_8),
        contracts.binding());
    var wire = contracts.historySnapshot(null);
    assertEquals(304, wire.length());
    assertNotEquals(Payloads.hash(wire), Payloads.hash(contracts.binding()));
    assertEquals(
        Payloads.hash(contracts.binding()),
        contracts.json(wire, 4096).path("policy_sha256").asText());
    for (String raw :
        List.of(contracts.binding(), wire, new String(wire), new String(contracts.binding()))) {
      assertTrue(contracts.historySnapshotMatches(raw, null));
      var q = query(0);
      assertTrue(contracts.historySnapshotMatches(raw, q));
      assertEquals(65536, q.scratchLive());
    }
    assertFalse(
        contracts.historySnapshotMatches(
            contracts.binding().replace("local-session", "other-session"), query(0)));
  }

  @Test
  void rejectsRelabelsMissingPinsCatalogChangesAndNonCanonicalOrMalformedRaw() {
    String wire = contracts.historySnapshot(null);
    var cases = new ArrayList<String>();
    for (String key :
        List.of("version", "codec_id", "schema_sha256", "policy_sha256", "catalog_digest")) {
      var value = (com.fasterxml.jackson.databind.node.ObjectNode) contracts.json(wire, 4096);
      value.remove(key);
      cases.add(value.toString());
      value.put(key, "bad");
      cases.add(value.toString());
    }
    cases.add(wire.replace("\"version\":2", "\"version\":5"));
    cases.add(wire.replace("\"version\":2", "\"version\":2,\"version\":2"));
    cases.add(wire.replace("{", "{\"extra\":0,"));
    cases.add(wire + " ");
    cases.add(wire.replace("\"version\":2", "\"version\": 2"));
    cases.add(wire.replace("refs.v2", "refs.v1"));
    cases.add(wire + "{}");
    cases.add(contracts.binding().replace("\"version\":5", "\"version\":2"));
    for (String raw : cases) {
      var q = query(0);
      assertFalse(contracts.historySnapshotMatches(raw, q), raw);
      assertFalse(contracts.historySnapshotMatches(raw, null), raw);
      assertEquals(65536, q.scratchLive());
    }
    for (String raw : List.of("\uD800", "\uDC00")) {
      var q = query(0);
      assertThrows(TaskFailure.class, () -> contracts.historySnapshotMatches(raw, q));
      assertThrows(TaskFailure.class, () -> contracts.historySnapshotMatches(raw, null));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void everyReadIsIndependentlyEncodedHashedAndValidatedWithinExactBoundary() {
    String wire = contracts.historySnapshot(null);
    var q = query(0);
    assertTrue(contracts.historySnapshotMatches(wire, q));
    long cost = q.resources().operations();
    assertEquals(1008, cost);
    assertTrue(contracts.historySnapshotMatches(wire, q));
    assertTrue(contracts.historySnapshotMatches(new String(wire), q));
    assertEquals(3 * cost, q.resources().operations());
    assertEquals(3L * wire.length(), q.completedWork().get("hash"));
    assertEquals(3L * wire.length(), q.completedWork().get("encode"));
    assertEquals(3L * 64, q.completedWork().get("history.rawDigest"));
    assertEquals(3L * wire.length(), q.completedWork().get("history.wire"));
    assertFalse(q.completedWork().containsKey("history.schema"));
    for (int delta : List.of(-1, 0, 1)) {
      var limited = query(65536 - cost + delta);
      if (delta <= 0) assertTrue(contracts.historySnapshotMatches(wire, limited));
      else {
        var first =
            assertThrows(TaskFailure.class, () -> contracts.historySnapshotMatches(wire, limited));
        assertSame(first, limited.recordedFailure());
        assertSame(
            first,
            assertThrows(TaskFailure.class, () -> contracts.historySnapshotMatches(wire, limited)));
        assertFalse(limited.completedWork().containsKey("history.wire"));
      }
      assertEquals(65536, limited.scratchLive());
    }
    var full = query(0);
    Object owner = new Object();
    full.retainScratch(owner, 980000);
    assertThrows(TaskFailure.class, () -> contracts.historySnapshotMatches(wire, full));
    full.releaseScratch(owner);
    assertEquals(65536, full.scratchLive());
    var legacy = query(0);
    assertTrue(contracts.historySnapshotMatches(new String(contracts.binding()), legacy));
    assertEquals(32L + contracts.binding().length(), legacy.resources().operations());
    assertEquals(
        (long) contracts.binding().length(), legacy.completedWork().get("history.compare"));
  }

  private final class Proof implements SessionHistoryProofs.History {
    SessionHistoryProofs.HistoryRow row;

    public SessionHistoryProofs.HistoryRow load() {
      return row;
    }

    public long version() {
      return 5;
    }

    public String binding() {
      return contracts.binding();
    }

    public String hash(String raw) {
      return Payloads.hash(raw);
    }

    public boolean matchesSnapshot(String actual) {
      return contracts.historySnapshotMatches(actual, query(0));
    }
  }

  @Test
  void serviceKeepsLogicalDigestVersionMissingAndExpiryChecksForMixedSources() {
    var service = new SessionHistoryProofServiceImpl();
    var proof = new Proof();
    for (String raw : List.of(contracts.binding(), contracts.historySnapshot(null))) {
      proof.row =
          new SessionHistoryProofs.HistoryRow(
              1L, 5L, raw, Payloads.hash(contracts.binding()), "PRESENT", 1L);
      service.history(proof, true);
      proof.row =
          new SessionHistoryProofs.HistoryRow(1L, 5L, raw, Payloads.hash(raw) + "x", "PRESENT", 1L);
      assertThrows(TaskFailure.class, () -> service.history(proof, true));
      proof.row =
          new SessionHistoryProofs.HistoryRow(
              1L, 5L, raw, Payloads.hash(contracts.binding()), "PURGED", 0L);
      assertEquals(
          410, assertThrows(TaskFailure.class, () -> service.history(proof, true)).status());
      proof.row =
          new SessionHistoryProofs.HistoryRow(
              1L, 4L, raw, Payloads.hash(contracts.binding()), "PRESENT", 1L);
      assertThrows(TaskFailure.class, () -> service.history(proof, true));
    }
    proof.row = null;
    assertEquals(409, assertThrows(TaskFailure.class, () -> service.history(proof, true)).status());
    String wire = contracts.historySnapshot(null);
    proof.row =
        new SessionHistoryProofs.HistoryRow(1L, 5L, wire, Payloads.hash(wire), "PRESENT", 1L);
    assertThrows(TaskFailure.class, () -> service.history(proof, true));
  }

  @Test
  void missingOrTamperedPackagedDescriptorSchemaPolicyAndWireFailStartup() throws Exception {
    for (String path :
        List.of(
            "history-policy-codec-v1.json",
            "history-policy-refs-v2.schema.json",
            "history-policy-v5.json",
            "history-policy-refs-v2.json"))
      for (boolean missing : List.of(true, false))
        try (var loader =
            new java.net.URLClassLoader(
                new java.net.URL[] {
                  SessionContracts.class.getProtectionDomain().getCodeSource().getLocation()
                },
                SessionContracts.class.getClassLoader()) {
              @Override
              public java.io.InputStream getResourceAsStream(String name) {
                if (name.equals("session/storage/" + path))
                  return missing ? null : new java.io.ByteArrayInputStream(new byte[] {123, 125});
                return super.getResourceAsStream(name);
              }

              @Override
              protected Class<?> loadClass(String name, boolean resolve)
                  throws ClassNotFoundException {
                if (!name.startsWith("io.github.lovexiaopeng.jimu.adapters.runtime.session."))
                  return super.loadClass(name, resolve);
                Class<?> value = findLoadedClass(name);
                if (value == null) value = findClass(name);
                if (resolve) resolveClass(value);
                return value;
              }
            }) {
          var type = loader.loadClass(SessionContracts.class.getName());
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () -> type.getConstructor(int.class).newInstance(5));
        }
  }
}
