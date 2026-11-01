package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionModelStepEncodingTest {
  @Test
  void currentStepContextExtractsOnlyAfterItsOwnSchemaValidation() throws Exception {
    var records = new JdbcSessionRecords(new org.springframework.jdbc.core.JdbcTemplate());
    var proof =
        new MapperSessionHistoryProofs(
            records,
            java.util.Map.of(
                "capabilities",
                io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES),
            java.util.Map.of());
    String raw;
    try (var source = getClass().getResourceAsStream("/session/v5/examples/context.json")) {
      raw = new String(source.readAllBytes(), StandardCharsets.UTF_8);
    }
    // The frozen illustrative example predates v5's two required lineage digests.
    // Complete this local shape fixture without changing that published example.
    raw =
        raw.replaceFirst(
            "\\{",
            "{\"genesis_sha256\":\""
                + "a".repeat(64)
                + "\",\"graph_sha256\":\""
                + "b".repeat(64)
                + "\",");
    var checked = proof.checkedContext(raw);
    assertEquals("00000000-0000-4000-8000-000000000055", checked.session());
    assertEquals(3, checked.upper());
    for (String invalid :
        List.of(
            raw.replace("\"version\": 5", "\"version\": 4"),
            raw.replace("\"upper_seq\": 3", "\"upper_seq\": -1"),
            raw.replaceFirst("\\{", "{\"unexpected\":true,"),
            "{\"session_id\":\"x\",\"upper_seq\":3}",
            raw + " {}")) {
      var failure = assertThrows(TaskFailure.class, () -> proof.checkedContext(invalid));
      assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
    }
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> proof.checkedContext(" ".repeat(4097))).code());
    // A prior successful call cannot authorize a changed raw or replace a fresh parse.
    assertEquals(checked, proof.checkedContext(new String(raw)));
  }

  @Test
  void nodeRawViewsEachUseTheirOwnResultEncodingLengthAndDigest() {
    var q = query();
    String raw = "a字😀\u0000";
    long expectedBytes = raw.getBytes(StandardCharsets.UTF_8).length;
    for (String label : List.of("request_snapshot", "genesis_snapshot", "graph_snapshot")) {
      try (var result = q.result()) {
        var view =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView(
                raw, result, label);
        assertSame(raw, view.raw());
        assertEquals(expectedBytes, view.bytes());
        assertEquals(Payloads.hash(raw), view.digest());
      }
    }
    assertEquals(3L * raw.length(), q.completedWork().get("history.encode"));
    assertEquals(3L * expectedBytes, q.completedWork().get("history.hash"));
    assertEquals(65536, q.scratchLive());
    for (String invalid : List.of("\ud800", "x\udc00")) {
      var bad = query();
      try (var result = bad.result()) {
        assertThrows(
            TaskFailure.class,
            () ->
                new io.github.lovexiaopeng.jimu.adapters.runtime.session.LineageRawView(
                    invalid, result, "graph_snapshot"));
      }
      assertEquals(65536, bad.scratchLive());
      assertTrue(!bad.completedWork().containsKey("history.hash"));
    }
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  @Test
  void oneStrictEncodingRetainsExactTransportBytesAndRealShaCost() {
    for (String raw : List.of("", "a".repeat(8163), "a中😀\u0000\u07ff\uffff")) {
      var q = query();
      var old = query();
      try (var result = q.result();
          var prior = old.result()) {
        assertEquals(Payloads.hash(raw), result.modelStepColumn("request_snapshot", raw));
        prior.column("request_snapshot", raw);
        assertEquals(old.resources().bytes(), q.resources().bytes());
        assertEquals((long) raw.length(), q.completedWork().get("history.encode"));
        assertEquals(
            (long) raw.getBytes(StandardCharsets.UTF_8).length,
            q.completedWork().get("history.hash"));
      }
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void eachFreshEqualRawStillEncodesAndHashesIndependently() {
    var q = query();
    for (int i = 0; i < 2; i++)
      try (var result = q.result()) {
        result.modelStepColumn("request_snapshot", new String("字😀"));
      }
    assertEquals(6L, q.completedWork().get("history.encode"));
    assertEquals(14L, q.completedWork().get("history.hash"));
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void malformedSurrogatesPoisonWithoutHashingAndReleaseTheEncodingOwner() {
    for (String raw : List.of("\ud800", "\udc00", "a\ud800x")) {
      var q = query();
      TaskFailure failure;
      try (var result = q.result()) {
        failure =
            assertThrows(TaskFailure.class, () -> result.modelStepColumn("request_snapshot", raw));
        assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
        assertTrue(!q.completedWork().containsKey("history.hash"));
      }
      assertSame(failure, assertThrows(TaskFailure.class, q::checkHealthy));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void additionalMeterSeesOriginalRawAndItsFirstFailureSurvivesCleanup() {
    var q = query();
    var marker = new IllegalStateException("extra meter");
    String raw = "字😀";
    try (var meter =
            q.meter(
                () ->
                    (name, value) -> {
                      assertEquals("request_snapshot", name);
                      assertSame(raw, value);
                      throw marker;
                    });
        var result = q.result()) {
      assertSame(
          marker,
          assertThrows(
              IllegalStateException.class, () -> result.modelStepColumn("request_snapshot", raw)));
    }
    assertSame(marker, q.recordedFailure());
    assertEquals(65536, q.scratchLive());
  }
}
