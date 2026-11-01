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

  static void verifyNativeModelTask(JdbcTemplate db, String task) {
    verifyModelTask(db, task);
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mapper = LedgerMapperSessions.create(source);
    var before = SessionDependencyScenario.snapshot(db);
    for (boolean custom : List.of(false, true)) {
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      Throwable caught = null;
      try {
        tx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", task);
              var connection =
                  org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
              com.mysql.cj.NativeCharsetSettings settings = null;
              java.lang.reflect.Field mapping = null;
              Object prior = null;
              try {
                var jdbc = (com.mysql.cj.jdbc.JdbcConnection) connection;
                settings =
                    (com.mysql.cj.NativeCharsetSettings)
                        jdbc.getSession().getServerSession().getCharsetSettings();
                mapping =
                    com.mysql.cj.NativeCharsetSettings.class.getDeclaredField(
                        "collationIndexToCharsetName");
                mapping.setAccessible(true);
                prior = mapping.get(settings);
                if (custom) {
                  // Change the current native mapping, not the Field encoding or a static table.
                  var replacement = new java.util.HashMap<Integer, String>();
                  if (prior instanceof Map<?, ?> old)
                    old.forEach((key, value) -> replacement.put((Integer) key, (String) value));
                  try (var command =
                      connection.prepareStatement(
                          "SELECT owner_actor_id FROM task WHERE tenant_id=? AND id=?")) {
                    command.setString(1, "tenant-a");
                    command.setString(2, task);
                    try (var rows = command.executeQuery()) {
                      check(
                          rows.getClass() == com.mysql.cj.jdbc.result.ResultSetImpl.class,
                          "exact Connector rows exercised");
                      var metadata =
                          (com.mysql.cj.jdbc.result.ResultSetMetaData) rows.getMetaData();
                      replacement.put(metadata.getFields()[0].getCollationIndex(), "latin1");
                    }
                  }
                  mapping.set(settings, replacement);
                }
                for (String statement :
                    List.of(
                        "selectModelAdmissionTaskForUpdate",
                        "selectExtendedModelAdmissionTaskForUpdate")) {
                  var row =
                      mapper.<Map<String, Object>>selectOne(
                          ROOT + "task.mapper.TaskMapper." + statement,
                          Map.of("query", q, "tenant", "tenant-a", "taskId", task));
                  check(
                      !custom && row.size() == (statement.startsWith("selectExtended") ? 55 : 32),
                      "native current task full projection " + statement);
                }
              } catch (ReflectiveOperationException | java.sql.SQLException failure) {
                throw new AssertionError(failure);
              } finally {
                if (mapping != null && settings != null) {
                  try {
                    mapping.set(settings, prior);
                  } catch (IllegalAccessException failure) {
                    throw new AssertionError(failure);
                  }
                }
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                    connection, source);
              }
              return null;
            });
      } catch (RuntimeException | Error failure) {
        caught = failure;
      }
      if (custom) {
        check(
            caught != null && !(caught instanceof AssertionError),
            "native current custom charset rejected");
        Throwable cursor = caught;
        while (cursor != null
            && !String.valueOf(cursor.getMessage()).contains("Changed model task charset"))
          cursor = cursor.getCause();
        check(cursor != null, "native charset is checked through the current mapping");
      } else if (caught != null) throw new AssertionError("native task rejected", caught);
      check(
          q.scratchLive() == 65536
              && SessionQueryTransaction.currentOwner() == null
              && before.equals(SessionDependencyScenario.snapshot(db)),
          "native mapping 22 tables unchanged and all owners released");
    }
  }

  static void verifyModelTaskFaults(JdbcTemplate db, String task) {
    verifyModelTaskFaults(db, task, null);
  }

  static void verifyModelTaskBoundaries(JdbcTemplate db, String task) {
    verifyModelTaskFaults(
        db, task, List.of("none", "metadata-write", "write", "extra", "error", "close-write"));
  }

  private static void verifyModelTaskFaults(JdbcTemplate db, String task, List<String> selected) {
    if (selected == null) {
      verifyModelTask(db, task);
      verifyModelTaskExtensions(db, task);
    }
    var source = new SessionFinalMapperFailures.FaultSource(db.getDataSource());
    var mapper = LedgerMapperSessions.create(source);
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    String statement = ROOT + "task.mapper.TaskMapper.selectModelAdmissionTaskForUpdate";
    var before = SessionDependencyScenario.snapshot(db);
    for (String fault :
        selected != null
            ? selected
            : List.of(
                "none",
                "source-count",
                "source-type",
                "source-table",
                "source-name",
                "source-charset",
                "source-encoding",
                "foreign",
                "connection",
                "write",
                "metadata-write",
                "next-write",
                "begin",
                "before-close-write",
                "incomplete",
                "extra",
                "close-write",
                "error",
                "close")) {
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      var other =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      other.restrictLineage();
      source.table = "task";
      source.fault = fault;
      source.changed = false;
      source.closed = false;
      source.getters = 0;
      source.statements = 0;
      source.binds.clear();
      source.primary =
          fault.equals("error")
              ? new AssertionError("M1 getter Error")
              : new java.sql.SQLException("M1 close");
      source.secondary =
          fault.equals("error") ? new java.sql.SQLException("M1 suppressed close") : null;
      source.write =
          () ->
              mapper
                  .getMapper(
                      io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                          .SessionContextMapper.class)
                  .account(q, 1, "tenant-a", task);
      Throwable caught = null;
      try {
        tx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", task);
              if (fault.equals("before-close-write") || fault.equals("incomplete")) {
                var connection =
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
                try (var prepared =
                    connection.prepareStatement(
                        mapper
                            .getConfiguration()
                            .getMappedStatement(statement)
                            .getBoundSql(Map.of("tenant", "tenant-a", "taskId", task))
                            .getSql())) {
                  prepared.setString(1, "tenant-a");
                  prepared.setString(2, task);
                  prepared.execute();
                  try (var rows =
                      io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults.meter(
                          prepared.getResultSet(), q, statement)) {
                    if (fault.equals("before-close-write")) {
                      source.changed = true;
                      source.write.run();
                    }
                  }
                } catch (java.sql.SQLException failure) {
                  throw new org.springframework.dao.DataAccessResourceFailureException(
                      "M1 close fixture", failure);
                } finally {
                  org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                      connection, source);
                }
                throw new AssertionError("M1 task fault accepted " + fault);
              }
              try (var meter =
                  q.meter(
                      () -> {
                        if (fault.equals("begin") && !source.changed) {
                          source.changed = true;
                          source.write.run();
                        }
                        return (label, value) -> {
                          if (fault.equals("extra") && source.getters > 0 && !source.changed) {
                            source.changed = true;
                            source.write.run();
                          }
                        };
                      })) {
                Map<String, Object> result =
                    mapper.selectOne(
                        statement,
                        Map.of(
                            "query",
                            fault.equals("foreign") ? other : q,
                            "tenant",
                            "tenant-a",
                            "taskId",
                            task));
                check(
                    result.size() == 32 && result.containsKey("max_tokens"),
                    "complete M1 source row");
                if (!fault.equals("none"))
                  throw new AssertionError("M1 task fault accepted " + fault);
              }
              return null;
            });
      } catch (RuntimeException | Error failure) {
        caught = failure;
      }
      if (fault.equals("none")) {
        if (caught != null) throw new AssertionError("M1 positive source rejected", caught);
      } else {
        if (caught == null
            || caught instanceof AssertionError a
                && a.getMessage().startsWith("M1 task fault accepted"))
          throw new AssertionError("M1 failed to reject " + fault, caught);
        if (fault.startsWith("source-")
            || fault.equals("metadata-write")
            || fault.equals("next-write"))
          check(source.getters == 0, "M1 refuses before value getters " + fault);
        if (fault.equals("write"))
          check(source.getters == 1, "M1 stops immediately after getter write");
        if (fault.equals("foreign")) check(source.statements == 0, "M1 foreign owner before SQL");
        if (fault.equals("begin")
            || fault.equals("before-close-write")
            || fault.equals("incomplete"))
          check(source.getters == 0 && source.closed, "M1 closes unconsumed source " + fault);
        if (fault.equals("error"))
          check(
              SessionFinalMapperFailures.contains(caught, source.primary)
                  && SessionFinalMapperFailures.contains(source.primary, source.secondary),
              "M1 Error/suppressed close preserved");
      }
      check(
          q.scratchLive() == 65536
              && SessionQueryTransaction.currentOwner() == null
              && before.equals(SessionDependencyScenario.snapshot(db)),
          "M1 22-table rollback and owners released " + fault);
      System.out.println(
          "MODEL_TASK_FAULT "
              + fault
              + " getters="
              + source.getters
              + " operations="
              + q.resources().operations());
      System.out.flush();
    }
  }

  private static void verifyModelTaskExtensions(JdbcTemplate db, String task) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mapper = LedgerMapperSessions.create(source);
    var before = SessionDependencyScenario.snapshot(db);
    int version =
        db.queryForObject(
            "SELECT record_version FROM task WHERE tenant_id='tenant-a' AND id=?",
            Integer.class,
            task);
    for (String field :
        List.of(
            "evaluation_snapshot",
            "evaluation_digest",
            "evaluation_phase",
            "evaluation_sample",
            "evaluation_model",
            "material_snapshot",
            "material_digest",
            "material_system_digest",
            "material_file_snapshot",
            "material_file_digest",
            "execution_scope_digest",
            "business_policy_snapshot",
            "business_policy_digest",
            "media_snapshot",
            "media_digest",
            "media_cleanup_state",
            "mcp_snapshot",
            "mcp_digest",
            "question_binding",
            "question_binding_digest",
            "waiting_question_id",
            "question_continuation_step_id",
            "question_cleanup_state",
            "question_retained_digests")) {
      Object old =
          db.queryForObject(
              "SELECT " + field + " FROM task WHERE tenant_id='tenant-a' AND id=?",
              String.class,
              task);
      check(old == null, "M1 extension fixture starts NULL " + field);
      String value =
          field.endsWith("digest") ? "a".repeat(64) : field.endsWith("snapshot") ? "{}" : "x";
      boolean mediaPair = field.equals("media_snapshot") || field.equals("media_digest");
      boolean mcpPair = field.equals("mcp_snapshot") || field.equals("mcp_digest");
      try {
        if (mediaPair)
          db.update(
              "UPDATE task SET record_version=3,media_snapshot='{}',media_digest=REPEAT('a',64) WHERE tenant_id='tenant-a' AND id=?",
              task);
        else if (mcpPair)
          db.update(
              "UPDATE task SET mcp_snapshot='{}',mcp_digest=REPEAT('a',64) WHERE tenant_id='tenant-a' AND id=?",
              task);
        else
          db.update(
              "UPDATE task SET " + field + "=? WHERE tenant_id='tenant-a' AND id=?", value, task);
        var q =
            new SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
        q.restrictLineage();
        tx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", task);
              var args = Map.of("query", q, "tenant", "tenant-a", "taskId", task);
              var core =
                  mapper.<Map<String, Object>>selectOne(
                      ROOT + "task.mapper.TaskMapper.selectModelAdmissionTaskForUpdate", args);
              check(
                  Long.valueOf(1).equals(core.get("model_admission_extended")),
                  "M1 detects current extension " + field);
              var full =
                  mapper.<Map<String, Object>>selectOne(
                      ROOT + "task.mapper.TaskMapper.selectExtendedModelAdmissionTaskForUpdate",
                      args);
              check(
                  full.size() == 55 && value.equals(full.get(field)),
                  "M1 full source retains extension value/type " + field);
              return null;
            });
        check(
            q.scratchLive() == 65536 && SessionQueryTransaction.currentOwner() == null,
            "M1 extension owners released " + field);
      } finally {
        if (mediaPair)
          db.update(
              "UPDATE task SET record_version=?,media_snapshot=NULL,media_digest=NULL WHERE tenant_id='tenant-a' AND id=?",
              version,
              task);
        else if (mcpPair)
          db.update(
              "UPDATE task SET mcp_snapshot=NULL,mcp_digest=NULL WHERE tenant_id='tenant-a' AND id=?",
              task);
        else
          db.update("UPDATE task SET " + field + "=NULL WHERE tenant_id='tenant-a' AND id=?", task);
      }
    }
    check(before.equals(SessionDependencyScenario.snapshot(db)), "M1 extension 22 tables restored");
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
          if (entry.getKey().equals("model_admission_extended")) {
            check(Long.valueOf(0).equals(entry.getValue()), "M1 absent extension source flag");
            continue;
          }
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
        // M1 now pays its full source/callback gates; compare semantics, report that cost below.
        // The unchanged scope-query projection retains its original saving assertion.
        if (!modelAdmission)
          check(
              q.resources().operations() < cost, "complete scope-query task read saves operations");
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
          if (modelAdmission) {
            SessionQueryTransaction.current(q).locked("foreign-tenant", task);
            SessionQueryTransaction.current(q).locked("tenant-a", "missing");
          }
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
