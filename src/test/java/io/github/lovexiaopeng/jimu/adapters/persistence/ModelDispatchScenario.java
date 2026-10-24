package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一个驱动对照准确旧/新制品；合成 paid-run 只用私有 MySQL，无外部 provider 调用。 */
public final class ModelDispatchScenario {
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static final ModelUsage KNOWN =
      new ModelUsage(100L, 50L, 150L, null, null, null, null, null);
  private static final List<Map<String, Object>> TRACE =
      Collections.synchronizedList(new ArrayList<>());
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static final AtomicBoolean FAIL_COST = new AtomicBoolean();
  private static final AtomicBoolean COST_WRITTEN = new AtomicBoolean();

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
    db = new JdbcTemplate(source);
    try {
      for (String mode :
          List.of(
              "known",
              "unknown",
              "partial-overrun",
              "known-overrun",
              "overflow",
              "failed-null",
              "failed-known",
              "corrupt-proof",
              "missing-tokens",
              "invalid-call",
              "old-fence",
              "other-tenant",
              "response-race",
              "dispatch-race",
              "cost-rollback")) {
        String run = "model-" + args[1] + "-" + mode;
        properties.setProperty("jimu.model.evaluation-run-id", run);
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
        ledger = new JdbcLedger(new ObservedSource(source), new ModelProfiles(env));
        db.update("INSERT INTO evaluation_budget(tenant_id,id) VALUES(?,?)", OWNER.tenant(), run);
        exercise(args[1], mode, run);
      }
      System.out.println("PASS model dispatch differential " + args[1]);
    } finally {
      Files.writeString(Path.of(args[2]), Payloads.json(TRACE));
    }
  }

  private static Map<String, Object> tenantBefore;

  private static void exercise(String tag, String mode, String run) throws Exception {
    tenantBefore =
        db.queryForMap(
            "SELECT model_used,token_held,cost_held FROM tenant_budget WHERE tenant_id=?",
            OWNER.tenant());
    String worker = "model-" + tag + "-" + mode;
    ledger.registerWorker(
        worker, "private-model", ProcessHandle.current().pid(), 1, "fixture", "fixture");
    String task =
        ledger
            .submit(
                OWNER,
                worker,
                new Tasks.Request(
                    worker, List.of("web_fetch"), List.of(), new Tasks.Budget(120, 8, 20, null)))
            .taskId();
    var lease = ledger.claim(worker).orElseThrow();
    check(task.equals(lease.taskId()), "exact model candidate");
    var model = ledger.modelSnapshot(lease);
    check(model.paid(), "explicit synthetic paid profile");
    String body = "{\"messages\":[]}";
    String request =
        Payloads.json(
            Map.of(
                "requestBody",
                body,
                "bodyDigest",
                Payloads.hash(body),
                "configDigest",
                ModelProfiles.digest(model)));
    var step = ledger.prepare(lease, request);
    if (mode.equals("dispatch-race")) {
      var results =
          race(
              () -> ledger.dispatchModel(lease, step.id()),
              () -> ledger.dispatchModel(lease, step.id()));
      check(
          results.equals(List.of("MODEL_BUDGET_EXCEEDED", "OK")),
          "paid dispatch has one admitted winner");
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM model_dispatch WHERE tenant_id=? AND task_id=?",
                  Long.class,
                  OWNER.tenant(),
                  task)
              == 1,
          "one paid dispatch row");
    } else {
      String dispatch = ledger.dispatchModel(lease, step.id());
      switch (mode) {
        case "known" -> respond(lease, step.id(), dispatch, KNOWN, List.of());
        case "unknown" ->
            reject(
                "MODEL_USAGE_UNKNOWN",
                () ->
                    respond(
                        lease,
                        step.id(),
                        dispatch,
                        new ModelUsage(null, null, null, null, null, null, null, null),
                        List.of()));
        case "partial-overrun" ->
            reject(
                "MODEL_COST_BOUND_EXCEEDED",
                () ->
                    respond(
                        lease,
                        step.id(),
                        dispatch,
                        new ModelUsage(9000L, null, null, null, null, null, null, null),
                        List.of()));
        case "known-overrun" ->
            reject(
                "MODEL_COST_BOUND_EXCEEDED",
                () ->
                    respond(
                        lease,
                        step.id(),
                        dispatch,
                        new ModelUsage(9000L, 1000L, 10000L, null, null, null, null, null),
                        List.of()));
        case "overflow" ->
            reject(
                "MODEL_COST_BOUND_EXCEEDED",
                () ->
                    respond(
                        lease,
                        step.id(),
                        dispatch,
                        new ModelUsage(
                            Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            null,
                            null,
                            null,
                            null,
                            null),
                        List.of()));
        case "failed-null" -> ledger.modelFailed(lease, dispatch, "FIXTURE_FAILURE", null);
        case "failed-known" -> ledger.modelFailed(lease, dispatch, "FIXTURE_FAILURE", KNOWN);
        case "corrupt-proof" -> {
          db.update(
              "UPDATE task SET model_digest=REPEAT('f',64) WHERE tenant_id=? AND id=?",
              OWNER.tenant(),
              task);
          reject(
              "MODEL_SETTLEMENT_PROOF_INVALID",
              () -> ledger.modelFailed(lease, dispatch, "FIXTURE_FAILURE", KNOWN));
          check(
              "STOPPED"
                  .equals(
                      db.queryForObject(
                          "SELECT state FROM evaluation_budget WHERE tenant_id=? AND id=?",
                          String.class,
                          OWNER.tenant(),
                          run)),
              "proof rejection commits stopped run");
        }
        case "missing-tokens" -> {
          db.update("DELETE FROM reservation WHERE tenant_id=? AND id=?", OWNER.tenant(), dispatch);
          String before = snapshot(task, run);
          reject("NOT_FOUND", () -> respond(lease, step.id(), dispatch, KNOWN, List.of()));
          check(
              before.equals(snapshot(task, run)),
              "missing tokens rolls back actual cost, run and response");
        }
        case "invalid-call" -> {
          var call = new ExecutionLedger.Call("slot", "provider", "web_fetch", "x".repeat(65536));
          reject(
              "TOOL_INPUT_INVALID",
              () -> respond(lease, step.id(), dispatch, KNOWN, List.of(call)));
          check(
              db.queryForObject(
                      "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND task_id=?",
                      Long.class,
                      OWNER.tenant(),
                      task)
                  == 0,
              "rejected effect never becomes intent");
          check(
              "KNOWN"
                  .equals(
                      db.queryForObject(
                          "SELECT cost_quality FROM model_dispatch WHERE tenant_id=? AND id=?",
                          String.class,
                          OWNER.tenant(),
                          dispatch)),
              "rejection retains actual charge");
        }
        case "old-fence", "other-tenant" -> {
          var wrong =
              new ExecutionLedger.Lease(
                  mode.equals("other-tenant") ? "tenant-b" : lease.tenant(),
                  lease.actor(),
                  lease.taskId(),
                  lease.attemptId(),
                  lease.fence() + (mode.equals("old-fence") ? 1 : 0),
                  lease.worker(),
                  lease.deadline(),
                  lease.goal(),
                  lease.capabilities(),
                  lease.installationEpoch());
          String before = snapshot(task, run);
          reject(
              mode.equals("old-fence") ? "FENCE_REJECTED" : "NOT_FOUND",
              () -> respond(wrong, step.id(), dispatch, KNOWN, List.of()));
          check(before.equals(snapshot(task, run)), "tenant/fence refusal changes no账目");
        }
        case "response-race" -> {
          var results =
              race(
                  () -> {
                    respond(lease, step.id(), dispatch, KNOWN, List.of());
                    return "done";
                  },
                  () -> {
                    respond(lease, step.id(), dispatch, KNOWN, List.of());
                    return "done";
                  });
          check(
              results.equals(List.of("DISPATCH_REJECTED", "OK")),
              "two connections settle exactly once");
        }
        case "cost-rollback" -> {
          String before = snapshot(task, run);
          COST_WRITTEN.set(false);
          FAIL_COST.set(true);
          try {
            respond(lease, step.id(), dispatch, KNOWN, List.of());
            throw new AssertionError("fault was not reached");
          } catch (org.springframework.dao.DataAccessException expected) {
            check(
                COST_WRITTEN.get(),
                "actual evaluation cost write preceded injected tenant failure");
          } finally {
            FAIL_COST.set(false);
          }
          check(
              before.equals(snapshot(task, run)),
              "Mapper cost write rolled back atomically with JDBC UoW");
          respond(lease, step.id(), dispatch, KNOWN, List.of());
        }
        default -> throw new AssertionError(mode);
      }
      if (mode.equals("known") || mode.equals("failed-known")) {
        String before = snapshot(task, run);
        reject("DISPATCH_REJECTED", () -> ledger.modelFailed(lease, dispatch, "DUPLICATE", KNOWN));
        check(before.equals(snapshot(task, run)), "duplicate result cannot alter settled fact");
      }
    }
    System.out.println("RESULT " + mode + " " + snapshot(task, run));
    ledger.stop(
        lease,
        mode.equals("corrupt-proof") ? "MODEL_SETTLEMENT_PROOF_INVALID" : "FIXTURE_FINISHED");
    ledger.quiescent(lease);
    ledger.processStopped(worker);
  }

  private static void respond(
      ExecutionLedger.Lease lease,
      String step,
      String dispatch,
      ModelUsage usage,
      List<ExecutionLedger.Call> calls) {
    Integer tokens =
        usage.totalTokens() == null || usage.totalTokens() > Integer.MAX_VALUE
            ? null
            : usage.totalTokens().intValue();
    ledger.modelResponded(lease, step, dispatch, "{}", calls, tokens, usage);
  }

  private static String snapshot(String task, String run) {
    var tenant =
        db.queryForMap(
            "SELECT model_used,token_held,cost_held FROM tenant_budget WHERE tenant_id=?",
            OWNER.tenant());
    var tenantDelta = new java.util.TreeMap<String, Long>();
    tenant.forEach(
        (key, value) ->
            tenantDelta.put(
                key, ((Number) value).longValue() - ((Number) tenantBefore.get(key)).longValue()));
    return Payloads.json(
        List.of(
            tenantDelta,
            db.queryForList(
                "SELECT state,code,models_used,token_held,cost_held,max_models FROM task WHERE tenant_id=? AND id=?",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,requests_used,cost_held FROM evaluation_budget WHERE tenant_id=? AND id=?",
                OWNER.tenant(),
                run),
            db.queryForList(
                "SELECT category,state,amount,actual,usage_quality,cost_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY category",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,code,input_tokens,output_tokens,total_tokens,reserved_cost,actual_cost,cost_quality FROM model_dispatch WHERE tenant_id=? AND task_id=? ORDER BY state",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,response_snapshot FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT type,code FROM event WHERE tenant_id=? AND task_id=? ORDER BY seq",
                OWNER.tenant(),
                task)));
  }

  private static List<String> race(Callable<String> one, Callable<String> two) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    try {
      var jobs = new ArrayList<java.util.concurrent.Future<String>>();
      for (var job : List.of(one, two))
        jobs.add(
            pool.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  try {
                    job.call();
                    return "OK";
                  } catch (TaskFailure failure) {
                    return failure.code();
                  }
                }));
      var results = new ArrayList<String>();
      for (var job : jobs) results.add(job.get(15, TimeUnit.SECONDS));
      Collections.sort(results);
      return results;
    } finally {
      pool.shutdownNow();
      check(pool.awaitTermination(5, TimeUnit.SECONDS), "model contenders collected");
    }
  }

  private static void reject(String code, Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected refusal " + code);
    } catch (TaskFailure refusal) {
      check(code.equals(refusal.code()), "expected " + code + ", actual " + refusal.code());
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class ObservedSource extends AbstractDataSource {
    private final DriverManagerDataSource source;

    ObservedSource(DriverManagerDataSource source) {
      this.source = source;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrap(source.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return wrap(source.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
      long id;
      try (var statement = connection.createStatement();
          var row = statement.executeQuery("SELECT CONNECTION_ID()")) {
        row.next();
        id = row.getLong(1);
      }
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  Object result = method.invoke(connection, args);
                  if (List.of("commit", "rollback", "setAutoCommit").contains(method.getName()))
                    TRACE.add(
                        Map.of(
                            "connection",
                            id,
                            "thread",
                            Thread.currentThread().getName(),
                            "event",
                            method.getName(),
                            "autoCommit",
                            connection.getAutoCommit()));
                  if (method.getName().equals("prepareStatement")
                      && result instanceof PreparedStatement statement) {
                    String sql = (String) args[0];
                    return Proxy.newProxyInstance(
                        PreparedStatement.class.getClassLoader(),
                        new Class<?>[] {PreparedStatement.class},
                        (p, m, a) -> {
                          try {
                            if (m.getName().startsWith("execute")) {
                              TRACE.add(
                                  Map.of(
                                      "connection",
                                      id,
                                      "thread",
                                      Thread.currentThread().getName(),
                                      "event",
                                      m.getName(),
                                      "sql",
                                      sql,
                                      "autoCommit",
                                      connection.getAutoCommit()));
                              if (FAIL_COST.get()
                                  && sql.startsWith(
                                      "UPDATE tenant_budget SET cost_held=cost_held+")) {
                                check(
                                    COST_WRITTEN.get(),
                                    "preceding evaluation UPDATE was actually executed");
                                throw new SQLException(
                                    "Synthetic private failure after actual evaluation cost write",
                                    "HY000",
                                    1644);
                              }
                            }
                            Object value = m.invoke(statement, a);
                            if (FAIL_COST.get()
                                && m.getName().startsWith("execute")
                                && sql.startsWith(
                                    "UPDATE evaluation_budget SET cost_held=cost_held+"))
                              COST_WRITTEN.set(true);
                            return value;
                          } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                          }
                        });
                  }
                  return result;
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }
  }
}
