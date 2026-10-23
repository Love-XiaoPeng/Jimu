package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages;
import io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageNormalizer;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real product methods, two physical connections, test-only barriers after actual InnoDB locks. */
public final class MediaLockScenario {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static final String PATH = "inputs/images/sample.png";
  private static final List<Map<String, Object>> TRACE =
      Collections.synchronizedList(new ArrayList<>());
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static ModelProfiles profiles;
  private static SecureImageStore imageStore;
  private static String tag;
  private static byte[] png;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void record(String event, Object detail) {
    TRACE.add(
        Map.of(
            "nanoTime",
            System.nanoTime(),
            "thread",
            Thread.currentThread().getName(),
            "event",
            event,
            "detail",
            detail));
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var raw =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    db = new JdbcTemplate(raw);
    profiles = new ModelProfiles(env);
    tag = args[1];
    png = Files.readAllBytes(Path.of(args[2]));
    var gates = new Gates();
    ledger = new JdbcLedger(new ObservedSource(raw, gates), profiles);
    imageStore = new SecureImageStore(Path.of(env.getRequiredProperty("jimu.images.root")));
    ledger.imageStorage(imageStore);
    try {
      for (String operation : List.of("reserve", "success")) {
        for (String first : List.of("media", "forward"))
          for (int repeat = 0; repeat < 3; repeat++) race(gates, operation, repeat, first);
      }
      refusals();
      atomicity(gates);
      controls(gates);
      System.out.println("PASS media lock " + tag);
    } finally {
      Files.writeString(
          Path.of(args[3]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(TRACE));
    }
  }

  private record Work(
      ExecutionLedger.Lease lease, String invocation, String input, ImagePort.Result result) {}

  private static Work work(String label, boolean reserved) throws Exception {
    return work(label, reserved, OWNER, true);
  }

  private static Work work(String label, boolean reserved, Tasks.Identity owner, boolean synthetic)
      throws Exception {
    String worker = "media-" + Payloads.hash(tag + ":" + label).substring(0, 30);
    ledger.registerWorker(
        worker, "private-media", ProcessHandle.current().pid(), 1, "fixture", "fixture");
    String task =
        ledger
            .submit(
                owner,
                worker,
                new Tasks.Request(
                    worker, List.of("read_image"), List.of(), new Tasks.Budget(180, 8, 20, null)),
                List.of(new TaskImages.Input(PATH, Base64.getEncoder().encodeToString(png))))
            .taskId();
    var lease = ledger.claim(worker).orElseThrow();
    check(task.equals(lease.taskId()), "exact claimed media task");
    var model = ledger.modelSnapshot(lease);
    var history =
        List.of(
            new DurableAgent.WireMessage("user", worker, List.of(), List.of()),
            new DurableAgent.WireMessage(
                "user",
                ImageMessages.directory(ledger.mediaSnapshot(lease)),
                List.of(),
                List.of()));
    var contract = new WorkspaceContracts().load("read_image");
    var definitions =
        List.of(
            ToolDefinition.builder()
                .name("read_image")
                .description(WorkspaceContracts.description("read_image"))
                .inputSchema(contract.input())
                .build());
    String body =
        new OpenAiProtocol()
            .body(
                model,
                history,
                definitions,
                ref -> {
                  throw new AssertionError("No image reference before first invocation");
                });
    String checkpoint =
        Payloads.json(
            new DurableAgent.Checkpoint(
                3,
                model.model(),
                history,
                lease.capabilities(),
                lease.installationEpoch(),
                model.stream(),
                ModelProfiles.digest(model),
                body,
                Payloads.hash(body)));
    var step = ledger.prepare(lease, checkpoint);
    String dispatch = ledger.dispatchModel(lease, step.id());
    var call =
        new ExecutionLedger.Call(
            "0:provider-0", "provider-0", "read_image", "{\"file_path\":\"" + PATH + "\"}");
    var usage = new ModelUsage(10L, 5L, 15L, null, null, null, null, null);
    ledger.modelResponded(
        lease,
        step.id(),
        dispatch,
        Payloads.json(
            new DurableAgent.Response(
                2,
                "",
                List.of(
                    new DurableAgent.ToolCall(
                        "provider-0", "function", "read_image", call.input())),
                15,
                usage)),
        List.of(call),
        15,
        usage);
    var pin = ledger.toolSnapshot(lease).pins().get("read_image");
    String invocation = ledger.admit(lease, step.id(), call, pin.version(), pin.digest()).id();
    String input = ledger.imageInput(lease, PATH).entry().inputId();
    // Explicitly synthetic admitted paid-run state. Paid vision dispatch is STILL rejected by
    // ModelImages.body. Never claim these seed writes prove reachable live paid model dispatch.
    var paid = profiles.selected("synthetic-paid");
    if (synthetic)
      db.update(
          "UPDATE task SET evaluation_run_id='media-run',model_snapshot=?,model_digest=? WHERE tenant_id=? AND id=?",
          Payloads.json(paid),
          ModelProfiles.digest(paid),
          owner.tenant(),
          task);
    if (synthetic)
      record(
          "synthetic-paid-seed",
          Map.of("task", task, "run", "media-run", "modelPaid", paid.paid()));
    String image = reserved ? ledger.reserveImage(lease, invocation, input) : "not-yet-reserved";
    var normalized = new ImageNormalizer().normalize(PATH, png, () -> {});
    var value =
        new ImagePort.Image(
            image,
            "image/png",
            Payloads.hash(normalized.png()),
            normalized.png().length,
            normalized.width(),
            normalized.height(),
            normalized.originalWidth(),
            normalized.originalHeight(),
            "jimu-image-v1");
    if (reserved) {
      String stored = imageStore.write(owner.tenant(), image, normalized.png(), value.sha256());
      check(stored.equals(image + "-" + value.sha256()), "actual private image store key");
      imageStore.read(owner.tenant(), stored, value.sha256(), value.size());
    }
    return new Work(
        lease,
        invocation,
        input,
        new ImagePort.Result(
            PATH, input, "image:" + invocation, Payloads.hash(png), png.length, value));
  }

  private static void race(Gates gates, String operation, int repeat, String first)
      throws Exception {
    String label = operation + "-" + first + "-" + repeat;
    Work media = work(label + "-media", operation.equals("success"));
    Work forward = forwardWork(label + "-forward", OWNER);
    String before = snapshot(media);
    gates.reset(first);
    var pool = Executors.newFixedThreadPool(2);
    List<Map<String, Object>> waits = new ArrayList<>();
    try {
      Callable<String> mediaCall =
          () ->
              outcome(
                  "media",
                  () -> {
                    if (operation.equals("reserve"))
                      ledger.reserveImage(media.lease(), media.invocation(), media.input());
                    else
                      ledger.imageSucceeded(
                          media.lease(), media.invocation(), key(media.result()), media.result());
                  });
      Callable<String> forwardCall =
          () -> outcome("forward", () -> ledger.checkpoint(forward.lease()));
      var lead = pool.submit(first.equals("media") ? mediaCall : forwardCall);
      check(gates.first.await(5, TimeUnit.SECONDS), "first product call acquired real lock");
      var follower = pool.submit(first.equals("media") ? forwardCall : mediaCall);
      var a = first.equals("media") ? lead : follower;
      var b = first.equals("media") ? follower : lead;
      long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < until) {
        waits =
            db.queryForList(
                "SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS requester,w.BLOCKING_ENGINE_TRANSACTION_ID AS blocker,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE()");
        if (!waits.isEmpty()) break;
        TimeUnit.MILLISECONDS.sleep(20);
      }
      check(!waits.isEmpty(), "observed actual InnoDB row wait before releasing barrier");
      var locks =
          db.queryForList(
              "SELECT ENGINE_TRANSACTION_ID,THREAD_ID,OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_STATUS,LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE()");
      record("locks-before-release", Map.of("case", label, "locks", locks));
      if (!tag.startsWith("old")) {
        Object waiting = waits.getFirst().get("requester");
        check(
            locks.stream()
                .noneMatch(
                    l ->
                        String.valueOf(l.get("ENGINE_TRANSACTION_ID"))
                                .equals(String.valueOf(waiting))
                            && "tenant_budget".equals(l.get("OBJECT_NAME"))
                            && "GRANTED".equals(l.get("LOCK_STATUS"))),
            "new evaluation waiter owns no budget lock");
      }
      record(
          "wait-before-release",
          Map.of(
              "case",
              label,
              "waits",
              waits,
              "transactions",
              db.queryForList(
                  "SELECT trx_id,trx_state,trx_mysql_thread_id,trx_query FROM information_schema.innodb_trx")));
      gates.release.countDown();
      String left = a.get(15, TimeUnit.SECONDS), right = b.get(15, TimeUnit.SECONDS);
      gates.active = false;
      boolean old = tag.startsWith("old");
      if (old && !tag.contains("normal")) {
        String code = tag.contains("timeout") ? "SQL-1205" : "SQL-1213";
        check(
            left.contains(code) || right.contains(code),
            "old reverse order reproduces " + code + ": " + left + "/" + right);
        check(
            waits.stream()
                .anyMatch(
                    w ->
                        (first.equals("media") ? "tenant_budget" : "evaluation_budget")
                            .equals(w.get("OBJECT_NAME"))),
            "old opposite lock wait observed");
      } else {
        check(
            left.equals("OK") && right.equals("OK"),
            "both product transactions complete: " + left + "/" + right);
        check(
            waits.stream().allMatch(w -> "evaluation_budget".equals(w.get("OBJECT_NAME"))),
            "new forward waits on evaluation first");
      }
      if (!left.equals("OK"))
        check(before.equals(snapshot(media)), "failed media transaction rolls back completely");
      checkAccounting(media, operation, left.equals("OK"));
      System.out.println(
          "RESULT "
              + label
              + " "
              + JSON.writeValueAsString(
                  Map.of(
                      "media",
                      left,
                      "forward",
                      right,
                      "firstLock",
                      gates.firstTable,
                      "waits",
                      waits,
                      "accounting",
                      accounting(media))));
      if (tag.contains("deadlock"))
        record("innodb-status", db.queryForList("SHOW ENGINE INNODB STATUS"));
    } finally {
      gates.release.countDown();
      gates.active = false;
      pool.shutdownNow();
      check(pool.awaitTermination(20, TimeUnit.SECONDS), "all product calls exited");
      finish(media);
      finish(forward);
    }
  }

  private static Work forwardWork(String label, Tasks.Identity owner) {
    String worker = "media-" + Payloads.hash(tag + ":" + label).substring(0, 30);
    ledger.registerWorker(
        worker, "private-media", ProcessHandle.current().pid(), 1, "fixture", "fixture");
    String task =
        ledger
            .submit(
                owner,
                worker,
                new Tasks.Request(
                    worker, List.of("web_fetch"), List.of(), new Tasks.Budget(180, 8, 20, null)))
            .taskId();
    var lease = ledger.claim(worker).orElseThrow();
    check(lease.taskId().equals(task), "exact forward task");
    var paid = profiles.selected("synthetic-paid");
    db.update(
        "UPDATE task SET evaluation_run_id='media-run',model_snapshot=?,model_digest=? WHERE tenant_id=? AND id=?",
        Payloads.json(paid),
        ModelProfiles.digest(paid),
        owner.tenant(),
        task);
    record(
        "synthetic-forward-seed",
        Map.of("tenant", owner.tenant(), "task", task, "run", "media-run"));
    return new Work(lease, "unused", "unused", null);
  }

  private static String outcome(String role, Runnable call) {
    Thread.currentThread().setName(role);
    try {
      call.run();
      return "OK";
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (cause != null) {
        if (cause instanceof SQLException sql)
          return "SQL-" + sql.getErrorCode() + "/" + sql.getSQLState();
        cause = cause.getCause();
      }
      return failure instanceof TaskFailure task ? task.code() : failure.toString();
    }
  }

  private static String key(ImagePort.Result value) {
    return value.image().imageId() + "-" + value.image().sha256();
  }

  private static Object accounting(Work work) {
    return List.of(
        db.queryForList(
            "SELECT category,state,amount,actual,usage_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY category,id",
            work.lease().tenant(),
            work.lease().taskId()),
        db.queryForList(
            "SELECT phase,size FROM task_image_object WHERE tenant_id=? AND task_id=?",
            work.lease().tenant(),
            work.lease().taskId()),
        db.queryForList(
            "SELECT state FROM invocation WHERE tenant_id=? AND id=?",
            work.lease().tenant(),
            work.invocation()),
        db.queryForList(
            "SELECT storage_held FROM tenant_budget WHERE tenant_id=?", work.lease().tenant()),
        db.queryForList(
            "SELECT revision,state,token_held FROM task WHERE tenant_id=? AND id=?",
            work.lease().tenant(),
            work.lease().taskId()),
        db.queryForList(
            "SELECT seq,type,status FROM event WHERE tenant_id=? AND task_id=? ORDER BY seq",
            work.lease().tenant(),
            work.lease().taskId()));
  }

  private static String snapshot(Work work) throws Exception {
    return JSON.writeValueAsString(accounting(work));
  }

  private static void checkAccounting(Work work, String operation, boolean success) {
    String state =
        db.queryForObject(
            "SELECT state FROM invocation WHERE tenant_id=? AND id=?",
            String.class,
            work.lease().tenant(),
            work.invocation());
    check(
        state.equals(success && operation.equals("success") ? "SUCCEEDED" : "RUNNING"),
        "atomic invocation state");
    var rows =
        db.queryForList(
            "SELECT state,amount,actual FROM reservation WHERE tenant_id=? AND task_id=? AND category='MEDIA_STORAGE'",
            work.lease().tenant(),
            work.lease().taskId());
    if (operation.equals("reserve") && !success)
      check(rows.isEmpty(), "failed reserve has zero media holds");
    else {
      check(rows.size() == 1, "exactly one media hold");
      check(
          "SETTLED".equals(rows.getFirst().get("state"))
              == (operation.equals("success") && success),
          "media settlement state");
      check(
          ((Number) rows.getFirst().get("amount")).longValue() == 1048576,
          "original bound preserved");
      if (success && operation.equals("success"))
        check(
            ((Number) rows.getFirst().get("actual")).longValue() == work.result().image().size(),
            "actual normalized bytes settled");
    }
  }

  private static void refusals() throws Exception {
    Work work = work("refusals", true);
    var lease = work.lease();
    var stale =
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
    expect(
        work,
        "FENCE_REJECTED",
        () -> ledger.imageSucceeded(stale, work.invocation(), key(work.result()), work.result()));
    expect(
        work,
        "SNAPSHOT_CORRUPT",
        () -> ledger.imageSucceeded(lease, work.invocation(), "wrong-key", work.result()));
    expect(
        work,
        "EFFECT_OUTCOME_UNKNOWN",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    expect(
        work,
        "IMAGE_UNAVAILABLE",
        () -> ledger.reserveImage(lease, work.invocation(), "missing-input"));
    long limit =
        db.queryForObject(
            "SELECT storage_limit FROM tenant_budget WHERE tenant_id='tenant-a'", Long.class);
    db.update("UPDATE tenant_budget SET storage_limit=storage_held WHERE tenant_id='tenant-a'");
    expect(
        work,
        "STORAGE_BUDGET_EXCEEDED",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    db.update("UPDATE tenant_budget SET storage_limit=? WHERE tenant_id='tenant-a'", limit);
    Object deadline =
        db.queryForObject(
            "SELECT deadline FROM task WHERE tenant_id=? AND id=?",
            Object.class,
            lease.tenant(),
            lease.taskId());
    db.update(
        "UPDATE task SET deadline='2000-01-01 00:00:00' WHERE tenant_id=? AND id=?",
        lease.tenant(),
        lease.taskId());
    expect(
        work,
        "DEADLINE_EXCEEDED",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    expect(
        work,
        "DEADLINE_EXCEEDED",
        () -> ledger.imageSucceeded(lease, work.invocation(), key(work.result()), work.result()));
    db.update(
        "UPDATE task SET deadline=? WHERE tenant_id=? AND id=?",
        deadline,
        lease.tenant(),
        lease.taskId());
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    expect(
        work, "ACCESS_REVOKED", () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    expect(
        work,
        "ACCESS_REVOKED",
        () -> ledger.imageSucceeded(lease, work.invocation(), key(work.result()), work.result()));
    db.update(
        "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    db.update(
        "UPDATE installation SET epoch=epoch+1 WHERE tenant_id='tenant-a' AND plugin_id='read_image'");
    expect(
        work,
        "CAPABILITY_REVOKED",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    expect(
        work,
        "CAPABILITY_REVOKED",
        () -> ledger.imageSucceeded(lease, work.invocation(), key(work.result()), work.result()));
    db.update(
        "UPDATE installation SET epoch=epoch-1 WHERE tenant_id='tenant-a' AND plugin_id='read_image'");
    db.update(
        "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id='tenant-a' AND id='media-run'");
    expect(
        work,
        "MODEL_BUDGET_EXCEEDED",
        () -> ledger.imageSucceeded(lease, work.invocation(), key(work.result()), work.result()));
    expect(
        work,
        "MODEL_BUDGET_EXCEEDED",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    db.update(
        "UPDATE evaluation_budget SET state='OPEN' WHERE tenant_id='tenant-a' AND id='media-run'");
    ledger.cancel(OWNER, lease.taskId());
    expect(
        work,
        "CANCEL_REQUESTED",
        () -> ledger.imageSucceeded(lease, work.invocation(), key(work.result()), work.result()));
    expect(
        work,
        "CANCEL_REQUESTED",
        () -> ledger.reserveImage(lease, work.invocation(), work.input()));
    finish(work);
    Work complete = work("duplicate-success", true);
    ledger.imageSucceeded(
        complete.lease(), complete.invocation(), key(complete.result()), complete.result());
    expect(
        complete,
        "SNAPSHOT_CORRUPT",
        () ->
            ledger.imageSucceeded(
                complete.lease(),
                complete.invocation(),
                key(complete.result()),
                complete.result()));
    finish(complete);
    System.out.println("PASS media refusal rollback and no replay " + tag);
  }

  private static void atomicity(Gates gates) throws Exception {
    Work work = work("atomicity", true);
    for (String point :
        List.of(
            "UPDATE task_image_object SET",
            "UPDATE reservation SET state='SETTLED',actual=",
            "UPDATE tenant_budget SET storage_held=storage_held-?",
            "UPDATE invocation SET state='SUCCEEDED',output_snapshot=")) {
      String before = snapshot(work);
      gates.failAfter = point;
      String result;
      try {
        result =
            outcome(
                "injection",
                () ->
                    ledger.imageSucceeded(
                        work.lease(), work.invocation(), key(work.result()), work.result()));
      } finally {
        gates.failAfter = null;
      }
      check(
          result.contains("INJECTED_MEDIA_ROLLBACK"),
          "reached exact real mutation: " + point + ": " + result);
      check(
          before.equals(snapshot(work)),
          "all media/hold/task/event writes rolled back after " + point);
      record("atomic-rollback", point);
    }
    ledger.imageSucceeded(work.lease(), work.invocation(), key(work.result()), work.result());
    checkAccounting(work, "success", true);
    finish(work);
  }

  private static void controls(Gates gates) throws Exception {
    for (boolean paid : List.of(false, true)) {
      Work serial = work("serial-" + paid, true, OWNER, paid);
      ledger.imageSucceeded(
          serial.lease(), serial.invocation(), key(serial.result()), serial.result());
      checkAccounting(serial, "success", true);
      System.out.println(
          "SERIAL "
              + paid
              + " "
              + JSON.writeValueAsString(
                  List.of(
                      db.queryForList(
                          "SELECT category,state,amount,actual,usage_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY category",
                          serial.lease().tenant(),
                          serial.lease().taskId()),
                      db.queryForList(
                          "SELECT phase,size,width,height FROM task_image_object WHERE tenant_id=? AND task_id=?",
                          serial.lease().tenant(),
                          serial.lease().taskId()),
                      db.queryForList(
                          "SELECT state FROM invocation WHERE tenant_id=? AND id=?",
                          serial.lease().tenant(),
                          serial.invocation()))));
      finish(serial);
    }
    Work media = work("tenant-control-a", false);
    Work other = forwardWork("tenant-control-b", new Tasks.Identity("tenant-b", "bob"));
    gates.reset("media");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () ->
                  outcome(
                      "media",
                      () -> ledger.reserveImage(media.lease(), media.invocation(), media.input())));
      check(gates.first.await(5, TimeUnit.SECONDS), "tenant control held first lock");
      var b = pool.submit(() -> outcome("forward", () -> ledger.checkpoint(other.lease())));
      check(
          b.get(2, TimeUnit.SECONDS).equals("OK"),
          "different tenant same run id completes while first tenant lock remains held");
      record(
          "different-tenant-control",
          db.queryForList(
              "SELECT OBJECT_NAME,LOCK_DATA,LOCK_STATUS FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=DATABASE()"));
      gates.release.countDown();
      check(a.get(10, TimeUnit.SECONDS).equals("OK"), "tenant control media complete");
    } finally {
      gates.release.countDown();
      gates.active = false;
      pool.shutdownNow();
      check(pool.awaitTermination(20, TimeUnit.SECONDS), "tenant calls exited");
      finish(media);
      finish(other);
    }
    System.out.println("PASS serial free/paid and different tenant controls " + tag);
  }

  private static void expect(Work work, String expected, Runnable action) throws Exception {
    String before = snapshot(work);
    record(
        "refusal-before",
        Map.of(
            "expected",
            expected,
            "task",
            db.queryForList(
                "SELECT id,CAST(deadline AS CHAR) AS deadline,deadline>CURRENT_TIMESTAMP(6) AS within_deadline,lease_until>CURRENT_TIMESTAMP(6) AS live FROM task WHERE tenant_id=? AND id=?",
                work.lease().tenant(),
                work.lease().taskId())));
    String actual = outcome("refusal", action);
    check(expected.equals(actual), "expected " + expected + " got " + actual);
    check(before.equals(snapshot(work)), "refusal has no accounting writes: " + expected);
    record("refusal", expected);
  }

  private static void finish(Work work) {
    ledger.stop(work.lease(), "FIXTURE_COMPLETE");
    ledger.quiescent(work.lease());
    ledger.processStopped(work.lease().worker());
  }

  private static final class Gates {
    volatile boolean active;
    volatile String firstTable;
    volatile String firstRole;
    volatile String failAfter;
    CountDownLatch first;
    CountDownLatch release;
    final AtomicBoolean held = new AtomicBoolean();

    void reset(String firstRole) {
      first = new CountDownLatch(1);
      release = new CountDownLatch(1);
      held.set(false);
      this.firstRole = firstRole;
      active = true;
    }

    void locked(String sql) throws Exception {
      if (active
          && Thread.currentThread().getName().equals(firstRole)
          && sql.contains("FOR UPDATE")
          && (sql.contains("FROM tenant_budget") || sql.contains("FROM evaluation_budget"))
          && held.compareAndSet(false, true)) {
        firstTable = sql.contains("FROM evaluation_budget") ? "evaluation_budget" : "tenant_budget";
        first.countDown();
        check(release.await(10, TimeUnit.SECONDS), "bounded test barrier");
      }
      if (failAfter != null && sql.startsWith(failAfter))
        throw new IllegalStateException("INJECTED_MEDIA_ROLLBACK");
    }
  }

  private static final class ObservedSource extends AbstractDataSource {
    private final DriverManagerDataSource raw;
    private final Gates gates;

    ObservedSource(DriverManagerDataSource raw, Gates gates) {
      this.raw = raw;
      this.gates = gates;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return wrap(raw.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      return wrap(raw.getConnection(user, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
      long id;
      try (var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
        result.next();
        id = result.getLong(1);
      }
      try (var statement = connection.createStatement()) {
        statement.execute("SET SESSION innodb_lock_wait_timeout=3");
      }
      long connectionId = id;
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  if (List.of("setAutoCommit", "commit", "rollback", "close")
                      .contains(method.getName()))
                    record(
                        "transaction",
                        Map.of(
                            "connection",
                            connectionId,
                            "method",
                            method.getName(),
                            "arguments",
                            args == null ? "" : List.of(args)));
                  Object value = method.invoke(connection, args);
                  if (method.getName().equals("prepareStatement"))
                    return statement((PreparedStatement) value, (String) args[0], connectionId);
                  return value;
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    private PreparedStatement statement(PreparedStatement statement, String sql, long id) {
      Map<Integer, Object> parameters = new LinkedHashMap<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) -> {
                try {
                  if (method.getName().startsWith("set")
                      && args != null
                      && args.length >= 2
                      && args[0] instanceof Integer index)
                    parameters.put(index, args[1] == null ? "NULL" : args[1]);
                  boolean execute = method.getName().startsWith("execute");
                  if (execute)
                    record(
                        "sql-before",
                        Map.of(
                            "connection",
                            id,
                            "sql",
                            sql,
                            "parameters",
                            new LinkedHashMap<>(parameters)));
                  Object value = method.invoke(statement, args);
                  if (execute) {
                    record("sql-after", Map.of("connection", id, "sql", sql));
                    gates.locked(sql);
                  }
                  return value;
                } catch (InvocationTargetException failure) {
                  Throwable cause = failure.getCause();
                  if (cause instanceof SQLException error)
                    record(
                        "sql-error",
                        Map.of(
                            "connection",
                            id,
                            "sql",
                            sql,
                            "code",
                            error.getErrorCode(),
                            "state",
                            error.getSQLState()));
                  throw cause;
                }
              });
    }
  }
}
