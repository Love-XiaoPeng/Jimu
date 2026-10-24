package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一驱动准确旧新制品：产品入口、真实MySQL、账目和两连接有界等待。 */
public final class TaskOperationsScenario {
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static String tag;

  private static Tasks.View submit(String name) {
    return ledger.submit(
        OWNER,
        tag + "-" + name,
        new Tasks.Request(
            "task operations " + name,
            List.of("web_fetch"),
            List.of(),
            new Tasks.Budget(120, 8, 20, null)));
  }

  private static void require(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }

  private static long number(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  private static void result(String label, String value) {
    System.out.println("RESULT " + label + " " + value);
  }

  private static void reject(String code, Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("accepted " + code);
    } catch (RuntimeException ex) {
      Throwable cause = ex;
      while (!(cause instanceof TaskFailure) && cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof TaskFailure failure)) throw ex;
      require(failure.code().equals(code), "actual refusal " + code);
    }
  }

  private static JdbcLedger scoped(long end) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(ledger, end, new QueryResources(0, 0, 0));
  }

  private static String account(String task) {
    return Payloads.json(
        List.of(
            db.queryForMap(
                "SELECT state,revision,cancel_requested_at,next_event_seq,current_attempt_id,fence,cost_held FROM task WHERE tenant_id='tenant-a' AND id=?",
                task),
            db.queryForList(
                "SELECT id,state,actual,usage_quality FROM reservation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY id",
                task),
            db.queryForList(
                "SELECT fact_id,type,code,status FROM event WHERE tenant_id='tenant-a' AND task_id=? ORDER BY seq",
                task),
            db.queryForMap(
                "SELECT queued,running,question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'")));
  }

  public static void main(String[] args) throws Exception {
    var p = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      p.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-task-operations", p));
    var source =
        new DriverManagerDataSource(
            p.getProperty("jimu.db.url"),
            p.getProperty("jimu.db.user"),
            p.getProperty("jimu.db.password"));
    ledger = new JdbcLedger(source, new ModelProfiles(env));
    db = new JdbcTemplate(source);
    tag = args[1];
    if (tag.equals("fixed-meter")) {
      // Both binaries observe this exact already-published task before either mutating scenario.
      // Independent scenario runs legitimately leave retained QUESTION_STORAGE in the tenant.
      var measured = scoped(System.nanoTime() + 10_000_000_000L);
      var view = measured.get(OWNER, args[3]);
      require(view.artifacts().size() == 1, "actual published artifact projection");
      var first = measured.queryContext().resources();
      require(view.equals(measured.get(OWNER, args[3])), "fixed public view replay");
      var second = measured.queryContext().resources();
      require(
          second.reads() == 2 * first.reads()
              && second.bytes() == 2 * first.bytes()
              && second.operations() == 2 * first.operations(),
          "fixed get meter counted once");
      System.out.println("QUERY fixed-get " + Payloads.json(first));
      measured = scoped(System.nanoTime() + 10_000_000_000L);
      var page = measured.events(OWNER, args[3], 0, 64);
      first = measured.queryContext().resources();
      require(page.equals(measured.events(OWNER, args[3], 0, 64)), "fixed public event page");
      second = measured.queryContext().resources();
      require(
          second.reads() == 2 * first.reads()
              && second.bytes() == 2 * first.bytes()
              && second.operations() == 2 * first.operations(),
          "fixed events meter counted once");
      System.out.println("QUERY fixed-events " + Payloads.json(first));
      System.out.println("PUBLIC_VIEW " + view);
      System.out.println("PUBLIC_EVENTS " + page);
      return;
    }
    if (tag.equals("scope-refusal")) {
      String before = account(args[3]);
      require(
          number(
                  "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND invalidated_at IS NULL",
                  args[3])
              == 1,
          "direct events starts with non-invalidated scope");
      reject("ACCESS_REVOKED", () -> ledger.events(OWNER, args[3], 0, 64));
      require(
          number(
                  "SELECT COUNT(*) FROM task_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=? AND invalidated_at IS NOT NULL",
                  args[3])
              == 1,
          "direct events refusal afterCompletion persists scope invalidation");
      require(
          before.equals(account(args[3])),
          "direct events refusal rolls back task/event/accounting");
      System.out.println("PASS direct scope events rollback");
      return;
    }
    var task = submit("queued");
    String id = task.taskId();
    require(task.equals(ledger.get(OWNER, id)), "get exact view");
    require(ledger.events(OWNER, id, 0, 1000).size() == 1, "accepted event only");
    result("get-event", "sameView=true count=1");
    for (var identity :
        List.of(new Tasks.Identity("tenant-b", "bob"), new Tasks.Identity("tenant-a", "carol"))) {
      reject("NOT_FOUND", () -> ledger.get(identity, id));
      reject("NOT_FOUND", () -> ledger.cancel(identity, id));
      reject("NOT_FOUND", () -> ledger.events(identity, id, 0, 64));
    }
    result("ownership", "get/cancel/events=404");
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    try {
      reject("ACCESS_REVOKED", () -> ledger.get(OWNER, id));
      reject("ACCESS_REVOKED", () -> ledger.cancel(OWNER, id));
      reject("ACCESS_REVOKED", () -> ledger.events(OWNER, id, 0, 64));
    } finally {
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    }
    result("actor", "get/cancel/events=refused");
    db.update(
        "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'");
    try {
      reject("CAPABILITY_REVOKED", () -> ledger.get(OWNER, id));
      reject("CAPABILITY_REVOKED", () -> ledger.events(OWNER, id, 0, 64));
    } finally {
      db.update(
          "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'");
    }
    result("capability", "get/events=refused");
    var metered = scoped(System.nanoTime() + 10_000_000_000L);
    metered.get(OWNER, id);
    var once = metered.queryContext().resources();
    metered.get(OWNER, id);
    var twice = metered.queryContext().resources();
    require(
        once.reads() > 0
            && twice.reads() == 2 * once.reads()
            && twice.bytes() == 2 * once.bytes()
            && twice.operations() == 2 * once.operations(),
        "get physical results counted once without cache");
    System.out.println("QUERY get " + Payloads.json(once));
    metered = scoped(System.nanoTime() + 10_000_000_000L);
    metered.events(OWNER, id, 0, 64);
    once = metered.queryContext().resources();
    metered.events(OWNER, id, 0, 64);
    twice = metered.queryContext().resources();
    require(
        twice.reads() == 2 * once.reads()
            && twice.bytes() == 2 * once.bytes()
            && twice.operations() == 2 * once.operations(),
        "events physical results counted once");
    System.out.println("QUERY events " + Payloads.json(once));
    var expired = scoped(System.nanoTime() - 1);
    reject("SESSION_QUERY_TIMEOUT", () -> expired.get(OWNER, id));
    reject("SESSION_QUERY_TIMEOUT", () -> expired.cancel(OWNER, id));
    reject("SESSION_QUERY_TIMEOUT", () -> expired.events(OWNER, id, 0, 64));
    result("deadline", "all=expired");
    var rollback = scoped(System.nanoTime() + 10_000_000_000L);
    String original = account(id);
    boolean[] hit = {false};
    try (var ignored =
        rollback
            .queryContext()
            .meter(
                () ->
                    (label, value) -> {
                      if (label.equals("cancel_requested_at") && value != null) {
                        hit[0] = true;
                        throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
                      }
                    })) {
      reject("SESSION_HISTORY_LIMIT", () -> rollback.cancel(OWNER, id));
    }
    require(
        hit[0] && original.equals(account(id)),
        "late cancellation reread failure rolls back state/budget/reservation/events");
    result("rollback", "lateResult=true allAtomic=true");
    long queue = number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'");
    var cancelled = ledger.cancel(OWNER, id);
    require(
        cancelled.state().equals("CANCELLED") && cancelled.cancelRequestedAt() != null,
        "queued cancel terminal");
    require(cancelled.equals(ledger.cancel(OWNER, id)), "cancel idempotent exact view");
    require(
        number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'") == queue - 1,
        "queue release exactly once");
    var events = ledger.events(OWNER, id, 0, 64);
    require(
        events.stream()
            .map(Tasks.Event::type)
            .toList()
            .equals(List.of("task.accepted", "task.cancel_requested", "task.cancelled")),
        "ordered public events");
    require(
        ledger.events(OWNER, id, events.get(0).seq(), 1).getFirst().equals(events.get(1)),
        "exclusive cursor and bound");
    reject("FUTURE_CURSOR", () -> ledger.events(OWNER, id, cancelled.revision() + 100, 64));
    db.update(
        "UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE tenant_id='tenant-a' AND task_id=? AND seq=1",
        id);
    reject("CURSOR_EXPIRED", () -> ledger.events(OWNER, id, 0, 64));
    require(ledger.events(OWNER, id, 1, 64).size() == 2, "cursor oldest minus one accepted");
    db.update(
        "UPDATE event SET occurred_at=CURRENT_TIMESTAMP(6)-INTERVAL 8 DAY WHERE tenant_id='tenant-a' AND task_id=?",
        id);
    reject("CURSOR_EXPIRED", () -> ledger.events(OWNER, id, 3, 64));
    result("cancel-cursor", "queueOnce=true idempotent=true order=true future=400 retention=410");
    // A live product claim and its exact fence: cancellation may not prematurely free the run.
    var running = submit("running");
    var lease = ledger.claim("task-operations-" + tag).orElseThrow();
    require(lease.taskId().equals(running.taskId()), "actual claim selected intended task");
    var requested = ledger.cancel(OWNER, running.taskId());
    require(requested.state().equals("CANCEL_REQUESTED"), "active task gets intent only");
    require(
        number("SELECT fence FROM task WHERE tenant_id='tenant-a' AND id=?", running.taskId())
            == lease.fence(),
        "cancel preserves fence");
    require(
        number(
                "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND id=? AND state='RESERVED'",
                running.taskId() + ":run")
            == 1,
        "cancel keeps active run hold");
    // Source fixture writes UNKNOWN only to reach a terminal active-writer branch; product cancel
    // performs no replay.
    db.update(
        "UPDATE task SET state='NEEDS_RECONCILIATION',cancel_requested_at=NULL WHERE tenant_id='tenant-a' AND id=?",
        running.taskId());
    long revision =
        number("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", running.taskId());
    require(
        ledger.cancel(OWNER, running.taskId()).state().equals("NEEDS_RECONCILIATION"),
        "UNKNOWN outcome unchanged");
    require(
        number("SELECT revision FROM task WHERE tenant_id='tenant-a' AND id=?", running.taskId())
            == revision,
        "existing cancel fact not duplicated");
    ledger.processStopped(
        "task-operations-" + tag); // actual recovery settlement, never replays provider effects.
    result(
        "running-unknown",
        "intent=true fenceUnchanged=true activeRunHeld=true unknownOutcome=true");
    // Unrelated paid balances must survive ordinary cancellation unchanged.
    var question = new QuestionContracts();
    var descriptor = question.contract("0.1.0").descriptor();
    db.update(
        "INSERT IGNORE INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','ask_user_question',?,?)",
        descriptor.version(),
        descriptor.outputSha256());
    ledger.questionsEnabled(true);
    var ask =
        ledger.submit(
            OWNER,
            tag + "-question",
            new Tasks.Request(
                "ask",
                List.of("ask_user_question"),
                List.of(),
                new Tasks.Budget(120, 8, 20, null)));
    long held = number("SELECT question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'");
    ledger.cancel(OWNER, ask.taskId());
    ledger.cancel(OWNER, ask.taskId());
    require(
        number("SELECT question_tasks_held FROM tenant_budget WHERE tenant_id='tenant-a'")
            == held - 1,
        "question task hold released once");
    result("question", "queuedCancelHoldOnce=true");
    // Two connections: blocker owns budget; product cancel must wait until rollback releases it.
    var wait = submit("wait");
    Path barrier = Path.of(args[2]);
    Files.createDirectories(barrier);
    try (var pool = Executors.newSingleThreadExecutor();
        var c = source.getConnection()) {
      c.setAutoCommit(false);
      try (var st = c.createStatement()) {
        st.executeQuery("SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE")
            .close();
      }
      var pending = pool.submit(() -> ledger.cancel(OWNER, wait.taskId()));
      Files.writeString(barrier.resolve("waiting"), wait.taskId());
      long end = System.nanoTime() + 10_000_000_000L;
      while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end) Thread.sleep(20);
      require(
          Files.exists(barrier.resolve("release")) && !pending.isDone(),
          "bounded actual budget lock barrier");
      c.rollback();
      require(
          pending.get(15, TimeUnit.SECONDS).state().equals("CANCELLED"),
          "lock release then normal product cancellation");
    }
    result("lock", "actualBudgetWait=true normalCompletion=true");
    System.out.println("PASS task operations " + tag);
  }
}
