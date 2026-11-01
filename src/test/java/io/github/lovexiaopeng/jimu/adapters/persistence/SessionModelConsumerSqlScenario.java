package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Compare the three ordinary consumers against their former full step SELECT. */
final class SessionModelConsumerSqlScenario {
  private static final String STEP = "20000000-0000-0000-0000-000000000020";
  private static final String ROOT = "io.github.lovexiaopeng.jimu.adapters.persistence.";

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  static void verify(JdbcTemplate db, String task) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    db.update(
        "UPDATE step SET request_snapshot=?,session_upper_seq=7,session_context_digest=REPEAT('b',64),session_binding_digest=REPEAT('c',64) WHERE id=?",
        "x".repeat(8159) + "字😀x",
        STEP);
    for (String response : new String[] {null, "{\"content\":\"字😀\",\"calls\":[]}"}) {
      db.update("UPDATE step SET response_snapshot=? WHERE id=?", response, STEP);
      var before = SessionDependencyScenario.snapshot(db);
      for (String consumer : List.of("response", "fact", "binding")) {
        String[] columns =
            switch (consumer) {
              case "response" ->
                  new String[] {"tenant_id", "id", "task_id", "dispatch_id", "response_snapshot"};
              case "fact" -> MapperSessionFacts.LINEAGE_STEP.split(",");
              default -> MapperSessionCallBindings.LINEAGE_STEP.split(",");
            };
        Map<String, Object> baseline = null;
        long prior = 0;
        for (boolean narrow : List.of(false, true)) {
          var q =
              new SessionQueryContext(
                  System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
          q.restrictLineage();
          var row =
              tx.required(
                  q,
                  () -> {
                    SessionQueryTransaction.current(q).locked("tenant-a", task);
                    if (consumer.equals("response"))
                      return mappers.<Map<String, Object>>selectOne(
                          ROOT
                              + "execution.mapper.StepMapper."
                              + (narrow ? "selectLineageModelResponse" : "selectModelStep"),
                          Map.of("query", q, "tenant", "tenant-a", "stepId", STEP, "taskId", task));
                    var wrapper =
                        new QueryWrapper<>()
                            .select(narrow ? String.join(",", columns) : Schema22Columns.STEP)
                            .eq("tenant_id", "tenant-a")
                            .eq("id", STEP)
                            .eq("task_id", task);
                    if (consumer.equals("binding"))
                      wrapper.apply(
                          "(NOT {0} OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
                          true);
                    return mappers.<Map<String, Object>>selectOne(
                        ROOT
                            + "session.mapper."
                            + (consumer.equals("fact")
                                ? "SessionFactStepMapper"
                                : "ScopeQueryStepReadMapper")
                            + ".selectMaps",
                        Map.of("query", q, "ew", wrapper));
                  });
          if (!narrow) {
            baseline = row;
            prior = q.resources().operations();
          } else {
            for (String column : columns) {
              Object old = baseline.get(column), fresh = row.get(column);
              check(
                  Objects.equals(old, fresh) && (old == null || old.getClass() == fresh.getClass()),
                  consumer + " actual consumed value/type/NULL " + column);
            }
            check(
                !row.containsKey("request_snapshot"),
                consumer + " discarded request is not materialized");
            check(
                q.resources().operations() < prior,
                consumer + " full affected read has positive saving");
          }
          check(
              q.resources().reads() == 1 && q.scratchLive() == 65536,
              consumer + " independent fresh read and owners released");
          check(
              before.equals(SessionDependencyScenario.snapshot(db)),
              consumer + " 22 tables unchanged");
          System.out.println(
              "MODEL_CONSUMER consumer="
                  + consumer
                  + " response="
                  + (response != null)
                  + " narrow="
                  + narrow
                  + " prior="
                  + prior
                  + " resources="
                  + q.resources());
        }
      }
    }
  }

  static void verifyQueryTask(JdbcTemplate db, String task) {
    verifyTask(db, task, false);
  }

  static void verifyModelTask(JdbcTemplate db, String task) {
    verifyTask(db, task, true);
  }

  private static void verifyTask(JdbcTemplate db, String task, boolean modelAdmission) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    var before = SessionDependencyScenario.snapshot(db);
    Map<String, Object> prior = null;
    long cost = 0;
    for (boolean narrow : List.of(false, true)) {
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      var row =
          tx.required(
              q,
              () -> {
                SessionQueryTransaction.current(q).locked("tenant-a", task);
                return mappers.<Map<String, Object>>selectOne(
                    ROOT
                        + "task.mapper.TaskMapper."
                        + (narrow
                            ? modelAdmission
                                ? "selectModelAdmissionTaskForUpdate"
                                : "selectLineageQueryTaskForUpdate"
                            : "selectTaskForUpdate"),
                    Map.of("query", q, "tenant", "tenant-a", "taskId", task));
              });
      if (!narrow) {
        prior = row;
        cost = q.resources().operations();
      } else {
        for (var entry : row.entrySet()) {
          Object old = prior.get(entry.getKey()), fresh = entry.getValue();
          check(
              Objects.equals(old, fresh) && (old == null || old.getClass() == fresh.getClass()),
              "scope query source value/type/NULL " + entry.getKey());
        }
        check(
            !row.containsKey("request_snapshot")
                && !row.containsKey("summary")
                && row.containsKey("models_used") == modelAdmission
                && row.containsKey("tools_used"),
            "task projection deletes only unused fields and preserves M1 model allowances");
        check(q.resources().operations() < cost, "complete scope-query task read saves operations");
      }
      check(
          q.resources().reads() == 1
              && q.scratchLive() == 65536
              && before.equals(SessionDependencyScenario.snapshot(db)),
          "scope query fresh lock read, 22 tables unchanged and all owners released");
      System.out.println("QUERY_TASK narrow=" + narrow + " resources=" + q.resources());
    }
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    tx.required(
        q,
        () -> {
          var sql =
              mappers.getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class);
          check(
              (modelAdmission
                      ? sql.selectModelAdmissionTaskForUpdate(q, "foreign-tenant", task)
                      : sql.selectLineageQueryTaskForUpdate(q, "foreign-tenant", task))
                  .isEmpty(),
              "scope query cannot cross tenant");
          check(
              (modelAdmission
                      ? sql.selectModelAdmissionTaskForUpdate(q, "tenant-a", "missing")
                      : sql.selectLineageQueryTaskForUpdate(q, "tenant-a", "missing"))
                  .isEmpty(),
              "scope query cannot cross task");
          return null;
        });
    check(q.scratchLive() == 65536, "empty scope-query results release without row qualification");
  }
}
