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
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Same public ledger driver runs against the accepted old and new binaries, without HTTP effects.
 */
public final class CoreSettlementScenario {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static String tag;
  private static String worker;

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
    db = new JdbcTemplate(source);
    ledger = new JdbcLedger(source, new ModelProfiles(env));
    tag = args[1];
    for (String mode :
        List.of(
            "model-null",
            "model-known",
            "model-corrupt",
            "model-missing",
            "tool-failed",
            "tool-unknown",
            "claim-recovery",
            "stop-cancel")) run(mode);
    ledger.processStopped(worker);
    check(
        db.queryForObject("SELECT stopped FROM worker_process WHERE id=?", Boolean.class, worker),
        "worker-final-receipt");
    System.out.println("PASS core settlement differential " + tag);
  }

  private static void run(String mode) throws Exception {
    worker = "core-" + tag + "-" + mode;
    ledger.registerWorker(
        worker,
        "private-core-probe",
        ProcessHandle.current().pid(),
        1,
        "private-probe",
        "private-probe");
    String task =
        ledger
            .submit(
                OWNER,
                tag + "-" + mode,
                new Tasks.Request(
                    tag + "-" + mode,
                    List.of("web_fetch"),
                    List.of(),
                    new Tasks.Budget(120, 8, 20, null)))
            .taskId();
    var lease = ledger.claim(worker).orElseThrow();
    check(task.equals(lease.taskId()), "exact-candidate");
    if (mode.equals("stop-cancel")) {
      ledger.cancel(OWNER, task);
      ledger.stop(lease, "CANCELLED");
      ledger.quiescent(lease);
    } else {
      String body = "{\"messages\":[]}";
      String request =
          JSON.writeValueAsString(
              Map.of(
                  "requestBody",
                  body,
                  "bodyDigest",
                  hash(body),
                  "configDigest",
                  ModelProfiles.digest(ledger.modelSnapshot(lease))));
      var step = ledger.prepare(lease, request);
      String dispatch = ledger.dispatchModel(lease, step.id());
      var usage = new ModelUsage(10L, 5L, 15L, null, null, null, null, null);
      if (mode.startsWith("model-")) {
        if (mode.equals("model-missing")) {
          db.update("DELETE FROM reservation WHERE tenant_id=? AND id=?", OWNER.tenant(), dispatch);
          String before = snapshot(task);
          try {
            ledger.modelFailed(lease, dispatch, "FIXTURE_PROVIDER_REJECTED", null);
            throw new AssertionError("missing reservation accepted");
          } catch (TaskFailure rejected) {
            check(
                rejected.status() == 404 && rejected.code().equals("NOT_FOUND"),
                "original-missing-reservation-error");
          }
          check(before.equals(snapshot(task)), "missing-reservation-failure-rolls-back");
          ledger.stop(lease, "FIXTURE_MISSING_RESERVATION");
        } else if (mode.equals("model-corrupt")) {
          db.update(
              "UPDATE task SET model_digest=REPEAT('f',64) WHERE tenant_id=? AND id=?",
              OWNER.tenant(),
              task);
          try {
            ledger.modelFailed(lease, dispatch, "FIXTURE_PROVIDER_REJECTED", usage);
            throw new AssertionError("corrupt model settled");
          } catch (TaskFailure rejected) {
            check(
                rejected.code().equals("MODEL_SETTLEMENT_PROOF_INVALID"), "corrupt-proof-rejected");
          }
          check(
              db.queryForObject(
                      "SELECT COUNT(*) FROM reservation WHERE tenant_id=? AND task_id=? AND category='MODEL' AND state='HELD_UNKNOWN' AND usage_quality='UNKNOWN'",
                      Long.class,
                      OWNER.tenant(),
                      task)
                  == 1,
              "deliberate-proof-rejection-commits-hold");
          ledger.stop(lease, "MODEL_SETTLEMENT_PROOF_INVALID");
        } else {
          ledger.modelFailed(
              lease,
              dispatch,
              "FIXTURE_PROVIDER_REJECTED",
              mode.equals("model-null") ? null : usage);
          String before = snapshot(task);
          try {
            ledger.modelFailed(lease, dispatch, "FIXTURE_PROVIDER_REJECTED", usage);
            throw new AssertionError("duplicate settlement accepted");
          } catch (TaskFailure rejected) {
            check(rejected.code().equals("DISPATCH_REJECTED"), "duplicate-model-rejected");
          }
          check(before.equals(snapshot(task)), "duplicate-model-no-write");
          ledger.stop(lease, "FIXTURE_PROVIDER_REJECTED");
        }
      } else {
        var call = new ExecutionLedger.Call("slot-0", "provider-0", "web_fetch", "{}");
        ledger.modelResponded(lease, step.id(), dispatch, "{}", List.of(call), 15, usage);
        var pin = ledger.toolSnapshot(lease).pins().get("web_fetch");
        var invocation = ledger.admit(lease, step.id(), call, pin.version(), pin.digest());
        var wrong =
            new ExecutionLedger.Lease(
                lease.tenant(),
                lease.actor(),
                lease.taskId(),
                lease.attemptId(),
                lease.fence() + 1,
                lease.worker(),
                lease.deadline(),
                lease.goal(),
                lease.capabilities(),
                lease.installationEpoch());
        String before = snapshot(task);
        try {
          ledger.toolFailed(wrong, invocation.id(), "FIXTURE_TOOL_FAILURE");
          throw new AssertionError("stale fence accepted");
        } catch (TaskFailure rejected) {
          check(rejected.code().equals("FENCE_REJECTED"), "old-fence-rejected");
        }
        check(before.equals(snapshot(task)), "old-fence-no-write");
        if (mode.equals("claim-recovery")) {
          db.update(
              "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=? AND id=?",
              OWNER.tenant(),
              task);
          check(
              ledger.claim("recovery-" + worker).isEmpty(),
              "expired-unknown-effect-never-reclaimed");
        } else if (mode.equals("tool-unknown")) ledger.uncertain(lease, invocation.id());
        else {
          ledger.toolFailed(lease, invocation.id(), "FIXTURE_TOOL_FAILURE");
          before = snapshot(task);
          try {
            ledger.toolFailed(lease, invocation.id(), "FIXTURE_TOOL_FAILURE");
            throw new AssertionError("duplicate tool failed accepted");
          } catch (TaskFailure rejected) {
            check(rejected.code().equals("INVOCATION_STATE"), "duplicate-tool-rejected");
          }
          check(before.equals(snapshot(task)), "duplicate-tool-no-write");
          ledger.stop(lease, "FIXTURE_TOOL_FAILURE");
        }
      }
      ledger.quiescent(lease);
    }
    String before = snapshot(task);
    ledger.quiescent(lease);
    ledger.processStopped(worker);
    check(before.equals(snapshot(task)), "repeat-receipts-release-once");
    System.out.println("RESULT " + mode + " " + snapshot(task));
  }

  private static String snapshot(String task) throws Exception {
    return JSON.writeValueAsString(
        List.of(
            db.queryForList(
                "SELECT state,code,models_used,tools_used,token_held FROM task WHERE tenant_id=? AND id=?",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT category,state,amount,actual,usage_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY category,amount,state",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,quiescent FROM task_attempt WHERE tenant_id=? AND task_id=? ORDER BY fence",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state FROM invocation WHERE tenant_id=? AND task_id=? ORDER BY ordinal",
                OWNER.tenant(),
                task),
            db.queryForList(
                "SELECT state,code FROM model_dispatch WHERE tenant_id=? AND task_id=? ORDER BY started_at,id",
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
