package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Same full FINAL entry on preserved R40 and new jars; private frozen SQL rows only. */
public final class SessionFinalMapperSqlScenario {
  private static final String ID = "7bde5bee-94a6-49ef-a663-dc1c8488c8cc";
  private static final String EMPTY = "00000000-0000-0000-0000-000000000000";

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
    var json = new ObjectMapper().readTree(Files.readAllBytes(Path.of(args[1])));
    if (args[2].equals("seed")) {
      seed(db, json);
      var seedFull =
          SessionBorrowedMetadataSqlScenario.class.getDeclaredMethod("seed", JdbcTemplate.class);
      seedFull.setAccessible(true);
      seedFull.invoke(null, db);
    }
    var mappers = LedgerMapperSessions.create(source);
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    // The first call deliberately starts static initialization inside its actual Work.
    boolean allTables = args.length > 3 && args[3].equals("all-final-source");
    boolean slots = allTables || (args.length > 3 && args[3].equals("slot-source"));
    boolean sourceOnly = args.length > 3 && args[3].equals("source-only");
    for (String population :
        slots
            ? List.of("cold-empty", "full-source")
            : sourceOnly
                ? List.of("cold-empty", "frozen-source")
                : List.of("cold-empty", "empty", "frozen", "full", "frozen-source")) {
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      var records = new JdbcSessionRecords(db, q, mappers);
      String id =
          population.startsWith("frozen")
              ? ID
              : population.startsWith("full") ? "20000000-0000-0000-0000-000000000001" : EMPTY;
      var task =
          Map.<String, Object>of(
              "tenant_id",
              "tenant-a",
              "id",
              id,
              "capabilities",
              SessionLineagePolicyV5.CAPABILITIES);
      var totals = new LinkedHashMap<String, Object>();
      long before = q.resources().operations();
      tx.required(
          q,
          () -> {
            SessionQueryTransaction.current(q).locked("tenant-a", id);
            if (population.endsWith("source")) {
              // Test-only Work shape from the saved original source. This measures FINAL's
              // source-lock loop, not admission validation or an authority grant.
              var work = SessionQueryTransaction.current(q);
              work.admission();
              var fact = sourceFact(json);
              work.locked(fact.caller().tenant(), fact.caller().id());
              work.stageSource(fact.caller(), fact);
              if (slots) {
                // Repeat actual public loaders: four independent raw owners and the event
                // Slot, all with current source membership. Complete FINAL and Work cleanup
                // remain in this measured segment; this is not an admission transaction.
                long prefix = q.resources().operations();
                var values = new java.util.ArrayList<String>();
                for (int i = 0; i < 4; i++) {
                  var rows =
                      io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryRows
                          .load(mappers, q, new String("tenant-a"), new String(ID));
                  values.add(rows.getFirst().get("binding_snapshot").toString());
                }
                var events =
                    io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionEventHistoryRows
                        .load(mappers, q, new String("tenant-a"), new String(ID));
                values.add(events.getFirst().get("binding_snapshot").toString());
                totals.put("slotValues", values);
                totals.put("slotOperations", q.resources().operations() - prefix);
              }
            }
            var accounting = new MapperSessionMetadataAccounting(records, task, true);
            try (var scope = accounting.finalization()) {
              for (var table : Table.values()) {
                if (!allTables && (table == Table.TASK || table == Table.TASK_SESSION_HISTORY))
                  continue;
                totals.put(table.name(), accounting.finalTable(table));
              }
            }
            return null;
          });
      if (q.scratchLive() != 65536) throw new AssertionError("FINAL owners leaked");
      System.out.println(
          "FINAL_MAPPER_COST "
              + Payloads.json(
                  Map.of(
                      "population",
                      population,
                      "operations",
                      q.resources().operations() - before,
                      "bytes",
                      q.resources().bytes(),
                      "reads",
                      q.resources().reads(),
                      "scratchLive",
                      q.scratchLive(),
                      "summaries",
                      totals)));
      System.out.flush();
    }
    if (args[2].equals("task-check")) SessionFinalMapperFailures.verify(source, true);
    else if (!args[2].equals("seed") && !args[2].equals("cost-only") && !sourceOnly)
      SessionFinalMapperFailures.verify(source);
  }

  private static SessionScopeRecords.SourceFact sourceFact(JsonNode saved) {
    var t = saved.path("task").get(0);
    var s = saved.path("task_session_scope").get(0);
    var h = saved.path("task_session_history").get(0);
    JsonNode a;
    try {
      a = new ObjectMapper().readTree(s.path("authority_snapshot").asText());
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
    var caller =
        new SessionScopeRecords.Caller(
            "tenant-a",
            ID,
            t.path("owner_actor_id").asText(),
            t.path("capabilities").asText(),
            t.path("tools_digest").asText(),
            t.path("installation_epoch").asLong(),
            t.path("state").asText(),
            LocalDateTime.parse(t.path("created_at").asText()),
            t.path("request_digest").asText());
    var scope =
        new SessionScopeRecords.Scope(
            s.path("record_version").asLong(),
            s.path("authority_version").asLong(),
            s.path("owner_actor_id").asText(),
            s.path("caller_binding_digest").asText(),
            s.path("caller_tools_digest").asText(),
            s.path("authority_snapshot").asText(),
            s.path("authority_digest").asText(),
            s.path("authority_bytes").asLong(),
            s.path("candidate_count").asLong(),
            s.path("grant_state").asText(),
            LocalDateTime.parse(s.path("created_at").asText()),
            LocalDateTime.parse(s.path("retain_until").asText()),
            null,
            null,
            LocalDateTime.parse(s.path("next_validation_at").asText()),
            s.path("content_state").asText(),
            true);
    var authority =
        new SessionScopeRecords.Authority(
            a.path("tenant_id").asText(),
            a.path("caller_session_id").asText(),
            a.path("owner_actor_id").asText(),
            a.path("caller_tools_sha256").asText(),
            a.path("caller_binding_sha256").asText(),
            a.path("created_at").asText(),
            a.path("retain_until").asText(),
            List.of());
    return new SessionScopeRecords.SourceFact(
        caller,
        new SessionScopeRecords.History(
            h.path("next_seq").asLong(),
            h.path("binding_digest").asText(),
            LocalDateTime.parse(h.path("retain_until").asText())),
        scope,
        authority,
        List.of());
  }

  private static void seed(JdbcTemplate db, JsonNode frozen) {
    db.update("INSERT INTO tenant_budget(tenant_id) VALUES('tenant-a')");
    db.update("INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice')");
    // Full R40 snapshots include the exact FK parents and all 22 original tables.
    for (String table :
        List.of(
            "task",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "invocation_session_scope_query",
            "task_session_lineage",
            "invocation_session_lineage_trace")) {
      var types = new LinkedHashMap<String, String>();
      db.query(
          "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
          rs -> {
            types.put(rs.getString(1), rs.getString(2));
          },
          table);
      for (var row : frozen.path(table)) {
        var columns = new ArrayList<String>();
        var values = new ArrayList<Object>();
        row.fields()
            .forEachRemaining(
                entry -> {
                  String name = entry.getKey();
                  if (!types.containsKey(name))
                    throw new AssertionError("Unknown frozen column " + name);
                  columns.add("`" + name + "`");
                  var node = entry.getValue();
                  Object value =
                      node.isNull()
                          ? null
                          : node.isBoolean()
                              ? node.booleanValue()
                              : node.isIntegralNumber() ? node.longValue() : node.asText();
                  if (value instanceof String text && types.get(name).equals("datetime"))
                    value = LocalDateTime.parse(text.replace(' ', 'T'));
                  values.add(value);
                });
        db.update(
            "INSERT INTO `"
                + table
                + "` ("
                + String.join(",", columns)
                + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(values.size(), "?"))
                + ")",
            values.toArray());
      }
    }
  }
}
