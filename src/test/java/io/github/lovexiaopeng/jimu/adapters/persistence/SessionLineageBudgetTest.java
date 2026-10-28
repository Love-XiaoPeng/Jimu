package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionLineageServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Executable lower bound: fixture reads, real production codecs, service and query meters; no SQL.
 */
class SessionLineageBudgetTest {
  private static final String TENANT = "tenant";

  private final Map<String, SessionLineageRecords.Fact> facts = new HashMap<>();
  private final Map<String, SessionLineage.Stored> rows = new HashMap<>();
  private final List<String> ids = new ArrayList<>();

  private void chain() {
    var contracts = new SessionLineageContracts();
    var nodes = new ArrayList<SessionLineage.Node>();
    for (int i = 0; i < 9; i++) {
      String id = "00000000-0000-0000-0000-%012d".formatted(i + 1);
      String request =
          Payloads.json(Map.of("goal", "probe", "history_session_ids", List.copyOf(ids)));
      String requestDigest = Payloads.hash(request);
      String parent = i == 0 ? null : ids.getLast();
      String genesis =
          contracts.genesis(
              new SessionLineage.Genesis(
                  1,
                  id,
                  i == 0 ? "ROOT" : "CONTINUATION",
                  "2026-10-28T10:00:00.000000Z",
                  requestDigest,
                  "a".repeat(64),
                  "b".repeat(64),
                  parent,
                  i == 0 ? null : nodes.getLast().genesisSha256()));
      nodes.add(new SessionLineage.Node(id, genesis, Payloads.hash(genesis)));
      String graph = contracts.graph(new SessionLineage.Graph(1, id, "a".repeat(64), nodes));
      assertEquals(730 + 728 * i, graph.length());
      rows.put(
          id,
          new SessionLineage.Stored(
              genesis,
              Payloads.hash(genesis),
              genesis.length(),
              graph,
              Payloads.hash(graph),
              graph.length(),
              "PRESENT",
              1));
      facts.put(
          id,
          new SessionLineageRecords.Fact(
              TENANT,
              id,
              "2026-10-28T10:00:00.000000Z",
              request,
              requestDigest,
              "a".repeat(64),
              "b".repeat(64),
              List.copyOf(ids),
              parent));
      ids.add(id);
    }
  }

  private final class Probe implements SessionLineageRecords {
    private final MapperSessionLineageRecords codecs;
    private final SessionQueryContext query;
    private long graphHashes, graphParses, completedGraphParses;
    private final Map<String, Long> hashes = new HashMap<>();

    Probe(boolean restricted) throws Exception {
      var env = new StandardEnvironment();
      env.getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("jimu.test-mode", true)));
      var root =
          new JdbcLedger(
              new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
              new ModelProfiles(env));
      var view =
          QueryViewAssemblyTest.view(
              root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      query = view.queryContext();
      if (restricted) query.restrictLineage();
      codecs = new MapperSessionLineageRecords(view);
    }

    public Fact fact(String tenant, String id) {
      return facts.get(id);
    }

    public <T> T withProof(java.util.function.Supplier<T> body) {
      return codecs.withProof(body);
    }

    public boolean matchGraph(SessionLineage.Stored stored, SessionLineage.Graph expected) {
      return codecs.matchGraph(stored, expected);
    }

    public SessionLineage.Stored load(String tenant, String id) {
      return rows.get(id);
    }

    public void insert(String tenant, String id, SessionLineage.Stored value) {
      rows.put(id, value);
    }

    public void body(String tenant, String id, long bytes) {}

    public boolean requestVerified(Fact fact) {
      return false;
    }

    public String hash(String raw) {
      hashes.merge(raw, 1L, Long::sum);
      long before = query.resources().operations();
      try {
        return codecs.hash(raw);
      } finally {
        if (raw.contains("\"nodes\"")) graphHashes += query.resources().operations() - before;
      }
    }

    public int bytes(String raw) {
      return codecs.bytes(raw);
    }

    public String genesis(SessionLineage.Genesis value) {
      return codecs.genesis(value);
    }

    public SessionLineage.Genesis genesis(String raw) {
      return codecs.genesis(raw);
    }

    public String graph(SessionLineage.Graph value) {
      return codecs.graph(value);
    }

    public SessionLineage.Graph graph(String raw) {
      long before = query.resources().operations();
      try {
        var graph = codecs.graph(raw);
        completedGraphParses += raw.length();
        return graph;
      } finally {
        graphParses += query.resources().operations() - before;
      }
    }

    public String output(SessionLineage.Result value) {
      return codecs.output(value);
    }

    public void checkpoint(long count) {
      codecs.checkpoint(count);
    }

    public void scratch(long bytes) {
      codecs.scratch(bytes);
    }
  }

  @Test
  void nineNodeProductionScannerExceedsOriginalBudgetBeforeSqlAuthorityOrOutput() throws Exception {
    chain();
    var full = new Probe(false);
    assertEquals(
        9, new SessionLineageServiceImpl(full).verify(TENANT, ids.getLast()).nodes().size());
    assertEquals(32778L, full.query.completedWork().get("parse.graph"));
    assertTrue(full.query.resources().operations() > 74852);
    System.out.println(
        "NINE_NODE_LOWER_BOUND hash="
            + full.graphHashes
            + " parse="
            + full.graphParses
            + " total="
            + full.query.resources().operations()
            + " scratch="
            + full.query.scratchPeak());
    var bounded = new Probe(true);
    var failure =
        assertThrows(
            TaskFailure.class,
            () -> new SessionLineageServiceImpl(bounded).verify(TENANT, ids.getLast()));
    assertEquals("SESSION_HISTORY_LIMIT", failure.code());
    assertTrue(bounded.query.resources().operations() > 65536);
    System.out.println(
        "NINE_NODE_REJECTION operations="
            + bounded.query.resources().operations()
            + " hash="
            + bounded.graphHashes
            + " parseCharged="
            + bounded.graphParses
            + " parseCompleted="
            + bounded.completedGraphParses);
    var evidence = java.nio.file.Path.of("target", "session-lineage-budget-evidence.json");
    java.nio.file.Files.writeString(
        evidence,
        Payloads.json(
            Map.of(
                "scope",
                "fixture reads; production codec/service/query; no SQL or authority scans",
                "facts",
                facts,
                "stored",
                rows,
                "full",
                Map.of(
                    "operations",
                    full.query.resources().operations(),
                    "graphHash",
                    full.graphHashes,
                    "graphParse",
                    full.completedGraphParses),
                "restricted",
                Map.of(
                    "code",
                    failure.code(),
                    "operationsCharged",
                    bounded.query.resources().operations(),
                    "graphHash",
                    bounded.graphHashes,
                    "graphParseCharged",
                    bounded.graphParses,
                    "graphParseCompleted",
                    bounded.completedGraphParses,
                    "scratchPeak",
                    bounded.query.scratchPeak()))));
  }

  @Test
  void defaultPortFallbackKeepsGeneratedRawAndDigestConsistent() throws Exception {
    chain();
    String caller = ids.getLast();
    rows.remove(caller);
    var probe = new Probe(false);
    new SessionLineageServiceImpl(probe).create(TENANT, caller, ids.get(7));
    assertEquals(Payloads.hash(rows.get(caller).genesis()), rows.get(caller).genesisDigest());
    assertEquals(Payloads.hash(rows.get(caller).graph()), rows.get(caller).graphDigest());
  }
}
