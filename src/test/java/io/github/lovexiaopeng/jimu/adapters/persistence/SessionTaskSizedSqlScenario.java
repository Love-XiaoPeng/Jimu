package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 私库投影夹具，不伪装成准入/权限业务；11列实际driver取值和新增getter失败22表回滚。 */
final class SessionTaskSizedSqlScenario {
  private static final List<String> LENGTHS =
      List.of(
          "capabilities",
          "question_binding",
          "question_retained_digests",
          "model_snapshot",
          "tools_snapshot",
          "evaluation_snapshot",
          "media_snapshot",
          "mcp_snapshot",
          "material_snapshot",
          "material_file_snapshot",
          "business_policy_snapshot");
  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.";

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static final class Observed extends AbstractDataSource {
    private final javax.sql.DataSource source;
    final Map<String, Long> gets = new java.util.TreeMap<>();
    final java.util.Set<String> types = new java.util.TreeSet<>();

    Observed(javax.sql.DataSource source) {
      this.source = source;
    }

    public Connection getConnection() throws java.sql.SQLException {
      return connection(source.getConnection());
    }

    public Connection getConnection(String user, String password) throws java.sql.SQLException {
      return connection(source.getConnection(user, password));
    }

    private Connection connection(Connection original) {
      return proxy(
          Connection.class,
          (method, args) -> {
            Object result = call(original, method, args);
            if (method.getName().equals("prepareStatement")
                && ((String) args[0]).contains("OCTET_LENGTH")) {
              var statement = (PreparedStatement) result;
              return proxy(
                  PreparedStatement.class,
                  (m, a) -> {
                    Object v = call(statement, m, a);
                    if (v instanceof ResultSet rows)
                      return proxy(
                          ResultSet.class,
                          (rm, ra) -> {
                            Object scalar = call(rows, rm, ra);
                            if (rm.getName().equals("getObject")) {
                              String label =
                                  ra[0] instanceof Integer i
                                      ? rows.getMetaData().getColumnLabel(i)
                                      : (String) ra[0];
                              if (LENGTHS.contains(label)) {
                                checkLength(scalar);
                                gets.merge(label, 1L, Long::sum);
                                types.add(
                                    scalar == null ? "NULL" : scalar.getClass().getSimpleName());
                              }
                            }
                            return scalar;
                          });
                    return v;
                  });
            }
            return result;
          });
    }
  }

  private interface Invoke {
    Object call(java.lang.reflect.Method m, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Invoke h) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> h.call(m, a)));
  }

  private static Object call(Object target, java.lang.reflect.Method m, Object[] args)
      throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void checkLength(Object value) {
    if (value != null && !(value instanceof Long || value instanceof Integer))
      throw new AssertionError("Length materialized original text: " + value.getClass());
  }

  private static long scan(SessionCursorMetadataBatch batch) {
    long total = 0;
    for (var row : batch) {
      total += 32;
      for (var v : row.values()) total += 8 + batch.scalarBytes(v);
    }
    return total;
  }

  static void verify(JdbcTemplate independent) {
    String id = java.util.UUID.randomUUID().toString();
    independent.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice',?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','','','session_event_read,session_search,session_trace',1,'QUEUED',TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)),1,1)",
        id,
        "sized-" + id);
    var source = new Observed(independent.getDataSource());
    var db = new JdbcTemplate(source);
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    int cases = 0;
    try {
      for (String column : LENGTHS)
        for (String raw : new String[] {null, "", "ASCII", "\0", "中文", "😀", "tail  "}) {
          if (column.equals("capabilities") && raw == null) continue; // 实际NOT NULL契约。
          if (column.equals("media_snapshot")) {
            // V13要求record3、JSON_VALID和digest；空/NUL原文不合法，合法JSON串仍保留对应内容。
            if ("".equals(raw)) {
              boolean rejected = false;
              try {
                independent.update(
                    "UPDATE task SET record_version=3,media_snapshot='',media_digest=? WHERE tenant_id='tenant-a' AND id=?",
                    "a".repeat(64),
                    id);
              } catch (org.springframework.dao.DataAccessException expected) {
                rejected =
                    expected.getRootCause() instanceof java.sql.SQLException sql
                        && sql.getErrorCode() == 3819
                        && sql.getMessage().contains("task_media_pair");
              }
              check(rejected, "original media empty-text CHECK rejects unchanged");
            }
            independent.update(
                "UPDATE task SET record_version=3,media_snapshot=?,media_digest=? WHERE tenant_id='tenant-a' AND id=?",
                raw == null ? null : Payloads.json(raw),
                raw == null ? null : "a".repeat(64),
                id);
          } else if (column.equals("mcp_snapshot")) {
            independent.update(
                "UPDATE task SET mcp_snapshot=?,mcp_digest=? WHERE tenant_id='tenant-a' AND id=?",
                raw,
                raw == null ? null : "a".repeat(64),
                id);
          } else if (column.equals("business_policy_snapshot")) {
            // V10只接受合法JSON；测内容的JSON编码，不放宽原CHECK以伪造非法字符串。
            independent.update(
                "UPDATE task SET business_policy_snapshot=? WHERE tenant_id='tenant-a' AND id=?",
                raw == null ? null : Payloads.json(raw),
                id);
          } else
            independent.update(
                "UPDATE task SET `" + column + "`=? WHERE tenant_id='tenant-a' AND id=?", raw, id);
          var q = query();
          tx.required(
              q,
              () -> {
                Object rawOwner = new Object();
                q.retainScratch(rawOwner, 131072);
                try {
                  var args = Map.<String, Object>of("query", q, "tenant", "tenant-a", "taskId", id);
                  // 同一连接先锁行，原60列仍经真实ResultSet计量；小样例的原串workspace先预留。
                  db.queryForList(
                      "SELECT id FROM task WHERE tenant_id='tenant-a' AND id=? FOR UPDATE", id);
                  List<Map<String, Object>> original =
                      mappers.selectList(PREFIX + "metadataTask", args);
                  long expected = 32;
                  for (Object value : original.getFirst().values())
                    expected += 8 + SessionQueryContext.scalarBytes(value);
                  try (var batch =
                      new SessionCursorMetadataBatch(
                          q,
                          SessionMetadataProjection.taskSized(),
                          () -> mappers.selectCursor(PREFIX + "metadataTaskSized", args))) {
                    check(
                        expected == scan(batch),
                        "SQL sized exact " + column + " " + (raw == null ? "NULL" : raw.length()));
                  }
                } finally {
                  q.releaseScratch(rawOwner);
                }
                return null;
              });
          check(q.scratchLive() == 65536, "SQL sized releases owner");
          cases++;
        }
      var viewQuery = query();
      tx.required(
          viewQuery,
          () -> {
            var before =
                db.queryForMap(
                    "SELECT * FROM task WHERE tenant_id='tenant-a' AND id=? FOR UPDATE", id);
            db.update(
                "UPDATE task SET revision=revision+1,next_event_seq=next_event_seq+1,summary='after-event 中文 😀' WHERE tenant_id='tenant-a' AND id=?",
                id);
            var projected =
                mappers
                    .getMapper(
                        io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper
                            .TaskAdmissionMapper.class)
                    .lockAcceptedView(viewQuery, "tenant-a", id)
                    .getFirst();
            var after =
                db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
            for (String column : projected.keySet())
              check(
                  java.util.Objects.equals(projected.get(column), after.get(column)),
                  "accepted View rereads exact " + column);
            check(
                projected.size() == 20 && projected.containsKey("cancel_requested_at"),
                "accepted View preserves NULL columns");
            check(
                !projected.get("revision").equals(before.get("revision"))
                    && !projected.get("next_event_seq").equals(before.get("next_event_seq")),
                "accepted View observes event progress");
            check(
                projected.get("tools_snapshot").equals(after.get("tools_snapshot"))
                    && projected.get("mcp_snapshot").equals(after.get("mcp_snapshot")),
                "accepted View preserves actual tools and unexpected MCP raw");
            return null;
          });
      // 单个LONGTEXT超过metadata容量仍从真实列计算，不截尾，不提前物化大文本。
      independent.update(
          "UPDATE task SET material_snapshot=REPEAT('x',65537) WHERE tenant_id='tenant-a' AND id=?",
          id);
      var q = query();
      try {
        tx.required(
            q,
            () -> {
              var records = new JdbcSessionRecords(db, q);
              return records.metadata(
                  Map.of(
                      "tenant_id",
                      "tenant-a",
                      "id",
                      id,
                      "capabilities",
                      io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5
                          .CAPABILITIES),
                  false);
            });
        throw new AssertionError("Expected metadata capacity refusal");
      } catch (TaskFailure limit) {
        check(
            limit.code().equals("SESSION_HISTORY_LIMIT"),
            "SQL large LONGTEXT preserves metadata capacity");
      }
      independent.update(
          "UPDATE task SET material_snapshot=NULL WHERE tenant_id='tenant-a' AND id=?", id);
      for (String entry : List.of("value", "hasNext", "next", "scalar", "beforeCommit")) {
        var before = SessionDependencyScenario.snapshot(independent);
        var bounded = query();
        boolean refused = false;
        try {
          tx.required(
              bounded,
              () -> {
                db.update(
                    "UPDATE task SET revision=revision+1 WHERE tenant_id='tenant-a' AND id=?", id);
                var args =
                    Map.<String, Object>of("query", bounded, "tenant", "tenant-a", "taskId", id);
                try (var batch =
                    new SessionCursorMetadataBatch(
                        bounded,
                        SessionMetadataProjection.taskSized(),
                        () -> mappers.selectCursor(PREFIX + "metadataTaskSized", args))) {
                  var row = batch.iterator().next();
                  var values = row.values().iterator();
                  if (entry.equals("beforeCommit")) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                            new org.springframework.transaction.support
                                .TransactionSynchronization() {
                              public int getOrder() {
                                return -1;
                              }

                              public void beforeCommit(boolean readOnly) {
                                try {
                                  bounded.operation(65537);
                                } catch (TaskFailure caught) {
                                  /* sticky拒绝必须仍使真实提交失败。 */
                                }
                              }
                            });
                  } else {
                    bounded.operation(65536 - bounded.resources().operations());
                    try {
                      switch (entry) {
                        case "value" -> row.value("tenant_id");
                        case "hasNext" -> values.hasNext();
                        case "next" -> values.next();
                        case "scalar" -> batch.scalarBytes("x");
                        default -> throw new AssertionError();
                      }
                    } catch (TaskFailure caught) {
                      check(
                          caught.code().equals("SESSION_HISTORY_LIMIT"),
                          "caught real SQL getter " + entry);
                    }
                  }
                }
                return null;
              });
        } catch (TaskFailure limit) {
          refused = limit.code().equals("SESSION_HISTORY_LIMIT");
        }
        check(
            refused && before.equals(SessionDependencyScenario.snapshot(independent)),
            "SQL 22-table rollback caught " + entry);
        check(bounded.scratchLive() == 65536, "SQL caught owner released " + entry);
      }
      check(
          source.gets.keySet().containsAll(LENGTHS),
          "all eleven actual getObject columns are bounded lengths");
      System.out.println(
          "TASK_SIZED_SQL "
              + Payloads.json(
                  Map.of("cases", cases, "getObject", source.gets, "driverTypes", source.types)));
      System.out.flush();
    } finally {
      independent.update("DELETE FROM task WHERE tenant_id='tenant-a' AND id=?", id);
    }
  }
}
