package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Local integration fixture only: real binder/ledger calls, explicit crash windows and accounting.
 */
public final class ExecutionScopeTestMain {
  private ExecutionScopeTestMain() {}

  private static void require(boolean value) {
    if (!value) throw new AssertionError("Scope fixture assertion failed");
  }

  private static ModelProfiles models(Map<String, Object> properties) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove("systemEnvironment");
    env.getPropertySources().remove("systemProperties");
    env.getPropertySources().addFirst(new MapPropertySource("fixture", properties));
    return new ModelProfiles(env);
  }

  public static void main(String[] args) throws Exception {
    var p = new Properties();
    try (var reader = Files.newBufferedReader(Path.of(args[1]))) {
      p.load(reader);
    }
    var config = new HashMap<String, Object>();
    p.forEach((k, v) -> config.put(k.toString(), v));
    if (args[0].equals("business-pins")) {
      var env = new StandardEnvironment();
      env.getPropertySources().remove("systemEnvironment");
      env.getPropertySources().remove("systemProperties");
      env.getPropertySources().addFirst(new MapPropertySource("fixture", config));
      var settings =
          org.springframework.boot.context.properties.bind.Binder.get(env)
              .bind(
                  "jimu.model",
                  org.springframework.boot.context.properties.bind.Bindable.of(
                      ModelProfiles.Settings.class))
              .get();
      var pins = new TreeMap<String, String>();
      settings.profiles().forEach((ref, model) -> pins.put(ref, ModelProfiles.digest(model)));
      System.out.println(Payloads.json(pins));
      return;
    }
    var source =
        new DriverManagerDataSource(
            p.getProperty("jimu.db.url"),
            p.getProperty("jimu.db.migration-user"),
            p.getProperty("jimu.db.migration-password"));
    Path file = Path.of(args[2]);
    if (args[0].equals("submit")) {
      try {
        var goal = Files.readString(Path.of(args[3]));
        var ledger = new JdbcLedger(source, models(config));
        var request =
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                goal,
                List.of("web_fetch", "read", "write", "present"),
                List.of(),
                new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(600, 16, 32, null));
        var result =
            ledger.submit(
                new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice"),
                args[4],
                request);
        System.out.println("ACCEPTED:" + result.taskId());
      } catch (TaskFailure
          | IllegalArgumentException
          | org.springframework.boot.context.properties.bind.BindException failure) {
        System.out.println(
            "REFUSED:"
                + (failure instanceof TaskFailure f ? f.code() : "INVALID_MODEL_CONFIGURATION"));
      }
      return;
    }
    var profiles = models(config);
    if (args[0].equals("budget-race")) {
      var db = new JdbcTemplate(source);
      var gate = new CountDownLatch(1);
      var ledger = new JdbcLedger(source, profiles);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (String taskId : List.of(args[3], args[4])) {
          var task = db.queryForMap("SELECT * FROM task WHERE id=?", taskId);
          var lease =
              new ExecutionLedger.Lease(
                  (String) task.get("tenant_id"),
                  (String) task.get("owner_actor_id"),
                  taskId,
                  (String) task.get("current_attempt_id"),
                  ((Number) task.get("fence")).longValue(),
                  (String) task.get("worker_id"),
                  ((java.time.LocalDateTime) task.get("deadline"))
                      .toInstant(java.time.ZoneOffset.UTC),
                  (String) task.get("goal"),
                  List.of(((String) task.get("capabilities")).split(",")),
                  ((Number) task.get("installation_epoch")).longValue());
          String step =
              db.queryForObject(
                  "SELECT id FROM step WHERE task_id=? AND ordinal=1", String.class, taskId);
          futures.add(
              pool.submit(
                  () -> {
                    gate.await();
                    try {
                      ledger.dispatchModel(lease, step);
                      return "ADMITTED";
                    } catch (TaskFailure failure) {
                      return failure.code();
                    }
                  }));
        }
        gate.countDown();
        var results = new java.util.ArrayList<String>();
        for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
        require(results.stream().filter("ADMITTED"::equals).count() == 1);
        require(results.stream().filter("MODEL_BUDGET_EXCEEDED"::equals).count() == 1);
        System.out.println(Payloads.json(results));
      }
      return;
    }
    if (args[0].equals("repin")) {
      var body =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              Payloads.read(Files.readString(file), com.fasterxml.jackson.databind.JsonNode.class);
      body.path("profiles")
          .properties()
          .forEach(
              entry -> {
                var model = Payloads.read(Payloads.json(entry.getValue()), ModelSnapshot.class);
                var pin =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                        body.path("scope").path("profilePins").path(entry.getKey());
                pin.put("modelSnapshotDigest", ModelProfiles.digest(model));
              });
      var account =
          Payloads.read(
              Payloads.json(body.path("accountEvidence")), ExecutionScopes.AccountEvidence.class);
      ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("scope"))
          .put("accountEvidenceDigest", Payloads.hash(Payloads.json(account)));
      Files.writeString(file, Payloads.json(body));
      System.out.println("repinned fixture input");
      return;
    }
    if (args[0].equals("generate")) {
      var req = profiles.scopeRequirement();
      var plan = EvaluationPlans.plan("qwen-main-v1");
      var ordinary = profiles.selected(req.tenantId());
      config.put("jimu.model.default-profile-ref", "sse");
      var stream = models(config).selected(req.tenantId());
      var snapshots = new TreeMap<String, ModelSnapshot>();
      snapshots.put(ordinary.profile(), ordinary);
      snapshots.put(stream.profile(), stream);
      var pins = new TreeMap<String, ExecutionScopes.Pin>();
      snapshots.forEach(
          (id, m) ->
              pins.put(
                  id,
                  new ExecutionScopes.Pin(m.configVersion(), m.stream(), ModelProfiles.digest(m))));
      var account =
          new ExecutionScopes.AccountEvidence(
              "local-fixture",
              "local-synthetic",
              "127.0.0.1",
              true,
              true,
              Payloads.hash("local-account-facts"));
      var scope =
          new ExecutionScopes.Scope(
              1,
              "local-plus-scope-v1",
              req.issueId(),
              req.instanceUuid(),
              req.schema(),
              req.tenantId(),
              req.runId(),
              req.approvalDigest(),
              EvaluationPlans.digest(plan),
              plan.sourcePlanSha256(),
              plan.materialsDigest(),
              Payloads.hash(Payloads.json(account)),
              ordinary.model(),
              ordinary.revision(),
              pins,
              Map.of(
                  "W1",
                  ordinary.profile(),
                  "W2",
                  stream.profile(),
                  "W3",
                  ordinary.profile(),
                  "W4",
                  stream.profile()),
              true);
      Files.writeString(
          file,
          Payloads.json(
              new ExecutionScopeBinding.Input(
                  scope, snapshots, account, "local-isolated-operator")));
      System.out.println(Payloads.hash(ExecutionScopes.canonical(scope)));
      return;
    }
    if (args[0].startsWith("bind")) {
      var input = Payloads.read(Files.readString(file), ExecutionScopeBinding.Input.class);
      javax.sql.DataSource bindingSource = source;
      if (args[0].startsWith("bind-crash-")) {
        String point = args[0].substring("bind-crash-".length());
        Path marker = Path.of(args[3]);
        bindingSource =
            new DelegatingDataSource(source) {
              @Override
              public Connection getConnection() throws java.sql.SQLException {
                var actual = super.getConnection();
                return (Connection)
                    Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, values) -> {
                          if (method.getName().equals("commit") && point.equals("before"))
                            pause(marker);
                          try {
                            Object result = method.invoke(actual, values);
                            if (method.getName().equals("commit") && point.equals("after"))
                              pause(marker);
                            return result;
                          } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                          }
                        });
              }
            };
      }
      try {
        var binder =
            args[0].equals("bind-production")
                ? new ExecutionScopeBinding(bindingSource, profiles.scopeRequirement())
                : new ExecutionScopeBinding(bindingSource, profiles);
        var row = binder.bindOnce(input);
        require(
            Payloads.hash((String) row.get("ledger_readback"))
                .equals(row.get("ledger_readback_digest")));
        System.out.println(
            Payloads.json(
                Map.of(
                    "result",
                    "bound",
                    "scopeDigest",
                    row.get("scope_digest"),
                    "ledgerReadback",
                    row.get("ledger_readback"),
                    "ledgerReadbackDigest",
                    row.get("ledger_readback_digest"),
                    "boundAtUtc",
                    row.get("bound_at_utc").toString(),
                    "boundBy",
                    row.get("bound_by"))));
      } catch (TaskFailure | IllegalArgumentException refused) {
        System.out.println(
            Payloads.json(
                Map.of(
                    "result",
                    "refused",
                    "code",
                    refused instanceof TaskFailure failure ? failure.code() : "INVALID_BINDING")));
      }
      return;
    }
    if (args[0].equals("permissions")) {
      var app =
          new DriverManagerDataSource(
              p.getProperty("jimu.db.url"),
              p.getProperty("jimu.db.user"),
              p.getProperty("jimu.db.password"));
      var db = new JdbcTemplate(app);
      int denied = 0;
      for (String table :
          List.of(
              "evaluation_execution_scope", "flyway_schema_history", "jimu_provisioning_receipt")) {
        db.queryForList("SELECT * FROM " + table);
        for (String sql :
            List.of(
                "DELETE FROM " + table + " WHERE 1=0",
                "UPDATE "
                    + table
                    + " SET "
                    + (table.equals("flyway_schema_history")
                        ? "success=success"
                        : "record_version=record_version")
                    + " WHERE 1=0",
                "INSERT INTO " + table + " SELECT * FROM " + table + " WHERE 1=0")) {
          try {
            db.execute(sql);
            throw new AssertionError("Unexpected metadata write permission");
          } catch (org.springframework.dao.PermissionDeniedDataAccessException expected) {
            denied++;
          } catch (org.springframework.jdbc.BadSqlGrammarException deniedSql) {
            require(((java.sql.SQLException) deniedSql.getCause()).getErrorCode() == 1142);
            denied++;
          }
        }
      }
      require(denied == 9);
      System.out.println("metadata write denials=9");
      return;
    }
    if (args[0].startsWith("settle-") || args[0].equals("guard-path")) {
      String taskId = args[3];
      var db = new JdbcTemplate(source);
      var task = db.queryForMap("SELECT * FROM task WHERE id=?", taskId);
      var lease =
          new ExecutionLedger.Lease(
              (String) task.get("tenant_id"),
              (String) task.get("owner_actor_id"),
              taskId,
              (String) task.get("current_attempt_id"),
              ((Number) task.get("fence")).longValue(),
              (String) task.get("worker_id"),
              ((java.time.LocalDateTime) task.get("deadline")).toInstant(java.time.ZoneOffset.UTC),
              (String) task.get("goal"),
              List.of(((String) task.get("capabilities")).split(",")),
              ((Number) task.get("installation_epoch")).longValue());
      if (args[0].equals("guard-path")) {
        var ledger = new JdbcLedger(source, profiles);
        var actions = new TreeMap<String, Runnable>();
        actions.put("checkpoint", () -> ledger.checkpoint(lease));
        actions.put("modelSnapshot", () -> ledger.modelSnapshot(lease));
        actions.put("toolSnapshot", () -> ledger.toolSnapshot(lease));
        actions.put("prepare", () -> ledger.prepare(lease, "{}"));
        actions.put("dispatch", () -> ledger.dispatchModel(lease, "unused"));
        actions.put("evaluationFile", () -> ledger.evaluationFile(lease));
        actions.put(
            "bindEvaluationFile",
            () ->
                ledger.bindEvaluationFile(
                    lease,
                    new ExecutionLedger.ReportFile("unused", 1, "unused", 1, "text/markdown")));
        actions.put(
            "toolAdmission",
            () ->
                ledger.admit(
                    lease, "unused", new ExecutionLedger.Call("x", "x", "read", "{}"), "x", "x"));
        actions.put("reportContext", () -> ledger.reportContext(lease));
        actions.put("stageReports", () -> ledger.stageReports(lease, "unused", List.of()));
        actions.put(
            "presentSucceeded", () -> ledger.presentSucceeded(lease, "unused", List.of(), "{}"));
        actions.put("stage", () -> ledger.stage(lease, "unused", 1));
        actions.put("verified", () -> ledger.verified(lease, "unused", "unused"));
        actions.put("publish", () -> ledger.publish(lease, "unused"));
        actions.put("finish", () -> ledger.finish(lease, "unused"));
        if (args[4].equals("positive")) {
          ledger.checkpoint(lease);
          ledger.modelSnapshot(lease);
          ledger.toolSnapshot(lease);
          System.out.println("OPEN_SCOPE_POSITIVE");
          return;
        }
        var action = actions.get(args[4]);
        require(action != null);
        try {
          action.run();
          throw new AssertionError("Unexpected scope admission: " + args[4]);
        } catch (TaskFailure failure) {
          require(failure.code().equals(args.length > 5 ? args[5] : "EVALUATION_SCOPE_MISMATCH"));
          System.out.println(Payloads.json(Map.of(args[4], failure.code())));
        }
        return;
      }
      if (args[0].startsWith("settle-tool-")) {
        var removed = new HashMap<String, Object>(config);
        removed.keySet().removeIf(k -> k.startsWith("jimu.model."));
        var ledger = new JdbcLedger(source, models(removed));
        String invocation =
            db.queryForObject(
                "SELECT id FROM invocation WHERE task_id=? AND state='RUNNING'",
                String.class,
                taskId);
        if (args[0].endsWith("success")) {
          var material =
              Payloads.read(
                  (String) task.get("material_snapshot"),
                  com.fasterxml.jackson.databind.JsonNode.class);
          var inputJson =
              Payloads.read(
                  db.queryForObject(
                      "SELECT input_snapshot FROM invocation WHERE id=?", String.class, invocation),
                  com.fasterxml.jackson.databind.JsonNode.class);
          String output =
              Payloads.json(material.path("sources").path(inputJson.path("url").asText()));
          ledger.toolSucceeded(lease, invocation, output);
        } else if (args[0].endsWith("unknown")) ledger.uncertain(lease, invocation);
        else ledger.toolFailed(lease, invocation, "LOCAL_FIXTURE_FAILURE");
        System.out.println("historical tool settlement=" + args[0]);
        return;
      }
      var dispatch =
          db.queryForMap(
              "SELECT * FROM model_dispatch WHERE task_id=? AND state='DISPATCHED'", taskId);
      var removed = new HashMap<String, Object>(config);
      removed.keySet().removeIf(k -> k.startsWith("jimu.model."));
      var ledger = new JdbcLedger(source, models(removed));
      ModelUsage usage =
          ((Number) task.get("record_version")).intValue() == 2
              ? new ModelUsage(
                  100L,
                  args[0].equals("settle-unknown") ? null : 50L,
                  args[0].equals("settle-unknown") ? null : 150L,
                  null,
                  null,
                  null,
                  null,
                  null)
              : args[0].equals("settle-unknown")
                  ? new ModelUsage(1000000L, null, null, null, null, null, null, null)
                  : new ModelUsage(1000000L, 4096L, 1004096L, null, null, null, null, null);
      try {
        ledger.modelResponded(
            lease,
            (String) dispatch.get("step_id"),
            (String) dispatch.get("id"),
            "{\"hostFixtureAuditOnly\":true}",
            List.of(),
            ((Number) task.get("record_version")).intValue() == 2 ? 150 : 1004096,
            usage);
      } catch (TaskFailure rejected) {
        System.out.println("settlement post-commit rejection=" + rejected.code());
      }
      System.out.println(
          "historical settlement fixture completed; no current profile and no tool intents");
      return;
    }
    throw new IllegalArgumentException("Unknown fixture action");
  }

  private static void pause(Path marker) throws Exception {
    Files.writeString(marker, "transaction-commit-window");
    while (!Files.exists(Path.of(marker + ".release"))) Thread.sleep(50);
  }
}
