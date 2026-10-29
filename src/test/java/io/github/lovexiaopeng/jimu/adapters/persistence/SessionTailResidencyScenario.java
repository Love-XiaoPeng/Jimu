package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity.InvocationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageTraceEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionPreparedScopeEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionScopeBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 私库具名Mapper纵切；authority/context/raw均合成，不宣称实际授权、模型响应或A03成功。 */
public final class SessionTailResidencyScenario {
  @org.apache.ibatis.plugin.Intercepts(
      @org.apache.ibatis.plugin.Signature(
          type = org.apache.ibatis.executor.Executor.class,
          method = "update",
          args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
  public static final class RetentionTail implements org.apache.ibatis.plugin.Interceptor {
    boolean armed, reached;

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      Object result = invocation.proceed();
      var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
      if (armed && statement.getId().endsWith("SessionHistoryRetentionMapper.releaseBudget")) {
        org.springframework.transaction.support.TransactionSynchronizationManager
            .registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                  public void beforeCommit(boolean readOnly) {
                    reached = true;
                    throw new IllegalStateException("root maintenance beforeCommit fixture");
                  }
                });
      }
      return result;
    }
  }

  private static final String ROOT = "io.github.lovexiaopeng.jimu.adapters.persistence.";
  private static final String TENANT = "tenant-a", DIGEST = "a".repeat(64);
  private static JdbcTemplate db;
  private static SqlSessionTemplate sql;
  private static SessionQueryTransaction transactions;
  private static JdbcLedger root;

  private static String id(int n) {
    return "20000000-0000-0000-0000-%012d".formatted(n);
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  private static Map<String, Object> args(SessionQueryContext q, Object... pairs) {
    var result = new HashMap<String, Object>();
    result.put("query", q);
    result.put("tenant", TENANT);
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  private static int write(String mapper, Map<String, Object> args) {
    return sql.update(ROOT + mapper, args);
  }

  private static void insert(String mapper, Object entity, SessionQueryContext q) {
    var args = args(q);
    var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
    for (String getter : meta.getGetterNames()) args.put(getter, meta.getValue(getter));
    check(sql.insert(ROOT + "session.mapper." + mapper + ".insert", args) == 1, mapper + " INSERT");
  }

  private static void publish(SessionQueryContext q) {
    var owner = SessionQueryTransaction.current(q);
    owner.leaseTask(TENANT, id(1));
    owner.bind("fixture-lease", id(3), null);
    owner.locked(TENANT, id(1));
    var scope =
        new SessionScopeRecords.Scope(
            1, 1, "alice", DIGEST, DIGEST, "{}", DIGEST, 2, 0, "ACTIVE", null, null, null, null,
            null, "PRESENT", true);
    var caller =
        new SessionScopeRecords.Caller(
            TENANT, id(1), "alice", "tools", "tools", 1, "RUNNING", null, DIGEST);
    var authority =
        new SessionScopeRecords.Authority(
            TENANT, id(1), "alice", "tools", DIGEST, "", "", List.of());
    owner.scope(caller, new SessionScopeRecords.Validated(scope, authority, List.of()), () -> {});
  }

  private static InvocationEntity invocation(int n) {
    var row = new InvocationEntity();
    row.setTenantId(TENANT);
    row.setInvocationId(id(n));
    row.setTaskId(id(1));
    row.setStepId(id(3));
    row.setAttemptId(id(2));
    row.setOrdinal(n);
    row.setSlot(n + ":fixture");
    row.setProviderId("provider-" + n);
    row.setEffectIntentId(id(n + 100));
    row.setTool("session_trace");
    row.setPluginVersion("0.1.0");
    row.setPluginDigest(DIGEST);
    row.setInstallationEpoch(1L);
    row.setInputSnapshot("{}");
    row.setInputDigest(Payloads.hash("{}"));
    row.setState("PENDING");
    return row;
  }

  private static void seed() {
    db.update(
        "INSERT INTO tenant_budget(tenant_id,history_storage_limit,history_storage_held,queued,running) VALUES('tenant-a',100663296,8388608,1,1)");
    db.update("INSERT INTO actor_access(tenant_id,actor_id) VALUES('tenant-a','alice')");
    db.update(
        "INSERT INTO worker_process(id,host_id,pid,start_ticks) VALUES(?,?,123,1)", id(99), id(98));
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools,current_attempt_id,worker_id,fence,lease_until) VALUES('tenant-a',?,'alice','tail',?,'{}','尾部fixture',?,1,'RUNNING',TIMESTAMPADD(SECOND,150,CURRENT_TIMESTAMP(6)),2,8,?,?,1,TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)))",
        id(1),
        DIGEST,
        SessionLineagePolicyV5.CAPABILITIES,
        id(2),
        id(99));
    db.update(
        "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES('tenant-a',?,?,?,1,'ACTIVE')",
        id(2),
        id(1),
        id(99));
    String binding = new SessionContracts(5).binding();
    db.update(
        "INSERT INTO task_session_history(tenant_id,task_id,history_version,binding_snapshot,binding_digest,retain_until) VALUES('tenant-a',?,5,?,?,TIMESTAMPADD(SECOND,604800,CURRENT_TIMESTAMP(6)))",
        id(1),
        binding,
        Payloads.hash(binding));
    db.update(
        "INSERT INTO task_session_lineage(tenant_id,task_id,genesis_snapshot,genesis_digest,genesis_bytes,graph_snapshot,graph_digest,graph_bytes) VALUES('tenant-a',?,'{}',?,2,'{}',?,2)",
        id(1),
        DIGEST,
        DIGEST);
    db.update(
        "INSERT INTO task_session_scope(tenant_id,caller_task_id,owner_actor_id,caller_binding_digest,caller_tools_digest,authority_snapshot,authority_digest,authority_bytes,candidate_count,created_at,retain_until,next_validation_at) SELECT tenant_id,id,'alice',?,?,'{}',?,2,0,created_at,TIMESTAMPADD(SECOND,604800,created_at),CURRENT_TIMESTAMP(6) FROM task WHERE id=?",
        DIGEST,
        DIGEST,
        DIGEST,
        id(1));
    for (String category : List.of("SESSION_HISTORY_STORAGE", "QUEUE", "RUNNING")) {
      String suffix =
          category.equals("SESSION_HISTORY_STORAGE")
              ? ":session-history"
              : category.equals("QUEUE") ? ":queue" : ":run";
      db.update(
          "INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) VALUES('tenant-a',?,?,?,?,'RESERVED','KNOWN')",
          id(1) + suffix,
          id(1),
          category,
          category.equals("SESSION_HISTORY_STORAGE") ? 8388608 : 1);
    }
  }

  private static void tail() {
    var q = query();
    transactions.required(
        q,
        () -> {
          publish(q);
          var core = CoreMappers.from(sql);
          check(
              core.steps()
                      .insertPreparedStep(
                          q, TENANT, id(3), id(1), id(2), 1, "{}", DIGEST, "fixture")
                  == 1,
              "prepare step");
          check(
              sql.getMapper(
                          io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                              .ScopeQueryStepReadMapper.class)
                      .bindPrepared(q, TENANT, id(1), id(3), 0, DIGEST, DIGEST)
                  == 1,
              "prepare finite context binding");
          insert(
              "SessionPreparedScopeMapper",
              new SessionPreparedScopeEntity(TENANT, id(3), id(1), DIGEST, "{}", DIGEST, 2),
              q);
          for (int n : List.of(10, 11, 12, 13)) {
            check(core.insertInvocation(q, invocation(n)) == 1, "actual invocation INSERT " + n);
            check(
                core.reservations()
                        .insertReservation(
                            q, TENANT, id(n), id(1), null, "TOOL", 1, "SETTLED", "KNOWN")
                    == 1,
                "TOOL reservation " + n);
            if (n < 13)
              insert(
                  "SessionLineageTraceMapper",
                  new SessionLineageTraceEntity(
                      TENANT,
                      id(n),
                      id(1),
                      id(3),
                      DIGEST,
                      Payloads.hash("{}"),
                      DIGEST,
                      DIGEST,
                      DIGEST,
                      "{}",
                      Payloads.hash("{}"),
                      2L),
                  q);
            else
              insert(
                  "SessionScopeBindingMapper",
                  new SessionScopeBindingEntity(
                      TENANT,
                      id(n),
                      id(1),
                      id(3),
                      "SEARCH",
                      DIGEST,
                      Payloads.hash("{}"),
                      DIGEST,
                      DIGEST,
                      "{}",
                      Payloads.hash("{}"),
                      2L),
                  q);
            write("session.mapper.SessionScopeQueryMapper.settle", args(q, "invocation", id(n)));
            write("session.mapper.SessionScopeQueryMapper.consumeTask", args(q, "task", id(1)));
            write("session.mapper.SessionScopeQueryMapper.consumeTenant", args(q));
            write(
                "session.mapper.SessionScopeQueryMapper.finishInvocation",
                args(
                    q,
                    "invocation",
                    id(n),
                    "outcome",
                    "SUCCEEDED",
                    "output",
                    "{}",
                    "digest",
                    DIGEST));
            core.events()
                .insertFact(
                    q,
                    TENANT,
                    id(1),
                    n,
                    id(1) + ":" + n,
                    "slot-" + n,
                    "tool.completed",
                    null,
                    "RUNNING",
                    null);
            var commit = new HashMap<String, Object>();
            commit.put("outcome", "SUCCEEDED");
            commit.put("code", null);
            commit.put("scan", DIGEST);
            commit.put("outputDigest", DIGEST);
            commit.put("outputBytes", 2);
            commit.put("receipt", n);
            commit.put("lineage", Map.of("nodes", 1, "edges", 0, "reads", 1, "bytes", 2));
            commit.put("rows", 0);
            commit.put("eventBytes", 0);
            commit.put("refs", null);
            commit.put("refsDigest", null);
            commit.put("refsBytes", null);
            commit.put("nativeSeq", null);
            check(
                write(
                        n < 13
                            ? "session.mapper.SessionLineageTraceMapper.commit"
                            : "session.mapper.SessionScopeQueryMapper.commitQuery",
                        args(
                            q,
                            "id",
                            id(n),
                            "invocation",
                            id(n),
                            "commit",
                            commit,
                            "attempt",
                            id(2),
                            "fence",
                            1L))
                    == 1,
                "same response slot receipt and commit " + n);
          }
          insert(
              "SessionPublicEventAppendMapper",
              new io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
                  .SessionPublicEventAppendEntity(
                  TENANT,
                  id(1),
                  5,
                  0,
                  "synthetic-public",
                  "session.read",
                  java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                  "{}",
                  Payloads.hash("{}"),
                  2,
                  DIGEST,
                  id(3),
                  id(10),
                  id(2),
                  1L,
                  10),
              q);
          check(
              core.attempts().lockProgressAttempt(q, TENANT, id(1), id(2), id(99), 1).size() == 1,
              "actual attempt relation");
          write(
              "task.mapper.TaskOperationsMapper.setTerminal",
              args(q, "taskId", id(1), "state", "SUCCEEDED", "code", null));
          write(
              "task.mapper.TaskOperationsMapper.stopAttempt",
              args(q, "attemptId", id(2), "state", "SUCCEEDED"));
          core.attempts().markOwnedAttemptQuiescent(q, TENANT, id(2), id(99), 1);
          for (String category : List.of("QUEUE", "RUNNING")) {
            String reservation = id(1) + (category.equals("QUEUE") ? ":queue" : ":run");
            core.reservations().lockProgressReservation(q, TENANT, id(1), reservation, category);
            check(
                write(
                        "task.mapper.TaskOperationsMapper.releaseReservation",
                        args(q, "reservationId", reservation))
                    == 1,
                "terminal reservation " + category);
          }
          core.tasks().finishSummary(q, "synthetic summary", TENANT, id(1));
          return null;
        });
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM invocation_session_lineage_trace WHERE outcome='SUCCEEDED'",
                Integer.class)
            == 3,
        "three trace bindings committed with one owner");
  }

  private static void negative() {
    for (boolean beforeCommit : List.of(false, true)) {
      for (String mode : List.of("step", "invocation", "dispatch", "attempt", "source")) {
        var before = SessionDependencyScenario.snapshot(db);
        var q = query();
        boolean rejected = false;
        try {
          transactions.required(
              q,
              () -> {
                publish(q);
                Runnable attempt =
                    () -> {
                      boolean refused = false;
                      try {
                        switch (mode) {
                          case "step" ->
                              write(
                                  "execution.mapper.StepMapper.storeModelResponse",
                                  args(
                                      q,
                                      "taskId",
                                      id(1),
                                      "stepId",
                                      id(999),
                                      "response",
                                      "{}",
                                      "digest",
                                      DIGEST));
                          case "invocation" ->
                              write(
                                  "session.mapper.SessionScopeQueryMapper.finishInvocation",
                                  args(
                                      q,
                                      "taskId",
                                      id(1),
                                      "invocation",
                                      id(999),
                                      "outcome",
                                      "SUCCEEDED",
                                      "output",
                                      "{}",
                                      "digest",
                                      DIGEST));
                          case "dispatch" ->
                              write(
                                  "model.mapper.ModelDispatchMapper.recordRejection",
                                  args(q, "taskId", id(1), "dispatchId", id(999), "code", "TEST"));
                          case "attempt" ->
                              write(
                                  "task.mapper.TaskOperationsMapper.stopAttempt",
                                  args(
                                      q, "taskId", id(1), "attemptId", id(999), "state", "FAILED"));
                          case "source" ->
                              write(
                                  "session.mapper.SessionLineageMapper.purge",
                                  args(q, "taskId", id(1)));
                          default -> throw new AssertionError(mode);
                        }
                      } catch (RuntimeException expected) {
                        refused = true;
                      }
                      check(refused, "write refused before SQL " + mode);
                    };
                if (beforeCommit) {
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .registerSynchronization(
                          new org.springframework.transaction.support.TransactionSynchronization() {
                            public int getOrder() {
                              return -100;
                            }

                            public void beforeCommit(boolean readOnly) {
                              attempt.run();
                            }
                          });
                } else attempt.run();
                return null;
              });
        } catch (RuntimeException expected) {
          rejected = true;
        }
        check(
            rejected && before.equals(SessionDependencyScenario.snapshot(db)),
            "caught write still rejects finish/beforeCommit="
                + beforeCommit
                + "; full 22-table rollback "
                + mode);
      }
    }
  }

  private static void relationInvalidation() {
    db.update(
        "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES('tenant-a',?,'alice','relation-source',?,'{}','source',?,1,'SUCCEEDED',CURRENT_TIMESTAMP(6),1,1)",
        id(201),
        DIGEST,
        SessionLineagePolicyV5.CAPABILITIES);
    var config = sql.getConfiguration();
    config.addMappedStatement(
        new org.apache.ibatis.mapping.MappedStatement.Builder(
                config,
                "private.reassignStep",
                new org.apache.ibatis.scripting.defaults.RawSqlSource(
                    config,
                    "UPDATE step SET task_id=#{destination} WHERE tenant_id=#{tenant} AND id=#{stepId}",
                    Map.class),
                org.apache.ibatis.mapping.SqlCommandType.UPDATE)
            .build());
    for (boolean refresh : List.of(false, true))
      for (boolean changed : List.of(false, true))
        for (boolean atCommit : List.of(false, true)) {
          var before = SessionDependencyScenario.snapshot(db);
          var q = query();
          boolean rejected = false;
          try {
            transactions.required(
                q,
                () -> {
                  var owner = SessionQueryTransaction.current(q);
                  owner.admission();
                  sql.selectList(
                      ROOT + "execution.mapper.StepMapper.selectModelStep",
                      args(q, "taskId", id(1), "stepId", id(3)));
                  // Unknown/wrapper write actually changes ownership, or preserves it for refresh
                  // positive.
                  var mutation = args(q, "stepId", id(3), "destination", id(changed ? 201 : 1));
                  mutation.put("ew", new Object());
                  check(
                      sql.update("private.reassignStep", mutation) == 1,
                      "staging wrapper actual step write");
                  if (refresh)
                    sql.selectList(
                        ROOT + "execution.mapper.StepMapper.selectModelStep",
                        args(q, "taskId", id(changed ? 201 : 1), "stepId", id(3)));
                  publish(q);
                  Runnable attempt =
                      () -> {
                        boolean refused = false;
                        try {
                          write(
                              "execution.mapper.StepMapper.storeModelResponse",
                              args(
                                  q,
                                  "taskId",
                                  id(1),
                                  "stepId",
                                  id(3),
                                  "response",
                                  "{}",
                                  "digest",
                                  DIGEST));
                        } catch (RuntimeException expected) {
                          refused = true;
                        }
                        check(
                            refused == (!refresh || changed || atCommit),
                            "stale/source relationship refusal after rebuilding scope");
                      };
                  if (atCommit)
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                            new org.springframework.transaction.support
                                .TransactionSynchronization() {
                              public int getOrder() {
                                return -100;
                              }

                              public void beforeCommit(boolean readOnly) {
                                attempt.run();
                              }
                            });
                  else attempt.run();
                  return null;
                });
          } catch (RuntimeException failure) {
            rejected = true;
          }
          boolean expectedReject = !refresh || changed || atCommit;
          check(
              rejected == expectedReject,
              "relation finish outcome refresh="
                  + refresh
                  + " changed="
                  + changed
                  + " beforeCommit="
                  + atCommit);
          if (expectedReject)
            check(
                before.equals(SessionDependencyScenario.snapshot(db)),
                "relation invalidation full 22-table rollback");
        }
  }

  private static void batch() {
    for (boolean empty : List.of(false, true)) {
      var q = query();
      transactions.required(
          q,
          () -> {
            var records = new JdbcSessionRecords(new JdbcTemplate(db.getDataSource()), q, sql);
            Map<String, Object> task =
                Map.of(
                    "tenant_id",
                    TENANT,
                    "id",
                    empty ? id(999) : id(1),
                    "capabilities",
                    SessionLineagePolicyV5.CAPABILITIES);
            var port = new MapperSessionMetadataAccounting(records, task, true);
            long base = q.scratchLive();
            try (var batch = port.batch(SessionMetadataAccounting.Table.TASK_SESSION_LINEAGE)) {
              check(
                  q.scratchLive() == base + SessionLineageMetadataBatch.RETAINED_BYTES,
                  "actual Mapper cursor closed; batch still held " + empty);
              int count = 0;
              for (var row : batch) {
                for (var value : row.values()) batch.scalarBytes(value);
                count++;
              }
              check(count == (empty ? 0 : 1), "actual Mapper bounded rows " + count);
            }
            check(q.scratchLive() == base, "actual Mapper batch finally released " + empty);
            try (var batch = port.batch(SessionMetadataAccounting.Table.TASK_SESSION_HISTORY)) {
              check(
                  q.scratchLive() == base + SessionHistoryMetadataBatch.RETAINED_BYTES,
                  "actual history cursor closed; history batch still held " + empty);
              int count = 0;
              for (var row : batch) {
                for (var value : row.values()) batch.scalarBytes(value);
                count++;
              }
              check(count == (empty ? 0 : 1), "actual history bounded rows " + count);
            }
            check(q.scratchLive() == base, "actual history batch finally released " + empty);
            return null;
          });
    }
    var before = SessionDependencyScenario.snapshot(db);
    var limited = query();
    boolean rejected = false;
    try {
      transactions.required(
          limited,
          () -> {
            publish(limited);
            CoreMappers.from(sql).tasks().finishSummary(limited, "must roll back", TENANT, id(1));
            limited.retainScratch(
                new Object(),
                1048576 - limited.scratchLive() - SessionLineageMetadataBatch.RETAINED_BYTES + 1);
            var records =
                new JdbcSessionRecords(new JdbcTemplate(db.getDataSource()), limited, sql);
            var port =
                new MapperSessionMetadataAccounting(
                    records,
                    Map.of(
                        "tenant_id",
                        TENANT,
                        "id",
                        id(1),
                        "capabilities",
                        SessionLineagePolicyV5.CAPABILITIES),
                    true);
            try (var ignored = port.batch(SessionMetadataAccounting.Table.TASK_SESSION_LINEAGE)) {
              throw new AssertionError("budget must refuse before Mapper");
            }
          });
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure expected) {
      rejected = expected.code().equals("SESSION_HISTORY_LIMIT");
    }
    check(
        rejected && before.equals(SessionDependencyScenario.snapshot(db)),
        "batch preallocation refusal rolls back prior DML across 22 tables");
    var historyQuery = query();
    boolean historyRejected = false;
    try {
      transactions.required(
          historyQuery,
          () -> {
            publish(historyQuery);
            CoreMappers.from(sql)
                .tasks()
                .finishSummary(historyQuery, "history must rollback", TENANT, id(1));
            historyQuery.retainScratch(
                new Object(),
                1048576
                    - historyQuery.scratchLive()
                    - SessionHistoryMetadataBatch.RETAINED_BYTES
                    + 1);
            var port =
                new MapperSessionMetadataAccounting(
                    new JdbcSessionRecords(new JdbcTemplate(db.getDataSource()), historyQuery, sql),
                    Map.of(
                        "tenant_id",
                        TENANT,
                        "id",
                        id(1),
                        "capabilities",
                        SessionLineagePolicyV5.CAPABILITIES),
                    true);
            try (var ignored = port.batch(SessionMetadataAccounting.Table.TASK_SESSION_HISTORY)) {
              throw new AssertionError("history preallocation");
            }
          });
    } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure expected) {
      historyRejected = expected.code().equals("SESSION_HISTORY_LIMIT");
    }
    check(
        historyRejected && before.equals(SessionDependencyScenario.snapshot(db)),
        "history preallocation refuses before SQL and rolls back 22 tables");
  }

  private static void retention() {
    db.update(
        "UPDATE task_session_history SET retain_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE task_id=?",
        id(1));
    db.update(
        "UPDATE task_session_history SET body_bytes=? WHERE task_id=?",
        SessionRepairSqlOracle.inspect(db, id(1)).body(),
        id(1));
    SessionRepairSqlOracle.dump(db, id(1), "before-root-retention");
    for (String state : List.of("PENDING", "OUTCOME_UNKNOWN")) {
      db.update("UPDATE invocation SET state=? WHERE id=?", state, id(10));
      var before = SessionDependencyScenario.snapshot(db);
      root.cleanupSessionHistory();
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "root cleanup blocked by invocation " + state);
      var q = query();
      transactions.required(
          q,
          () -> {
            check(
                !sql.getMapper(
                        io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                            .SessionHistoryRetentionMapper.class)
                    .activeInvocations(q, TENANT, id(1))
                    .isEmpty(),
                "actual locked eligibility predicate " + state);
            return null;
          });
      db.update("UPDATE invocation SET state='SUCCEEDED' WHERE id=?", id(10));
    }
    // 独立绑定PENDING屏障：invocation本身成功，旧查询不能误认为无活跃写。
    db.update(
        "UPDATE invocation_session_lineage_trace SET outcome='PENDING',scan_digest=NULL,node_count=NULL,edge_count=NULL,proof_reads=NULL,proof_bytes=NULL,output_digest=NULL,output_bytes=NULL,receipt_event_seq=NULL,committed_attempt_id=NULL,committed_fence=NULL,committed_at=NULL WHERE invocation_id=?",
        id(10));
    var before = SessionDependencyScenario.snapshot(db);
    root.cleanupSessionHistory();
    check(
        before.equals(SessionDependencyScenario.snapshot(db)),
        "root cleanup blocked by independent lineage PENDING");
    var pendingQuery = query();
    transactions.required(
        pendingQuery,
        () -> {
          check(
              !sql.getMapper(
                      io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                          .SessionHistoryRetentionMapper.class)
                  .activeInvocations(pendingQuery, TENANT, id(1))
                  .isEmpty(),
              "locked predicate includes independent lineage PENDING");
          return null;
        });
    db.update(
        "UPDATE invocation_session_lineage_trace SET outcome='FAILED',failure_code='FIXTURE',output_bytes=0,receipt_event_seq=10,committed_attempt_id=?,committed_fence=1,committed_at=CURRENT_TIMESTAMP(6) WHERE invocation_id=?",
        id(2),
        id(10));
    db.update(
        "UPDATE invocation_session_scope_query SET outcome='PENDING',scan_digest=NULL,scan_rows=NULL,scan_event_bytes=NULL,output_digest=NULL,output_bytes=NULL,receipt_event_seq=NULL,committed_attempt_id=NULL,committed_fence=NULL,committed_at=NULL WHERE invocation_id=?",
        id(13));
    var scopeBefore = SessionDependencyScenario.snapshot(db);
    root.cleanupSessionHistory();
    check(
        scopeBefore.equals(SessionDependencyScenario.snapshot(db)),
        "root cleanup blocked by independent scope-query PENDING");
    db.update(
        "UPDATE invocation_session_scope_query SET outcome='FAILED',failure_code='FIXTURE',output_bytes=0,receipt_event_seq=13,committed_attempt_id=?,committed_fence=1,committed_at=CURRENT_TIMESTAMP(6) WHERE invocation_id=?",
        id(2),
        id(13));
    var tail = new RetentionTail();
    root.mappers.session().getConfiguration().addInterceptor(tail);
    tail.armed = true;
    var beforeTail = SessionDependencyScenario.snapshot(db);
    boolean rolledBack = false;
    try {
      root.cleanupSessionHistory();
    } catch (RuntimeException expected) {
      rolledBack = true;
    }
    check(
        rolledBack && tail.reached && beforeTail.equals(SessionDependencyScenario.snapshot(db)),
        "first root purge reaches beforeCommit and rolls back all 22 tables");
    tail.armed = false;
    long held =
        db.queryForObject(
            "SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'",
            Long.class);
    root.cleanupSessionHistory();
    var independent = SessionRepairSqlOracle.inspect(db, id(1));
    var balance =
        db.queryForMap(
            "SELECT h.metadata_bytes,h.body_bytes,h.held_bytes,h.reserved_bytes,r.amount,r.actual,r.state FROM task_session_history h JOIN reservation r ON r.tenant_id=h.tenant_id AND r.id=CONCAT(h.task_id,':session-history') WHERE h.task_id=?",
            id(1));
    check(
        independent.body() == 0
            && independent.metadata() == ((Number) balance.get("metadata_bytes")).longValue(),
        "root first purge independent 18-projection/BODY " + independent);
    check(
        ((Number) balance.get("amount")).longValue() == 8388608
            && ((Number) balance.get("reserved_bytes")).longValue() == 8388608
            && ((Number) balance.get("held_bytes")).longValue() == independent.metadata()
            && ((Number) balance.get("actual")).longValue() == independent.metadata(),
        "original reserve and settled balance");
    check(
        held
                - db.queryForObject(
                    "SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'",
                    Long.class)
            == 8388608 - independent.metadata(),
        "tenant exact one refund");
    var after = SessionDependencyScenario.snapshot(db);
    root.cleanupSessionHistory();
    check(
        after.equals(SessionDependencyScenario.snapshot(db)),
        "second root cleanup exactly unchanged");
    System.out.println("BALANCE " + Payloads.json(balance));
    SessionRepairSqlOracle.dump(db, id(1), "after-root-retention");
  }

  public static void main(String[] argv) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(argv[0]))) {
      props.load(in);
    }
    var source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    sql = LedgerMapperSessions.create(source);
    transactions = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", props));
    root = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    seed();
    tail();
    relationInvalidation();
    negative();
    batch();
    retention();
    SessionMaintenanceRootScenario.run(db, root);
    System.out.println("TAIL_RESIDENCY_COMPLETE synthetic proof; local Mapper scope only; no A03");
  }
}
