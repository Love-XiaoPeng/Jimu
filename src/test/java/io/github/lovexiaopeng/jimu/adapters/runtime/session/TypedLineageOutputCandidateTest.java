package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedLineageOutputCandidateTest {
  private final SessionLineageContracts frozen = new SessionLineageContracts();
  private static final String ID = "abcdef00-0000-0000-0000-000000000001", HASH = "a".repeat(64);

  private SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private SessionLineage.PublicNode node(String kind, String parent, String date) {
    return new SessionLineage.PublicNode(ID, kind, date, HASH, parent);
  }

  private SessionLineage.Result result(SessionLineage.PublicNode target, int count, int depth) {
    return new SessionLineage.Result(
        ID,
        HASH,
        HASH,
        target,
        node("ROOT", null, "2026-10-29T00:00:00.000000Z"),
        java.util.Collections.nCopies(count, node("ROOT", null, "2026-10-29T00:00:00.000000Z")),
        java.util.Collections.nCopies(
            count,
            new SessionLineage.Descendant(
                node("CONTINUATION", ID, "2026-10-29T00:00:00.000000Z"), depth)));
  }

  private boolean accepts(Runnable run) {
    try {
      run.run();
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private void oracle(SessionLineage.Result r) {
    boolean expected = accepts(() -> frozen.output(r));
    boolean actual =
        accepts(
            () -> {
              try (var output = TypedLineageOutputCandidate.output(r, query())) {
                output.raw();
              }
            });
    assertEquals(expected, actual, r.toString());
    if (expected) {
      var baseline = frozen.output(r, query());
      try (var candidate = TypedLineageOutputCandidate.output(r, query())) {
        assertEquals(baseline.raw(), candidate.raw());
        assertEquals(baseline.bytes(), candidate.bytes());
        assertEquals(baseline.digest(), candidate.digest());
      }
    }
  }

  @Test
  void nativeDateFormatDifferentialRetainsVerifiedLeapSecond() {
    for (String year : List.of("0000", "0001", "1900", "2000", "2024", "2026", "9999"))
      for (String day : List.of("01-01", "02-28", "02-29", "02-30", "04-31", "12-31"))
        for (String time : List.of("00:00:00", "23:59:59", "23:59:60", "12:00:60", "24:00:00"))
          oracle(result(node("ROOT", null, year + "-" + day + "T" + time + ".000000Z"), 0, 1));
    oracle(result(node("ROOT", null, "2016-12-31T23:59:60.000000Z"), 0, 1));
    oracle(result(node("ROOT", null, "2016-12-30T23:59:60.000000Z"), 0, 1));
  }

  @Test
  void maximumShapeRetainsResultRawAndDigestUntilExplicitHandoff() {
    var n = node("CONTINUATION", ID, "2026-10-29T00:00:00.000000Z");
    var base = result(n, 8, 8);
    var r =
        new SessionLineage.Result(
            base.caller(),
            base.authority(),
            base.graph(),
            n,
            base.root(),
            java.util.Collections.nCopies(8, n),
            base.descendants());
    var q = query();
    long initial = q.scratchLive();
    var output = TypedLineageOutputCandidate.output(r, q);
    assertEquals(5195, output.bytes());
    org.junit.jupiter.api.Assertions.assertTrue(
        q.scratchLive() >= initial + output.raw().length() * 2L + 16384);
    Object receiver = new Object();
    q.retainScratch(receiver, 1024L + output.raw().length() * 2L);
    String adopted = output.raw();
    output.close();
    assertEquals(frozen.output(r), adopted);
    assertThrows(IllegalStateException.class, output::raw);
    assertThrows(IllegalStateException.class, output::digest);
    output.close();
    q.releaseScratch(receiver);
    assertEquals(initial, q.scratchLive());
    assertThrows(
        RuntimeException.class,
        () -> TypedLineageOutputCandidate.output(result(node("ROOT", null, "bad"), 0, 1), q));
    assertEquals(initial, q.scratchLive());
  }

  @Test
  void frozenSchemaDifferentialForTypedFieldsArraysDepthAndDates() {
    for (String kind : List.of("ROOT", "CONTINUATION", "root", "ROOT\"", "😀"))
      for (String parent : new String[] {null, ID, ID.toUpperCase(), "\\unicode"})
        oracle(result(node(kind, parent, "2026-10-29T00:00:00.000000Z"), 0, 1));
    for (int count : List.of(0, 1, 8, 9))
      for (int depth : List.of(0, 1, 8, 9))
        oracle(result(node("ROOT", null, "2026-10-29T00:00:00.000000Z"), count, depth));
    for (String date :
        List.of(
            "2026-02-28T23:59:59.999999Z",
            "2024-02-29T00:00:00.000000Z",
            "2026-02-29T00:00:00.000000Z",
            "2016-12-31T23:59:60.000000Z",
            "2026-10-29T24:00:00.000000Z",
            "2026-10-29T00:00:00.00000Z",
            "2026-10-29t00:00:00.000000z",
            "2026-10-29T00:00:00.000000+00:00")) oracle(result(node("ROOT", null, date), 0, 1));
    for (String id : List.of(ID, ID.toUpperCase(), ID + "x", "租户😀", "\\\"")) {
      var n = new SessionLineage.PublicNode(id, "ROOT", "2026-10-29T00:00:00.000000Z", HASH, null);
      oracle(result(n, 0, 1));
    }
    for (String hash : List.of(HASH, HASH.toUpperCase(), "a".repeat(63), "a".repeat(65)))
      oracle(
          result(
              new SessionLineage.PublicNode(ID, "ROOT", "2026-10-29T00:00:00.000000Z", hash, null),
              0,
              1));
  }

  @Test
  void externalRawStillUsesFrozenReaderIncludingSizeDuplicateAndExtraFieldRules() {
    String raw = frozen.output(result(node("ROOT", null, "2026-10-29T00:00:00.000000Z"), 0, 1));
    for (int size : List.of(8191, 8192, 8193)) {
      String padded = raw + " ".repeat(size - raw.length());
      assertEquals(size <= 8192, accepts(() -> frozen.output(padded)));
    }
    assertThrows(
        RuntimeException.class,
        () -> frozen.output(raw.replace("\"version\":1", "\"version\":1,\"extra\":true")));
    assertThrows(
        RuntimeException.class,
        () -> frozen.output(raw.replace("\"version\":1", "\"version\":1,\"version\":1")));
    // Typed API cannot represent unknown/duplicate fields; no raw-input parser was replaced.
  }
}
