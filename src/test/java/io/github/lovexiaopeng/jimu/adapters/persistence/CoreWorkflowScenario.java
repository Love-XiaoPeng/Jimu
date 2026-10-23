package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Same public API driver, same private schema, sequential accepted/new binary execution. */
public final class CoreWorkflowScenario {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static final ModelUsage USAGE =
      new ModelUsage(10L, 5L, 15L, null, null, null, null, null);
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static String tag;

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      properties.load(stream);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    ledger = new JdbcLedger(source, new ModelProfiles(env));
    db = new JdbcTemplate(source);
    tag = args[1];
    for (var mode :
        List.of(
            "checkpoint-empty",
            "prepare-replay",
            "prepare-incomplete",
            "checkpoint-corrupt",
            "checkpoint-purged",
            "dispatch-corrupt",
            "dispatch-limit",
            "responded-known",
            "responded-unknown",
            "responded-invalid",
            "responded-cancel",
            "admit-binding",
            "admit-pin",
            "admit-pending",
            "admit-replay",
            "success-duplicate",
            "finish-unresolved",
            "finish-success",
            "cross-tenant",
            "prepare-race",
            "dispatch-race",
            "success-race")) run(mode);
    System.out.println("PASS core workflow differential " + tag);
  }

  private static void run(String mode) throws Exception {
    String worker = "workflow-" + tag + "-" + mode;
    ledger.registerWorker(
        worker, "private-core-workflow", ProcessHandle.current().pid(), 1, "private", "private");
    String task =
        ledger
            .submit(
                OWNER,
                tag + "-workflow-" + mode,
                new Tasks.Request(
                    tag + "-workflow-" + mode,
                    List.of("web_fetch"),
                    List.of(),
                    new Tasks.Budget(120, 8, 20, null)))
            .taskId();
    var lease = ledger.claim(worker).orElseThrow();
    check(task.equals(lease.taskId()), "exact task");
    check(ledger.renew(lease), "renew exact fence");
    String request =
        JSON.writeValueAsString(
            Map.of(
                "requestBody",
                "{\"messages\":[]}",
                "bodyDigest",
                hash("{\"messages\":[]}"),
                "configDigest",
                ModelProfiles.digest(ledger.modelSnapshot(lease))));
    if (mode.equals("checkpoint-empty")) {
      check(ledger.checkpoint(lease).isEmpty(), "empty checkpoint");
    } else if (mode.equals("prepare-race")) {
      race(
          () -> ledger.prepare(lease, request).id(),
          () -> ledger.prepare(lease, request).id(),
          false);
      check(count("step", task) == 1, "concurrent prepare one original step");
    } else {
      var step = ledger.prepare(lease, request);
      check(
          ledger.checkpoint(lease).orElseThrow().id().equals(step.id()),
          "checkpoint exact original");
      if (mode.equals("prepare-replay"))
        check(ledger.prepare(lease, request).id().equals(step.id()), "replay same step");
      else if (mode.equals("prepare-incomplete"))
        reject("CHECKPOINT_INCOMPLETE", task, () -> ledger.prepare(lease, request + " "), true);
      else if (mode.equals("checkpoint-corrupt")) {
        db.update(
            "UPDATE step SET request_digest=REPEAT('f',64) WHERE tenant_id=? AND id=?",
            OWNER.tenant(),
            step.id());
        reject("SNAPSHOT_CORRUPT", task, () -> ledger.checkpoint(lease), true);
      } else if (mode.equals("checkpoint-purged")) {
        db.update(
            "UPDATE step SET request_cleanup_state='MEDIA_PURGED' WHERE tenant_id=? AND id=?",
            OWNER.tenant(),
            step.id());
        reject("CHECKPOINT_MEDIA_PURGED", task, () -> ledger.checkpoint(lease), true);
      } else if (mode.equals("dispatch-corrupt")) {
        db.update(
            "UPDATE step SET request_snapshot=? WHERE tenant_id=? AND id=?",
            request.replace(hash("{\"messages\":[]}"), "f".repeat(64)),
            OWNER.tenant(),
            step.id());
        reject("SNAPSHOT_CORRUPT", task, () -> ledger.dispatchModel(lease, step.id()), true);
      } else if (mode.equals("dispatch-limit")) {
        db.update("UPDATE task SET max_models=0 WHERE tenant_id=? AND id=?", OWNER.tenant(), task);
        reject("MODEL_BUDGET_EXCEEDED", task, () -> ledger.dispatchModel(lease, step.id()), true);
      } else if (mode.equals("cross-tenant")) {
        var other =
            new ExecutionLedger.Lease(
                "tenant-b",
                lease.actor(),
                lease.taskId(),
                lease.attemptId(),
                lease.fence(),
                lease.worker(),
                lease.deadline(),
                lease.goal(),
                lease.capabilities(),
                lease.installationEpoch());
        reject("NOT_FOUND", task, () -> ledger.prepare(other, request), true);
      } else if (mode.equals("dispatch-race")) {
        race(
            () -> ledger.dispatchModel(lease, step.id()),
            () -> ledger.dispatchModel(lease, step.id()),
            false);
        check(count("model_dispatch", task) == 2, "free model retains original two-dispatch limit");
        reject("MODEL_BUDGET_EXCEEDED", task, () -> ledger.dispatchModel(lease, step.id()), true);
      } else {
        String dispatch = ledger.dispatchModel(lease, step.id());
        var call = new ExecutionLedger.Call("slot-0", "provider-0", "web_fetch", "{}");
        if (mode.equals("responded-cancel")) {
          var pin = ledger.toolSnapshot(lease).pins().get("web_fetch");
          ledger.cancel(OWNER, task);
          // A completed ordinary response still records usage and intent after cancellation.
          // The next effect admission, rather than settlement, rejects the cancellation.
          ledger.modelResponded(lease, step.id(), dispatch, "{}", List.of(call), 15, USAGE);
          check(count("invocation", task) == 1, "cancelled response retains settled intent");
          reject(
              "CANCEL_REQUESTED",
              task,
              () -> ledger.admit(lease, step.id(), call, pin.version(), pin.digest()),
              true);
        } else if (mode.equals("responded-invalid")) {
          var invalid =
              new ExecutionLedger.Call("slot-0", "provider-0", "web_fetch", "x".repeat(65536));
          reject(
              "TOOL_INPUT_INVALID",
              task,
              () -> {
                ledger.modelResponded(
                    lease, step.id(), dispatch, "{}", List.of(invalid), 15, USAGE);
                return null;
              },
              false);
          check(
              count("invocation", task) == 0, "invalid response commits usage without tool intent");
        } else {
          boolean noCalls = mode.startsWith("responded-") || mode.equals("finish-success");
          ledger.modelResponded(
              lease,
              step.id(),
              dispatch,
              "{}",
              noCalls ? List.of() : List.of(call),
              mode.equals("responded-unknown") ? null : 15,
              USAGE);
          if (mode.startsWith("responded-")) {
            reject(
                "DISPATCH_REJECTED",
                task,
                () -> {
                  ledger.modelResponded(lease, step.id(), dispatch, "{}", List.of(), 15, USAGE);
                  return null;
                },
                true);
          } else if (mode.equals("finish-success")) ledger.finish(lease, "finished");
          else if (mode.equals("finish-unresolved"))
            reject(
                "UNRESOLVED_FACTS",
                task,
                () -> {
                  ledger.finish(lease, "unfinished");
                  return null;
                },
                true);
          else {
            var pin = ledger.toolSnapshot(lease).pins().get("web_fetch");
            if (mode.equals("admit-binding"))
              reject(
                  "INVOCATION_MISMATCH",
                  task,
                  () ->
                      ledger.admit(
                          lease,
                          step.id(),
                          new ExecutionLedger.Call("slot-0", "different", "web_fetch", "{}"),
                          pin.version(),
                          pin.digest()),
                  true);
            else if (mode.equals("admit-pin"))
              reject(
                  "PLUGIN_PIN_MISMATCH",
                  task,
                  () -> ledger.admit(lease, step.id(), call, "wrong", pin.digest()),
                  true);
            else {
              var invocation = ledger.admit(lease, step.id(), call, pin.version(), pin.digest());
              if (mode.equals("admit-pending"))
                reject(
                    "EFFECT_OUTCOME_UNKNOWN",
                    task,
                    () -> ledger.admit(lease, step.id(), call, pin.version(), pin.digest()),
                    true);
              else if (mode.equals("success-race")) {
                race(
                    () -> {
                      ledger.toolSucceeded(lease, invocation.id(), "{}");
                      return "committed";
                    },
                    () -> {
                      ledger.toolSucceeded(lease, invocation.id(), "{}");
                      return "committed";
                    },
                    true);
              } else {
                ledger.toolSucceeded(lease, invocation.id(), "{}");
                if (mode.equals("admit-replay"))
                  check(
                      ledger
                          .admit(lease, step.id(), call, pin.version(), pin.digest())
                          .output()
                          .equals("{}"),
                      "confirmed output replays without effect");
                if (mode.equals("success-duplicate"))
                  reject(
                      "INVOCATION_STATE",
                      task,
                      () -> {
                        ledger.toolSucceeded(lease, invocation.id(), "{}");
                        return null;
                      },
                      true);
              }
            }
          }
        }
      }
    }
    String state =
        db.queryForObject(
            "SELECT state FROM task WHERE tenant_id=? AND id=?",
            String.class,
            OWNER.tenant(),
            task);
    if (!state.equals("SUCCEEDED")) ledger.stop(lease, "FIXTURE_DONE");
    ledger.quiescent(lease);
    ledger.processStopped(worker);
    System.out.println("RESULT " + mode + " " + snapshot(task));
  }

  private static long count(String table, String task) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND task_id=?",
        Long.class,
        OWNER.tenant(),
        task);
  }

  private static void race(Callable<String> first, Callable<String> second, boolean oneRejection)
      throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<String> wrapFirst =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              return first.call();
            } catch (TaskFailure failure) {
              return failure.code();
            }
          };
      Callable<String> wrapSecond =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              return second.call();
            } catch (TaskFailure failure) {
              return failure.code();
            }
          };
      var a = executor.submit(wrapFirst);
      var b = executor.submit(wrapSecond);
      var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
      if (oneRejection)
        check(
            results.stream().filter("INVOCATION_STATE"::equals).count() == 1
                && results.stream().filter("committed"::equals).count() == 1,
            "concurrent success one commit one CAS rejection");
      else
        check(
            results.stream().noneMatch(value -> value.matches("[A-Z_]+")),
            "concurrent admissions preserve original result");
    }
  }

  private static void reject(String expected, String task, Callable<?> action, boolean rollback)
      throws Exception {
    String before = snapshot(task);
    try {
      action.call();
      throw new AssertionError("accepted " + expected);
    } catch (TaskFailure failure) {
      check(failure.code().equals(expected), "expected " + expected + " got " + failure.code());
    }
    if (rollback)
      check(before.equals(snapshot(task)), "rejection leaves exact task facts unchanged");
  }

  private static String snapshot(String task) throws Exception {
    return JSON.writeValueAsString(
        List.of(
            db.queryForList(
                "SELECT state,code,models_used,tools_used,token_held,summary FROM task WHERE tenant_id=? AND id=?",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT category,state,amount,actual,usage_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY category,amount,state",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT ordinal,state,dispatches,response_digest FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT ordinal,slot,state,output_digest FROM invocation WHERE tenant_id=? AND task_id=? ORDER BY ordinal",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,code FROM model_dispatch WHERE tenant_id=? AND task_id=? ORDER BY started_at,id",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT type,code FROM event WHERE tenant_id=? AND task_id=? ORDER BY seq",
                OWNER.tenant(),
                task)));
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }
}
