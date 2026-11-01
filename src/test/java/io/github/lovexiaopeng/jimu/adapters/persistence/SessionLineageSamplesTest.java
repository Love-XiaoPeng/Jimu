package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionLineageServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 127原19样本实际进入生产Java Records/Service/计量；fixture读端口不代表SQL或持锁验证。 */
class SessionLineageSamplesTest {
  private final ObjectMapper json = new ObjectMapper();

  private final class Records implements SessionLineageRecords {
    private final JsonNode fixture;
    private final MapperSessionLineageRecords codec;
    private final SessionQueryContext query;
    private VerifiedGraph verified;

    Records(JsonNode fixture, boolean limited) throws Exception {
      this.fixture = fixture;
      var env = new StandardEnvironment();
      env.getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("jimu.test-mode", true)));
      var ledger =
          new JdbcLedger(
              new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
              new ModelProfiles(env));
      var view =
          QueryViewAssemblyTest.view(
              ledger, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      query = view.queryContext();
      if (limited) query.restrictLineage();
      codec = new MapperSessionLineageRecords(view);
    }

    public Fact fact(String tenant, String id) {
      return json.convertValue(fixture.path("facts").path(id), Fact.class);
    }

    public SessionLineage.Stored load(String tenant, String id) {
      return json.convertValue(fixture.path("stored").path(id), SessionLineage.Stored.class);
    }

    public void insert(String tenant, String id, SessionLineage.Stored row) {
      throw new AssertionError("read only");
    }

    public void body(String tenant, String id, long bytes) {
      throw new AssertionError("read only");
    }

    public String hash(String raw) {
      return codec.hash(raw);
    }

    public int bytes(String raw) {
      return codec.bytes(raw);
    }

    public String genesis(SessionLineage.Genesis value) {
      return codec.genesis(value);
    }

    public SessionLineage.Genesis genesis(String raw) {
      return codec.genesis(raw);
    }

    public String graph(SessionLineage.Graph value) {
      return codec.graph(value);
    }

    public SessionLineage.Graph graph(String raw) {
      return codec.graph(raw);
    }

    public boolean matchGraph(SessionLineage.Stored stored, SessionLineage.Graph expected) {
      return codec.matchGraph(stored, expected);
    }

    public String output(SessionLineage.Result result) {
      return codec.output(result);
    }

    public void checkpoint(long count) {
      codec.checkpoint(count);
    }

    public void scratch(long bytes) {
      codec.scratch(bytes);
    }

    public <T> T withProof(java.util.function.Supplier<T> work) {
      return codec.withProof(work);
    }

    public VerifiedGraph verified(String tenant, String caller) {
      return verified;
    }

    public void verified(String tenant, String caller, VerifiedGraph value) {
      verified = value;
    }
  }

  @TestFactory
  List<DynamicTest> architectureSamplesEnterProductionScanner() throws Exception {
    JsonNode cases;
    try (var input =
        new GZIPInputStream(
            getClass().getResourceAsStream("/session-lineage/architecture-19.json.gz"))) {
      cases = json.readTree(input);
    }
    assertEquals(19, cases.size());
    var tests = new java.util.ArrayList<DynamicTest>();
    for (var sample : cases)
      tests.add(
          DynamicTest.dynamicTest(
              sample.path("name").asText(),
              () -> {
                var records = new Records(sample.path("fixture"), false);
                var service = new SessionLineageServiceImpl(records);
                String caller = "00000000-0000-0000-0000-000000000009";
                String expected = sample.path("code").asText();
                if (!"OK".equals(expected)) {
                  assertEquals(
                      expected,
                      assertThrows(TaskFailure.class, () -> service.verify("tenant", caller))
                          .code());
                } else {
                  var graph = service.verify("tenant", caller);
                  assertEquals(9, graph.nodes().size());
                  long hash = records.query.completedWork().get("hash");
                  long outputCharacters = 0;
                  for (int i = 0; i < 9; i++) {
                    String target = "00000000-0000-0000-0000-%012d".formatted(i + 1);
                    var observation = service.observe("tenant", caller, target);
                    outputCharacters += observation.output().length();
                    assertEquals(
                        sample.path("outputs").get(i), json.readTree(observation.output()));
                    assertEquals(graph.scanDigest(), observation.scanDigest());
                  }
                  assertEquals(
                      hash + outputCharacters,
                      records.query.completedWork().get("hash"),
                      "scan proof hashes once per bundle");
                  if (sample.path("name").asText().equals("exact-nine-chain")) {
                    assertEquals(32778L, records.query.completedWork().get("parse.graph"));
                    var evidence =
                        Map.of(
                            "requested",
                            records.query.requestedWork(),
                            "completed",
                            records.query.completedWork(),
                            "operations",
                            records.query.resources().operations(),
                            "scratchPeak",
                            records.query.scratchPeak(),
                            "scratchAfterVerify",
                            records.query.scratchLive(),
                            "scope",
                            "production Java scanner; fixture reads, no SQL/authority");
                    java.nio.file.Files.writeString(
                        java.nio.file.Path.of("target/lineage-java-19-work.json"),
                        Payloads.json(evidence));
                    var bounded = new Records(sample.path("fixture"), true);
                    assertEquals(
                        "SESSION_HISTORY_LIMIT",
                        assertThrows(
                                TaskFailure.class,
                                () ->
                                    new SessionLineageServiceImpl(bounded).verify("tenant", caller))
                            .code());
                    assertTrue(bounded.query.resources().operations() > 65536);
                    assertEquals(65536, bounded.query.scratchLive());
                  }
                }
              }));
    return tests;
  }
}
