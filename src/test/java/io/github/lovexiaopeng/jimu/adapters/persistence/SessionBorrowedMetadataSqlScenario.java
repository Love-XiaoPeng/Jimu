package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 私库metadata形状夹具；不把手工PENDING/JSON行当成真实授权或业务会话。 */
final class SessionBorrowedMetadataSqlScenario {
  private static final String ID = "20000000-0000-0000-0000-000000000001";
  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.";
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  private static String id(int n) {
    return "20000000-0000-0000-0000-%012d".formatted(n);
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static SessionQueryContext query(long initial) {
    var q =
        new SessionQueryContext(
            System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, initial));
    q.restrictLineage();
    return q;
  }

  private static long bytes(Object value) {
    if (value == null) return 1;
    String raw =
        value instanceof java.time.LocalDateTime time
            ? UTC.format(time)
            : value instanceof java.sql.Timestamp stamp
                ? UTC.format(stamp.toLocalDateTime())
                : value.toString();
    return raw.getBytes(StandardCharsets.UTF_8).length;
  }

  private static long scan(SessionCursorMetadataBatch batch) {
    long total = 0;
    for (var row : batch) {
      total += 32;
      for (var value : row.values()) total += 8 + batch.scalarBytes(value);
    }
    return total;
  }

  private static void seed(JdbcTemplate db) {
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice',?,REPEAT('a',64),'','metadata 字😀','session_event_read,session_search,session_trace',1,'FAILED',TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)),1,1)",
        ID,
        ID);
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,5,'{}',REPEAT('a',64),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)))",
        ID);
    db.update(
        "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant-a',?,'{}',REPEAT('a',64),2,'{}',REPEAT('a',64),2)",
        ID);
    db.update(
        "INSERT INTO task_session_scope(tenant_id,caller_task_id,owner_actor_id,caller_binding_digest,caller_tools_digest,authority_snapshot,authority_digest,authority_bytes,candidate_count,created_at,retain_until,next_validation_at) VALUES('tenant-a',?,'alice',REPEAT('a',64),REPEAT('a',64),'{}',REPEAT('a',64),2,0,CURRENT_TIMESTAMP(6),TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))",
        ID);
    for (int n = 0; n < 2; n++) {
      String attempt = id(10 + n), step = id(20 + n), invocation = id(30 + n);
      db.update(
          "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent,ended_at) VALUES('tenant-a',?,?,?,?,'FAILED',?,?)",
          attempt,
          ID,
          id(90),
          n + 1,
          n == 1,
          n == 0 ? null : java.time.LocalDateTime.of(2026, 10, 29, 1, 2, 3, 456789000));
      db.update(
          "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest) VALUES('tenant-a',?,?,?,?,'FAILED','{}',REPEAT('a',64))",
          step,
          ID,
          attempt,
          n);
      db.update(
          "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES('tenant-a',?,?,?,?,?,'slot','provider',?,'session_trace','0.1.0',REPEAT('a',64),1,'{}',REPEAT('a',64),'PENDING')",
          invocation,
          ID,
          step,
          attempt,
          n,
          id(40 + n));
      db.update(
          "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state) VALUES('tenant-a',?,?,?,REPEAT('a',64),REPEAT('a',64),'PREPARED')",
          id(50 + n),
          ID,
          step);
      db.update(
          "INSERT INTO reservation(tenant_id,id,task_id,category,amount,actual,state,usage_quality) VALUES('tenant-a',?,?,'MODEL',?,?, 'SETTLED','KNOWN')",
          id(60 + n),
          ID,
          n == 0 ? 0 : Long.MAX_VALUE,
          n == 0 ? null : 0L);
      db.update(
          "INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,code,status) VALUES('tenant-a',?,?,?,?, 'metadata',?,'FAILED')",
          ID,
          n + 1,
          id(70 + n),
          id(70 + n),
          n == 0 ? null : "中文😀");
      db.update(
          "INSERT INTO session_public_event(tenant_id,task_id,seq,event_version,fact_key,type,occurred_at,event_snapshot,event_digest,event_bytes,source_digest,reliable_event_seq) VALUES('tenant-a',?,?,5,?,'user.message',CURRENT_TIMESTAMP(6),'{}',REPEAT('a',64),2,REPEAT('a',64),?)",
          ID,
          n,
          "fixture-" + n,
          n + 1);
      db.update(
          "INSERT INTO step_session_scope(tenant_id,step_id,caller_task_id,authority_digest,context_snapshot,context_digest,context_bytes) VALUES('tenant-a',?,?,REPEAT('a',64),'{}',REPEAT('a',64),2)",
          step,
          ID);
      db.update(
          "INSERT INTO invocation_session_read(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,target_seq,before_count,after_count) VALUES('tenant-a',?,?,?,REPEAT('a',64),REPEAT('a',64),0,0,0,0)",
          invocation,
          ID,
          step);
      db.update(
          "INSERT INTO invocation_session_search(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,normalized_query_snapshot,normalized_query_digest,normalized_query_bytes) VALUES('tenant-a',?,?,?,REPEAT('a',64),REPEAT('a',64),0,'字😀',REPEAT('a',64),7)",
          invocation,
          ID,
          step);
      db.update(
          "INSERT INTO invocation_session_trace(tenant_id,invocation_id,task_id,step_id,binding_digest,input_digest,upper_seq,target_seq,relation_policy) VALUES('tenant-a',?,?,?,REPEAT('a',64),REPEAT('a',64),0,0,'read-receipt-sources-v1')",
          invocation,
          ID,
          step);
      db.update(
          "INSERT INTO invocation_session_scope_query(tenant_id,invocation_id,caller_task_id,step_id,operation,binding_digest,input_digest,authority_digest,context_digest,normalized_snapshot,normalized_digest,normalized_bytes) VALUES('tenant-a',?,?,?,'SEARCH',REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),'{}',REPEAT('a',64),2)",
          invocation,
          ID,
          step);
      db.update(
          "INSERT INTO invocation_session_lineage_trace(tenant_id,invocation_id,caller_task_id,step_id,binding_digest,input_digest,authority_digest,graph_digest,context_digest,normalized_snapshot,normalized_digest,normalized_bytes) VALUES('tenant-a',?,?,?,REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),REPEAT('a',64),'{}',REPEAT('a',64),2)",
          invocation,
          ID,
          step);
      db.update(
          "INSERT INTO task_session_scope_target(tenant_id,caller_task_id,ordinal,target_digest,target_bytes,content_state) VALUES('tenant-a',?,?,REPEAT('a',64),2,'PURGED')",
          ID,
          n);
    }
  }

  private static void rowBoundaries(
      JdbcTemplate db, SessionQueryTransaction tx, org.mybatis.spring.SqlSessionTemplate mappers) {
    for (var table : List.of(Table.TASK_SESSION_SCOPE_TARGET, Table.SESSION_PUBLIC_EVENT)) {
      boolean targets = table == Table.TASK_SESSION_SCOPE_TARGET;
      String name = table.name().toLowerCase(java.util.Locale.ROOT);
      String key = targets ? "caller_task_id" : "task_id";
      String insert =
          targets
              ? "INSERT INTO task_session_scope_target(tenant_id,caller_task_id,ordinal,target_digest,target_bytes,content_state) VALUES('tenant-a',?,?,REPEAT('a',64),2,'PURGED')"
              : "INSERT INTO session_public_event(tenant_id,task_id,seq,event_version,fact_key,type,occurred_at,event_snapshot,event_digest,event_bytes,source_digest,reliable_event_seq) VALUES('tenant-a',?,?,5,?,'user.message',CURRENT_TIMESTAMP(6),'{}',REPEAT('a',64),2,REPEAT('a',64),1)";
      for (int count : targets ? List.of(0, 1, 7, 8, 9) : List.of(0, 1, 127, 128, 129)) {
        db.update("DELETE FROM `" + name + "` WHERE tenant_id='tenant-a' AND `" + key + "`=?", ID);
        int legal = Math.min(count, targets ? 8 : 128);
        var rows = new java.util.ArrayList<Object[]>();
        for (int n = 0; n < legal; n++)
          rows.add(targets ? new Object[] {ID, n} : new Object[] {ID, n, "boundary-" + n});
        if (!rows.isEmpty()) db.batchUpdate(insert, rows);
        if (count > legal) {
          var before = SessionDependencyScenario.snapshot(db);
          boolean refused = false;
          try {
            if (targets) db.update(insert, ID, legal);
            else db.update(insert, ID, legal, "boundary-" + legal);
          } catch (org.springframework.dao.DataAccessException expected) {
            // MySQL 3819/HY000在当前Spring映射为UncategorizedSQLException；仅认原CHECK拒绝。
            Throwable cause = expected.getMostSpecificCause();
            if (!(cause instanceof java.sql.SQLException sql) || sql.getErrorCode() != 3819)
              throw expected;
            refused = true;
          }
          check(
              refused && before.equals(SessionDependencyScenario.snapshot(db)),
              "original SQL CHECK rejects row " + count + " for " + table);
          continue;
        }
        long expected = SessionRepairSqlOracle.inspect(db, ID, List.of(name)).metadata();
        String statement =
            PREFIX + (targets ? "metadataTaskSessionScopeTarget" : "metadataSessionPublicEvent");
        // 独立验证原行数上限；unmetered仅用于结构读回，明确不算生产预算成功。
        var structural = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
        structural.restrictLineage();
        long actual =
            tx.required(
                structural,
                () -> {
                  try (var batch =
                      new SessionCursorMetadataBatch(
                          structural,
                          SessionMetadataProjection.forTable(table),
                          () ->
                              mappers.selectCursor(
                                  statement,
                                  Map.of(
                                      "query", structural, "tenant", "tenant-a", "taskId", ID)))) {
                    return scan(batch);
                  }
                });
        check(
            actual == expected && structural.scratchLive() == 65536,
            "real SQL structural row boundary " + table + " rows=" + count + " bytes=" + actual);
        var metered = query(0);
        var before = SessionDependencyScenario.snapshot(db);
        String outcome = "COMMITTED";
        try {
          tx.required(
              metered,
              () -> {
                try (var batch =
                    new SessionCursorMetadataBatch(
                        metered,
                        SessionMetadataProjection.forTable(table),
                        () ->
                            mappers.selectCursor(
                                statement,
                                Map.of("query", metered, "tenant", "tenant-a", "taskId", ID)))) {
                  return scan(batch);
                }
              });
        } catch (TaskFailure failure) {
          if (!failure.code().equals("SESSION_HISTORY_LIMIT")) throw failure;
          outcome = failure.code();
        }
        check(
            before.equals(SessionDependencyScenario.snapshot(db)) && metered.scratchLive() == 65536,
            "real SQL metered row boundary released " + table + " rows=" + count);
        System.out.println(
            "ROW_BOUNDARY "
                + table
                + " rows="
                + count
                + " outcome="
                + outcome
                + " resources="
                + metered.resources()
                + " rejection="
                + metered.operationRejection());
      }
    }
  }

  static void verify(JdbcTemplate db, String mode) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    seed(db);
    try {
      if (!mode.equals("borrowed-tail") && !mode.equals("borrowed-boundaries")) {
        int groups = 0;
        for (var table : Table.values()) {
          var p = SessionMetadataProjection.forTable(table);
          if (p == null) continue;
          groups++;
          String operation =
              "metadata"
                  + java.util.Arrays.stream(
                          table.name().toLowerCase(java.util.Locale.ROOT).split("_"))
                      .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                      .collect(java.util.stream.Collectors.joining());
          String key =
              table.name().contains("SCOPE") || table == Table.INVOCATION_SESSION_LINEAGE_TRACE
                  ? "caller_task_id"
                  : "task_id";
          var raw =
              db.queryForList(
                  "SELECT "
                      + String.join(",", p.columns.stream().map(c -> "`" + c.name() + "`").toList())
                      + " FROM `"
                      + table.name().toLowerCase(java.util.Locale.ROOT)
                      + "` WHERE tenant_id='tenant-a' AND `"
                      + key
                      + "`=?",
                  ID);
          long expected = 0;
          for (var row : raw) {
            expected += 32;
            for (Object value : row.values()) expected += 8 + bytes(value);
          }
          var q = query(0);
          long actual =
              tx.required(
                  q,
                  () -> {
                    var args =
                        Map.<String, Object>of("query", q, "tenant", "tenant-a", "taskId", ID);
                    try (var batch =
                        new SessionCursorMetadataBatch(
                            q, p, () -> mappers.selectCursor(PREFIX + operation, args))) {
                      return scan(batch);
                    }
                  });
          check(
              actual == expected && !raw.isEmpty(),
              "real nonempty projection " + table + " rows=" + raw.size() + " metadata=" + actual);
          check(q.scratchLive() == 65536, "real projection owner cleared " + table);
          long cost = q.resources().operations();
          for (int delta : List.of(-1, 0, 1)) {
            var bounded = query(65536 - cost + delta);
            var beforeBoundary = SessionDependencyScenario.snapshot(db);
            boolean refused = false;
            try {
              tx.required(
                  bounded,
                  () -> {
                    var args =
                        Map.<String, Object>of(
                            "query", bounded, "tenant", "tenant-a", "taskId", ID);
                    try (var batch =
                        new SessionCursorMetadataBatch(
                            bounded, p, () -> mappers.selectCursor(PREFIX + operation, args))) {
                      return scan(batch);
                    }
                  });
            } catch (TaskFailure limit) {
              refused = limit.code().equals("SESSION_HISTORY_LIMIT");
            }
            check(
                refused == (delta > 0)
                    && beforeBoundary.equals(SessionDependencyScenario.snapshot(db))
                    && bounded.scratchLive() == 65536,
                "real SQL cumulative operations boundary " + table + " delta=" + delta);
          }
          System.out.println(
              "BORROWED_SQL "
                  + Payloads.json(
                      Map.of(
                          "table",
                          table,
                          "rows",
                          raw.size(),
                          "metadata",
                          actual,
                          "operations",
                          q.resources().operations(),
                          "scratchPeak",
                          q.scratchPeak())));
        }
        check(groups == 15, "all 15 actual nonempty schemas");
      }
      if (mode.equals("borrowed-boundaries")) {
        rowBoundaries(db, tx, mappers);
        return;
      }
      if (mode.equals("borrowed-shapes")) return;
      // 按独立迁移列清单逐表算32/8/UTF8/NULL/UTC；单表事务不冒称联合预算或A12。
      for (var table : Table.values()) {
        long expected =
            SessionRepairSqlOracle.inspect(
                    db, ID, List.of(table.name().toLowerCase(java.util.Locale.ROOT)))
                .metadata();
        var q18 = query(0);
        long actual =
            tx.required(
                q18,
                () -> {
                  var records =
                      new MapperSessionMetadataAccounting(
                          new JdbcSessionRecords(db, q18, mappers),
                          Map.of(
                              "tenant_id",
                              "tenant-a",
                              "id",
                              ID,
                              "capabilities",
                              io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5
                                  .CAPABILITIES));
                  long total = 0;
                  try (var batch = records.batch(table)) {
                    for (var row : batch) {
                      total += 32;
                      for (var value : row.values()) total += 8 + batch.scalarBytes(value);
                    }
                  }
                  return total;
                });
        check(
            expected == actual && actual > 0 && q18.scratchLive() == 65536,
            "independent actual 18-table oracle " + table + " metadata=" + actual);
      }
      var acceptedQuery = query(0);
      tx.required(
          acceptedQuery,
          () -> {
            var sql =
                mappers.getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper
                        .class);
            var event = SessionAcceptedTaskRows.load(sql, acceptedQuery, "tenant-a", ID, true);
            var raw =
                db.queryForMap(
                    "SELECT * FROM task WHERE tenant_id='tenant-a' AND id=? FOR UPDATE", ID);
            check(
                event.size() == 21 && event.get("goal").equals(raw.get("goal")),
                "actual accepted21 retains original goal");
            for (String name : event.keySet()) {
              Object value = raw.get(name);
              if (value instanceof java.sql.Timestamp stamp) value = stamp.toLocalDateTime();
              check(
                  java.util.Objects.equals(event.get(name), value),
                  "actual accepted21 exact " + name);
            }
            db.update(
                "UPDATE task SET revision=revision+1,next_event_seq=next_event_seq+1 WHERE tenant_id='tenant-a' AND id=?",
                ID);
            var view = SessionAcceptedTaskRows.load(sql, acceptedQuery, "tenant-a", ID, false);
            check(
                view.size() == 20
                    && !event.get("revision").equals(view.get("revision"))
                    && !event.get("next_event_seq").equals(view.get("next_event_seq")),
                "actual accepted View rereads event progress");
            return null;
          });
      check(
          acceptedQuery.scratchLive() == 65536, "accepted driver/Map owners released after finish");
      // 新首错回滚点：实际SQL返回错误类型，catch后申请超限不得再执行/覆盖409。
      var c = mappers.getConfiguration();
      c.addMappedStatement(
          new org.apache.ibatis.mapping.MappedStatement.Builder(
                  c,
                  "private.borrowedCorrupt",
                  new org.apache.ibatis.scripting.defaults.RawSqlSource(
                      c,
                      "SELECT 7 AS tenant_id FROM task WHERE tenant_id='tenant-a' AND id='"
                          + ID
                          + "'",
                      Map.class),
                  org.apache.ibatis.mapping.SqlCommandType.SELECT)
              .resultMaps(
                  List.of(
                      new org.apache.ibatis.mapping.ResultMap.Builder(
                              c, "private.borrowedCorruptMap", Map.class, List.of())
                          .build()))
              .build());
      var before = SessionDependencyScenario.snapshot(db);
      var q = query(0);
      RuntimeException[] first = {null};
      var closeFault = new IllegalStateException("private actual cursor secondary close failure");
      try {
        tx.required(
            q,
            () -> {
              db.update(
                  "UPDATE task SET revision=revision+1 WHERE tenant_id='tenant-a' AND id=?", ID);
              try (var batch =
                  new SessionCursorMetadataBatch(
                      q,
                      SessionMetadataProjection.forTable(Table.TASK_ATTEMPT),
                      () -> {
                        org.apache.ibatis.cursor.Cursor<Map<String, Object>> actual =
                            mappers.selectCursor("private.borrowedCorrupt", Map.of("query", q));
                        @SuppressWarnings("unchecked")
                        var wrapped =
                            (org.apache.ibatis.cursor.Cursor<Map<String, Object>>)
                                java.lang.reflect.Proxy.newProxyInstance(
                                    actual.getClass().getClassLoader(),
                                    new Class<?>[] {org.apache.ibatis.cursor.Cursor.class},
                                    (proxy, method, args) -> {
                                      if (method.getName().equals("close")) {
                                        actual.close();
                                        throw closeFault;
                                      }
                                      try {
                                        return method.invoke(actual, args);
                                      } catch (
                                          java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                      }
                                    });
                        return wrapped;
                      })) {
                batch.iterator().next();
              } catch (RuntimeException failure) {
                first[0] = failure;
              }
              long operations = q.resources().operations();
              try {
                q.operation(65537);
              } catch (RuntimeException failure) {
                check(failure == first[0], "real caught corruption keeps identical first failure");
              }
              check(
                  q.resources().operations() == operations,
                  "later rejected work did not execute or increment");
              return null;
            });
        throw new AssertionError("Expected first failure");
      } catch (TaskFailure failure) {
        check(
            failure == first[0]
                && failure.status() == 409
                && failure.code().equals("SESSION_HISTORY_CORRUPT"),
            "real first 409 survives transaction finish");
        check(
            failure.getSuppressed().length == 1 && failure.getSuppressed()[0] == closeFault,
            "actual owner preserves secondary cursor close failure on original 409");
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)) && q.scratchLive() == 65536,
          "real first-failure 22-table rollback and release");
      SessionRepairSqlOracle.dump(db, ID, "borrowed-nonempty");
    } finally {
      for (var table :
          List.of(
              "invocation_session_lineage_trace",
              "invocation_session_scope_query",
              "step_session_scope",
              "task_session_scope_target",
              "task_session_scope",
              "invocation_session_trace",
              "invocation_session_search",
              "invocation_session_read",
              "session_public_event",
              "task_session_lineage",
              "task_session_history",
              "event",
              "reservation",
              "model_dispatch",
              "invocation",
              "step",
              "task_attempt",
              "task")) {
        String key =
            table.equals("task")
                ? "id"
                : table.contains("scope") || table.equals("invocation_session_lineage_trace")
                    ? "caller_task_id"
                    : "task_id";
        db.update("DELETE FROM `" + table + "` WHERE tenant_id='tenant-a' AND `" + key + "`=?", ID);
      }
    }
  }
}
