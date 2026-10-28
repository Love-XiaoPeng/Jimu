package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 私库实际MP写/owner回滚；typed scope为显式fixture，不代替当前资格、业务或恢复验收。 */
public final class SessionDependencyScenario {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static String id(int index) {
    return "00000000-0000-0000-0000-%012d".formatted(index);
  }

  static SessionScopeRecords.Caller caller(String id) {
    return new SessionScopeRecords.Caller(
        "tenant-a",
        id,
        "alice",
        "session_trace",
        "b".repeat(64),
        1,
        "SUCCEEDED",
        null,
        "a".repeat(64));
  }

  static SessionScopeRecords.SourceFact source(int index) {
    var scope =
        new SessionScopeRecords.Scope(
            1, 1, "alice", "binding", "tools", "{}", "digest", 2, 0, "ACTIVE", null, null, null,
            null, null, "PRESENT", true);
    var authority =
        new SessionScopeRecords.Authority(
            "tenant-a", id(index), "alice", "tools", "binding", "", "", List.of());
    return new SessionScopeRecords.SourceFact(
        caller(id(index)),
        new SessionScopeRecords.History(0, "binding", null),
        scope,
        authority,
        List.of());
  }

  private static Map<String, Object> bind(SessionQueryContext query, String task) {
    var args = new java.util.HashMap<String, Object>();
    args.put("query", query);
    args.put("tenant", "tenant-a");
    args.put("task", task);
    for (String field : List.of("modelSnapshot", "toolsSnapshot")) args.put(field, "{}");
    for (String field : List.of("modelDigest", "toolsDigest", "scope"))
      args.put(field, "c".repeat(64));
    args.put("run", null);
    args.put("cost", 0L);
    return args;
  }

  static List<Object> snapshot(JdbcTemplate db) {
    var result = new ArrayList<Object>();
    for (String table :
        List.of(
            "task",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "task_session_history",
            "task_session_scope",
            "task_session_scope_target",
            "task_session_lineage",
            "step_session_scope",
            "invocation_session_scope_query",
            "invocation_session_lineage_trace",
            "reservation",
            "event",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "tenant_budget",
            "actor_access",
            "installation",
            "worker_process")) {
      var primary =
          db.queryForList(
              "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",
              String.class,
              table);
      if (primary.isEmpty()) throw new AssertionError("Missing primary key " + table);
      result.add(
          db.queryForList(
              "SELECT * FROM `"
                  + table
                  + "` ORDER BY "
                  + primary.stream()
                      .map(name -> "`" + name + "`")
                      .collect(java.util.stream.Collectors.joining(","))));
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    var independent = new JdbcTemplate(source);
    for (int i = 1; i <= 9; i++)
      independent.update(
          "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice',?,REPEAT('a',64),'{}','dependency-fixture','session_trace',1,'SUCCEEDED',TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP(6)),2,3)",
          id(i),
          "dependency-" + i);
    var mappers = LedgerMapperSessions.create(source);
    var configuration = mappers.getConfiguration();
    configuration.addMappedStatement(
        new org.apache.ibatis.mapping.MappedStatement.Builder(
                configuration,
                "private.unknownDependency",
                new org.apache.ibatis.scripting.defaults.RawSqlSource(
                    configuration,
                    "UPDATE task SET tools_digest=REPEAT('d',64) WHERE tenant_id='tenant-a' AND id='"
                        + id(1)
                        + "'",
                    Map.class),
                org.apache.ibatis.mapping.SqlCommandType.UPDATE)
            .build());
    var transactions =
        new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    String statement =
        "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper.bindSnapshots";
    int checks = 0;
    for (String mode :
        List.of(
            "staging-source",
            "staging-unknown",
            "published-caught",
            "published-uncaught",
            "dml-caught",
            "before-commit")) {
      var before = snapshot(independent);
      var query =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      query.restrictLineage();
      var marker = new IllegalStateException("intentional private rollback");
      boolean rejected = false;
      try {
        transactions.required(
            query,
            () -> {
              var owner = SessionQueryTransaction.current(query);
              owner.admission();
              for (int i = 1; i <= 8; i++) {
                var fact = source(i);
                owner.stageSource(caller(id(9)), fact);
                owner.validatedAuthority(fact.caller(), fact.scope(), fact.authority());
              }
              if (mode.startsWith("published")) {
                owner.locked("tenant-a", id(9));
                owner.locked("tenant-a", id(1));
                var fact = source(9);
                var authority =
                    new SessionScopeRecords.Authority(
                        "tenant-a",
                        id(9),
                        "alice",
                        "tools",
                        "binding",
                        "",
                        "",
                        List.of(
                            new SessionScopeRecords.AuthorityTarget(
                                id(1), "pins", "binding", 0, "", "", "{}")));
                owner.scope(
                    caller(id(9)),
                    new SessionScopeRecords.Validated(
                        fact.scope(), authority, List.of(caller(id(1)))),
                    () -> {});
                if (mode.equals("published-caught")) {
                  try {
                    mappers.update(statement, bind(query, id(1)));
                  } catch (RuntimeException failure) {
                    check(
                        failure.getMessage().contains("dependency"),
                        "published dependency refused before SQL");
                  }
                  return null;
                }
                mappers.update(statement, bind(query, id(1)));
              } else if (mode.equals("dml-caught")) {
                var insert = bind(query, id(1));
                insert.put("actor", "alice");
                insert.put("key", "duplicate");
                insert.put("digest", "a".repeat(64));
                insert.put("snapshot", "{}");
                insert.put("goal", "duplicate");
                insert.put("capabilities", "session_trace");
                insert.put("epoch", 1);
                insert.put("deadline", 180);
                insert.put("models", 2);
                insert.put("tools", 3);
                try {
                  mappers.insert(statement.replace("bindSnapshots", "insertAccepted"), insert);
                } catch (RuntimeException expected) {
                  System.out.println("PASS actual duplicate DML caught by business");
                }
                return null;
              } else if (mode.equals("before-commit")) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                          public void beforeCommit(boolean readOnly) {
                            mappers.update(statement, bind(query, id(1)));
                          }
                        });
                return null;
              } else {
                if (mode.equals("staging-unknown"))
                  mappers.update("private.unknownDependency", Map.of("query", query));
                else mappers.update(statement, bind(query, id(1)));
                check(
                    owner.stagedSource(caller(id(9)), id(1)) == null,
                    "staged source invalidated " + mode);
                check(
                    owner.validatedAuthority("tenant-a", id(1), "{}", "digest") == null,
                    "staged authority invalidated " + mode);
                throw marker;
              }
              return null;
            });
      } catch (RuntimeException expected) {
        rejected = true;
      }
      check(
          rejected && before.equals(snapshot(independent)),
          "complete independent table snapshot rolled back " + mode);
      check(query.scratchLive() == 65536, "owner resources released " + mode);
      checks++;
    }
    // 真实新caller INSERT的完整键不与8来源相交，finish/beforeCommit正常提交。
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    transactions.required(
        query,
        () -> {
          var owner = SessionQueryTransaction.current(query);
          owner.admission();
          for (int i = 1; i <= 8; i++) owner.stageSource(caller(id(9)), source(i));
          var insert = bind(query, id(10));
          insert.put("actor", "alice");
          insert.put("key", "new-caller");
          insert.put("digest", "a".repeat(64));
          insert.put("snapshot", "{}");
          insert.put("goal", "new-caller");
          insert.put("capabilities", "session_trace");
          insert.put("epoch", 1);
          insert.put("deadline", 180);
          insert.put("models", 2);
          insert.put("tools", 3);
          mappers.insert(statement.replace("bindSnapshots", "insertAccepted"), insert);
          for (int i = 1; i <= 8; i++)
            check(
                owner.stagedSource(caller(id(9)), id(i)) != null,
                "new caller preserves source " + i);
          return null;
        });
    check(
        independent.queryForObject(
                "SELECT COUNT(*) FROM task WHERE tenant_id='tenant-a' AND id=?",
                Integer.class,
                id(10))
            == 1,
        "new caller INSERT committed");
    System.out.println(
        "DEPENDENCY_RESULT "
            + (checks + 1)
            + " modes; synthetic typed facts; actual Mapper SQL and independent DB snapshots");
  }
}
