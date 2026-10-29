package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionEventHistoryRows;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionJdbcTemplate;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 私库合成行只验证SQL计量/锁/回滚；不冒充合法submit、当前授权或业务终态。 */
final class SessionEventHistoryLengthSqlScenario {
  private static final String RAW =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.eventHistory";

  private static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
    System.out.println("PASS " + what);
    System.out.flush();
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static void lock(JdbcTemplate independent, SessionQueryContext q, String id) {
    var db = new SessionJdbcTemplate(independent.getDataSource(), q);
    check(
        db.queryForList("SELECT id FROM task WHERE tenant_id='tenant-a' AND id=? FOR UPDATE", id)
                .size()
            == 1,
        "actual task lock");
    SessionQueryTransaction.current(q).locked("tenant-a", id);
  }

  static void verify(JdbcTemplate db) {
    String id = java.util.UUID.randomUUID().toString();
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice',?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','','','session_event_read,session_search,session_trace',1,'QUEUED',TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)),1,1)",
        id,
        "length-" + id);
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,5,'abc',?,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)))",
        id,
        Payloads.hash("abc"));
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var sql = LedgerMapperSessions.create(source);
    try {
      for (String value :
          new String[] {"", "abc", "中😀", "x\u0000尾  ", "x".repeat(2174), "中".repeat(1000)}) {
        db.update(
            "UPDATE task_session_history SET binding_snapshot=? WHERE tenant_id='tenant-a' AND task_id=?",
            value,
            id);
        var old = query();
        long[] oldRaw = {0};
        tx.required(
            old,
            () -> {
              lock(db, old, id);
              long b = old.resources().operations();
              List<Map<String, Object>> rows =
                  sql.selectList(RAW, Map.of("query", old, "tenant", "tenant-a", "taskId", id));
              check(value.equals(rows.getFirst().get("binding_snapshot")), "ordinary raw exact");
              oldRaw[0] = old.resources().operations() - b;
              return null;
            });
        var q = query();
        long[] sized = {0};
        tx.required(
            q,
            () -> {
              lock(db, q, id);
              long b = q.resources().operations();
              var rows = SessionEventHistoryRows.load(sql, q, "tenant-a", id);
              check(
                  value.equals(rows.getFirst().get("binding_snapshot")),
                  "SQL length raw exact UTF8="
                      + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
              check(rows.getFirst().size() == 8, "original eight raw columns");
              sized[0] = q.resources().operations() - b;
              check(q.resources().reads() == 3, "one lock plus size plus raw actual ResultSets");
              return null;
            });
        check(q.scratchLive() == 65536, "SQL length owner released");
        System.out.println(
            "SQL_LENGTH_DELTA utf16="
                + value.length()
                + " ordinary="
                + oldRaw[0]
                + " sized="
                + sized[0]
                + " oldProof="
                + old.resources().bytes()
                + " sizedProof="
                + q.resources().bytes()
                + " sizedPeak="
                + q.scratchPeak());
      }
      boolean nullRejected = false;
      try {
        db.update(
            "UPDATE task_session_history SET binding_snapshot=NULL WHERE tenant_id='tenant-a' AND task_id=?",
            id);
      } catch (org.springframework.dao.DataAccessException expected) {
        nullRejected =
            expected.getRootCause() instanceof java.sql.SQLException s && s.getErrorCode() == 1048;
      }
      check(
          nullRejected,
          "original NOT NULL rejects SQL NULL; provider NULL capability tested separately");
      var legacy = query();
      tx.required(
          legacy,
          () -> {
            lock(db, legacy, id);
            try (var ignored = legacy.meter(() -> (label, value) -> {})) {
              SessionEventHistoryRows.load(sql, legacy, "tenant-a", id);
              check(
                  legacy.resources().reads() == 2, "additional meter uses only original raw query");
            }
            return null;
          });
      var disabled = query();
      disabled.disableSqlUtf8Lengths();
      tx.required(
          disabled,
          () -> {
            lock(db, disabled, id);
            SessionEventHistoryRows.load(sql, disabled, "tenant-a", id);
            check(
                disabled.resources().reads() == 2,
                "flags-off preserves original raw and full independent metering");
            return null;
          });
      // 完整原Service仍独立检查raw/version/digest/retained，失败catch后finish必须原首错。
      String binding =
          new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts(5).binding();
      db.update(
          "UPDATE task_session_history SET binding_snapshot=?,binding_digest=? WHERE tenant_id='tenant-a' AND task_id=?",
          binding,
          Payloads.hash(binding),
          id);
      for (String mutation :
          List.of("history_version=4", "binding_snapshot='{}'", "binding_digest=REPEAT('0',64)")) {
        var before = SessionDependencyScenario.snapshot(db);
        var q = query();
        RuntimeException[] first = {null};
        try {
          tx.required(
              q,
              () -> {
                lock(db, q, id);
                db.update(
                    "UPDATE task SET goal='private rollback marker' WHERE tenant_id='tenant-a' AND id=?",
                    id);
                db.update(
                    "UPDATE task_session_history SET "
                        + mutation
                        + " WHERE tenant_id='tenant-a' AND task_id=?",
                    id);
                SessionQueryTransaction.current(q).mutation(); // 仅本私库注入，显式标记直接JDBC写。
                var records = new JdbcSessionRecords(new SessionJdbcTemplate(source, q), q, sql);
                var task =
                    Map.<String, Object>of(
                        "tenant_id",
                        "tenant-a",
                        "id",
                        id,
                        "capabilities",
                        SessionLineagePolicyV5.CAPABILITIES);
                try {
                  records.acceptedEventHistory(task, false);
                  throw new AssertionError("corruption accepted");
                } catch (RuntimeException failure) {
                  first[0] = failure;
                  return null;
                }
              });
          throw new AssertionError("caught corruption committed");
        } catch (RuntimeException failure) {
          check(
              first[0] != null && failure == first[0],
              "original Service first error through finish " + mutation);
        }
        check(
            before.equals(SessionDependencyScenario.snapshot(db)),
            "all22 tables rollback " + mutation);
        check(q.scratchLive() == 65536, "corrupt raw references released");
      }
      db.update("DELETE FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", id);
      var missing = query();
      tx.required(
          missing,
          () -> {
            lock(db, missing, id);
            check(
                SessionEventHistoryRows.load(sql, missing, "tenant-a", id).isEmpty(),
                "missing size keeps original independent missing query");
            check(missing.resources().reads() == 3, "both empty ResultSets counted");
            return null;
          });
      check(missing.scratchLive() == 65536, "empty length released");
      System.out.println(
          "SQL_LENGTH_ONLY_COMPLETE; synthetic persistence fixture, no business submit/authority/ACK claim");
    } finally {
      db.update("DELETE FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", id);
      db.update("DELETE FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    }
  }
}
