package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一驱动运行旧/新 JAR 的真实 submit/modelSnapshot；只用私有合成账本，不调用 provider。 */
public final class EvaluationBindingScenario {
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static final String MODEL = "qwen3.8-max-0902";
  private static final List<String> CAPS = List.of("web_fetch", "read", "write", "present");
  private static JdbcTemplate db;
  private static Properties properties;
  private static Object canonicalPlan;
  private static JsonNode plan;
  private static String planJson;
  private static String digest;
  private static String run;
  private static JdbcLedger ledger;
  private static int serial;

  public static void main(String[] args) throws Exception {
    properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                properties.getProperty("jimu.db.url"),
                properties.getProperty("jimu.db.user"),
                properties.getProperty("jimu.db.password")));
    // 内部 Java 值迁包不改变快照；反射只供同驱动跨旧/新 binary 调用不同返回类型的方法。
    canonicalPlan =
        EvaluationPlans.class.getMethod("plan", String.class).invoke(null, "qwen-main-v1");
    planJson = Payloads.json(canonicalPlan);
    digest = Payloads.hash(planJson);
    plan = Payloads.read(planJson, JsonNode.class);
    System.out.println("FROZEN plan " + planJson);
    String other =
        Payloads.json(
            db.queryForMap(
                "SELECT model_used,token_held,cost_held FROM tenant_budget WHERE tenant_id='tenant-b'"));
    for (String mode :
        List.of(
            "normal",
            "plan-digest",
            "noncanonical",
            "initial-cost",
            "initial-request",
            "tenant-cost",
            "tenant-token",
            "tenant-model",
            "snapshot-digest",
            "column-drift",
            "conflict",
            "selection-drift",
            "b-missing",
            "b-grades",
            "b-screening",
            "b-finalist",
            "b-short",
            "b-unknown",
            "b-normal",
            "b-snapshot",
            "race")) {
      run = "binding-" + args[1] + "-" + mode;
      db.update(
          "INSERT INTO evaluation_budget(tenant_id,id,policy_version,max_requests,max_cost_micros,plan_snapshot,plan_digest) VALUES('tenant-a',?,2,?,?,?,?)",
          run,
          plan.path("maxRequests").asInt(),
          plan.path("maxCostMicros").asLong(),
          planJson,
          digest);
      String result = exercise(mode);
      check(
          other.equals(
              Payloads.json(
                  db.queryForMap(
                      "SELECT model_used,token_held,cost_held FROM tenant_budget WHERE tenant_id='tenant-b'"))),
          "other tenant unchanged");
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM model_dispatch WHERE tenant_id='tenant-a'", Long.class)
              == 0,
          "binding decisions never dispatch or charge");
      System.out.println("RESULT " + mode + " " + result);
    }
    System.out.println("PASS shared evaluation binding " + args[1]);
  }

  private static String exercise(String mode) throws Exception {
    ledger = configured(MODEL, "W1");
    if (mode.startsWith("b-")) {
      predecessors(mode);
      ledger = configured(MODEL, "W3");
      if (!mode.equals("b-missing")) qualify(mode.equals("b-grades"));
      if (mode.equals("b-short"))
        db.update(
            "UPDATE tenant_budget SET token_limit=token_held+?-1 WHERE tenant_id='tenant-a'",
            12 * 1004096L);
      if (mode.equals("b-unknown"))
        db.update(
            "UPDATE reservation r JOIN task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id SET r.state='HELD_UNKNOWN' WHERE t.tenant_id='tenant-a' AND t.evaluation_run_id=?",
            run);
    }
    if (mode.equals("plan-digest"))
      db.update(
          "UPDATE evaluation_budget SET plan_digest=? WHERE tenant_id='tenant-a' AND id=?",
          "0".repeat(64),
          run);
    if (mode.equals("noncanonical")) {
      var changed = (ObjectNode) plan.deepCopy();
      changed.put("enabled", true);
      String json = Payloads.json(changed);
      db.update(
          "UPDATE evaluation_budget SET plan_snapshot=?,plan_digest=? WHERE tenant_id='tenant-a' AND id=?",
          json,
          Payloads.hash(json),
          run);
    }
    if (mode.equals("initial-cost"))
      db.update(
          "UPDATE evaluation_budget SET cost_held=max_cost_micros-?+1 WHERE tenant_id='tenant-a' AND id=?",
          plan.path("matrixWorstCostMicros").asLong(),
          run);
    if (mode.equals("initial-request"))
      db.update(
          "UPDATE evaluation_budget SET requests_used=1 WHERE tenant_id='tenant-a' AND id=?", run);
    if (mode.equals("tenant-cost"))
      db.update(
          "UPDATE tenant_budget SET cost_limit=cost_held+?-1 WHERE tenant_id='tenant-a'",
          plan.path("matrixWorstCostMicros").asLong());
    if (mode.equals("tenant-token"))
      db.update(
          "UPDATE tenant_budget SET token_limit=token_held+?-1 WHERE tenant_id='tenant-a'",
          plan.path("tenantTokenLimit").asLong());
    if (mode.equals("tenant-model"))
      db.update(
          "UPDATE tenant_budget SET model_limit=model_used+?-1 WHERE tenant_id='tenant-a'",
          plan.path("maxRequests").asLong());
    if (mode.equals("race")) return race();
    long count = tasks();
    String before = balances();
    String task;
    try {
      task = submit(ledger, mode.startsWith("b-") ? "W3" : "W1");
    } catch (TaskFailure failure) {
      String expected =
          mode.equals("plan-digest") || mode.equals("noncanonical")
              ? "EVALUATION_CONFIG_UNAVAILABLE"
              : mode.startsWith("b-") && !mode.equals("b-short")
                  ? "EVALUATION_PHASE_LOCKED"
                  : "EVALUATION_MATRIX_BUDGET_UNAVAILABLE";
      check(
          expected.equals(failure.code()),
          mode + " expected " + expected + " actual " + failure.code());
      check(
          tasks() == count && balances().equals(before),
          "refusal rolls back all intake balances and task insertion");
      restoreRoom();
      return failure.code() + " rollback=true";
    }
    check(
        List.of(
                "normal",
                "snapshot-digest",
                "column-drift",
                "conflict",
                "selection-drift",
                "b-normal",
                "b-snapshot")
            .contains(mode),
        "unexpected acceptance " + mode);
    if (mode.equals("normal"))
      System.out.println(
          "FROZEN binding "
              + Payloads.json(
                  db.queryForMap(
                      "SELECT evaluation_snapshot,evaluation_digest,max_models,max_tokens,max_cost_micros FROM task WHERE tenant_id='tenant-a' AND id=?",
                      task)));
    if (mode.equals("snapshot-digest"))
      db.update(
          "UPDATE task SET evaluation_digest=? WHERE tenant_id='tenant-a' AND id=?",
          "0".repeat(64),
          task);
    if (mode.equals("column-drift"))
      db.update("UPDATE task SET evaluation_sample='W2' WHERE tenant_id='tenant-a' AND id=?", task);
    if (mode.equals("conflict"))
      db.update(
          "UPDATE task SET evaluation_snapshot=JSON_SET(evaluation_snapshot,'$.planDigest',?) WHERE tenant_id='tenant-a' AND id=?",
          "d".repeat(64),
          task);
    if (mode.equals("selection-drift")) ledger = configured(MODEL, "W3");
    if (mode.equals("b-snapshot"))
      db.update(
          "UPDATE evaluation_budget SET phase_b_snapshot='{}' WHERE tenant_id='tenant-a' AND id=?",
          run);
    String worker =
        java.util
            .UUID
            .nameUUIDFromBytes(
                (run + "-worker-" + (++serial)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    ledger.registerWorker(
        worker, "private-binding", ProcessHandle.current().pid(), 1, "fixture", "fixture");
    Lease lease = ledger.claim(worker).orElseThrow();
    check(lease.taskId().equals(task), "only current test candidate");
    String result;
    before = balances();
    try {
      var snapshot = ledger.modelSnapshot(lease);
      check(
          snapshot.model().equals(MODEL) && List.of("normal", "b-normal").contains(mode),
          "only unchanged binding succeeds");
      result = "OK";
    } catch (TaskFailure failure) {
      String expected =
          mode.equals("snapshot-digest")
              ? "SNAPSHOT_CORRUPT"
              : mode.equals("b-snapshot") || mode.equals("selection-drift")
                  ? "EVALUATION_PHASE_LOCKED"
                  : "EVALUATION_CONFIG_UNAVAILABLE";
      check(
          expected.equals(failure.code()),
          mode + " snapshot expected " + expected + " actual " + failure.code());
      result = failure.code();
    }
    check(before.equals(balances()), "model binding check leaves accounting unchanged");
    // 证据完成后的私有夹具终结；故意损坏的快照不作为产品取消/恢复成功证据。
    db.update("UPDATE task SET state='CANCELLED' WHERE tenant_id='tenant-a' AND id=?", task);
    db.update(
        "UPDATE reservation SET state='RELEASED' WHERE tenant_id='tenant-a' AND task_id=?", task);
    db.update("UPDATE tenant_budget SET running=running-1 WHERE tenant_id='tenant-a'");
    restoreRoom();
    return result + " accountingUnchanged=true";
  }

  private static void predecessors(String mode) throws Exception {
    var candidates = plan.path("candidates").fieldNames();
    while (candidates.hasNext()) {
      String candidate = candidates.next();
      for (String sample : List.of("W1", "W2")) {
        if (mode.equals("b-screening") && candidate.equals(MODEL) && sample.equals("W1")) continue;
        String task = submit(configured(candidate, sample), sample);
        String state =
            mode.equals("b-finalist") && candidate.equals(MODEL) ? "FAILED" : "SUCCEEDED";
        db.update("UPDATE task SET state=? WHERE tenant_id='tenant-a' AND id=?", state, task);
        db.update(
            "UPDATE reservation SET state='RELEASED' WHERE tenant_id='tenant-a' AND task_id=?",
            task);
        db.update("UPDATE tenant_budget SET queued=queued-1 WHERE tenant_id='tenant-a'");
      }
    }
  }

  private static void qualify(boolean bad) {
    var score =
        Map.of(
            "chinese",
            18,
            "tools",
            bad ? 26 : 28,
            "sources",
            28,
            "report",
            18,
            "hardFailure",
            false);
    var assessments = new TreeMap<String, Object>();
    plan.path("candidates")
        .fieldNames()
        .forEachRemaining(
            candidate ->
                assessments.put(
                    candidate,
                    Map.of(
                        "completed",
                        true,
                        "c1Passed",
                        true,
                        "c1Chinese",
                        18,
                        "w1",
                        score,
                        "w2",
                        score,
                        "evidenceSha256",
                        "a".repeat(64))));
    var q = new TreeMap<String, Object>();
    q.put("planDigest", digest);
    q.put("materialsDigest", plan.path("materialsDigest").asText());
    q.put("assessments", assessments);
    q.put("finalists", List.of(MODEL));
    q.put("adjudicated", true);
    q.put("acceptedFinalCodeSha", "b".repeat(40));
    q.put("evidenceManifestDigest", "c".repeat(64));
    q.put("actor", "synthetic-only");
    q.put("timestamp", "2026-10-24T00:00:00Z");
    q.put("originalRunId", run);
    String json = Payloads.json(q);
    db.update(
        "UPDATE evaluation_budget SET phase_b_snapshot=?,phase_b_digest=? WHERE tenant_id='tenant-a' AND id=?",
        json,
        Payloads.hash(json),
        run);
  }

  private static JdbcLedger configured(String model, String sample) {
    var props = new Properties();
    props.putAll(properties);
    props.setProperty("jimu.model.evaluation-run-id", run);
    props.setProperty("jimu.model.evaluation-plan-ref", "qwen-main-v1");
    props.setProperty(
        "jimu.model.evaluation-phase", List.of("W1", "W2").contains(sample) ? "A" : "B");
    props.setProperty("jimu.model.evaluation-sample", sample);
    props.setProperty("jimu.model.profiles.a.model", model);
    props.setProperty("jimu.model.profiles.a.revision", model);
    props.setProperty(
        "jimu.model.profiles.a.stream", String.valueOf(List.of("W2", "W4").contains(sample)));
    var rate = plan.path("candidates").path(model);
    props.setProperty(
        "jimu.model.profiles.a.price.input-micros-per-million",
        rate.path("inputMicrosPerMillion").asText());
    props.setProperty(
        "jimu.model.profiles.a.price.output-micros-per-million",
        rate.path("outputMicrosPerMillion").asText());
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-binding", props));
    return new JdbcLedger(
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password")),
        new ModelProfiles(env));
  }

  private static String submit(JdbcLedger target, String sample) throws Exception {
    var load =
        java.util.Arrays.stream(EvaluationMaterials.class.getMethods())
            .filter(m -> m.getName().equals("load"))
            .findFirst()
            .orElseThrow();
    var material = (EvaluationMaterials.Material) load.invoke(null, canonicalPlan, sample);
    return target
        .submit(
            OWNER,
            "binding-" + java.util.UUID.randomUUID(),
            new Tasks.Request(material.goal(), CAPS, List.of(), new Tasks.Budget(120, 8, 20, null)))
        .taskId();
  }

  private static String race() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  try {
                    return submit(ledger, "W1");
                  } catch (TaskFailure failure) {
                    return failure.code();
                  }
                }));
      var results = new ArrayList<String>();
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      check(
          results.stream().filter("EVALUATION_SAMPLE_ALREADY_BOUND"::equals).count() == 1,
          "exactly one duplicate sample refusal");
      check(
          db.queryForObject(
                  "SELECT COUNT(*) FROM task WHERE tenant_id='tenant-a' AND evaluation_run_id=?",
                  Long.class,
                  run)
              == 1,
          "one immutable binding winner");
      String task =
          results.stream()
              .filter(s -> !s.equals("EVALUATION_SAMPLE_ALREADY_BOUND"))
              .findFirst()
              .orElseThrow();
      ledger.cancel(OWNER, task);
      return "oneWinner=true oneDuplicate=true";
    } finally {
      pool.shutdownNow();
      check(pool.awaitTermination(5, TimeUnit.SECONDS), "binding contenders collected");
    }
  }

  private static void restoreRoom() {
    db.update(
        "UPDATE tenant_budget SET cost_limit=2000000000,token_limit=1000000000,model_limit=1000 WHERE tenant_id='tenant-a'");
  }

  private static long tasks() {
    return db.queryForObject("SELECT COUNT(*) FROM task WHERE tenant_id='tenant-a'", Long.class);
  }

  private static String balances() {
    return Payloads.json(
            db.queryForMap(
                "SELECT queued,running,model_used,token_held,cost_held FROM tenant_budget WHERE tenant_id='tenant-a'"))
        + Payloads.json(
            db.queryForMap(
                "SELECT requests_used,cost_held,state FROM evaluation_budget WHERE tenant_id='tenant-a' AND id=?",
                run));
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
