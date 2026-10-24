package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一驱动运行准确旧新binary；事务失败注入只作用于本次私有查询，不改DDL或产品协议。 */
public final class TaskSubmitScenario {
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static String tag;

  private static Tasks.Request request(String goal, List<String> caps) {
    return new Tasks.Request(goal, caps, List.of(), new Tasks.Budget(120, 8, 20, null));
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      props.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-submit", props));
    var source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    ledger = new JdbcLedger(source, new ModelProfiles(env));
    db = new JdbcTemplate(source);
    tag = args[1];
    String key = tag + "-ordinary";
    var request = request("frozen same request", List.of("web_fetch"));
    long queued = number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'");
    var first = ledger.submit(OWNER, key, request);
    var retry = ledger.submit(OWNER, key, request);
    check(first.equals(retry), "exact idempotent View and timestamp bytes");
    check(
        number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'") == queued + 1,
        "one queue unit");
    check(
        number(
                "SELECT COUNT(*) FROM event WHERE tenant_id='tenant-a' AND task_id=?",
                first.taskId())
            == 1,
        "one accepted event");
    String frozen =
        Payloads.json(
            db.queryForMap(
                "SELECT request_snapshot,request_digest,tools_snapshot,tools_digest,model_snapshot,model_digest,max_models,max_tools,max_cost_micros,TIMESTAMPDIFF(SECOND,created_at,deadline) AS deadline_seconds FROM task WHERE tenant_id='tenant-a' AND id=?",
                first.taskId()));
    System.out.println("FROZEN " + frozen);
    result("retry", "oneQueue=true oneEvent=true sameView=true");
    var otherOwner = new Tasks.Identity("tenant-b", "bob");
    var other = ledger.submit(otherOwner, key, request);
    check(!other.taskId().equals(first.taskId()), "same key stays tenant scoped");
    ledger.cancel(otherOwner, other.taskId());
    result("isolation", "sameKeyDifferentTask=true");
    reject(
        "conflict",
        "IDEMPOTENCY_CONFLICT",
        () -> ledger.submit(OWNER, key, request("different", List.of("web_fetch"))));
    db.update(
        "UPDATE task SET request_digest=? WHERE tenant_id='tenant-a' AND id=?",
        "0".repeat(64),
        first.taskId());
    reject("digest", "SNAPSHOT_CORRUPT", () -> ledger.submit(OWNER, key, request));
    db.update(
        "UPDATE task SET request_digest=? WHERE tenant_id='tenant-a' AND id=?",
        Payloads.hash(Payloads.json(request)),
        first.taskId());
    db.update(
        "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'");
    try {
      check(
          ledger.submit(OWNER, key, request).taskId().equals(first.taskId()),
          "ordinary original retry precedes fresh pin gating");
      reject("pin", "CAPABILITY_REVOKED", () -> ledger.submit(OWNER, tag + "-revoked", request));
    } finally {
      db.update(
          "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='web_fetch'");
    }
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    try {
      reject("identity", "ACCESS_REVOKED", () -> ledger.submit(OWNER, key, request));
    } finally {
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id='tenant-a' AND actor_id='alice'");
    }
    long limit = number("SELECT queue_limit FROM tenant_budget WHERE tenant_id='tenant-a'");
    db.update("UPDATE tenant_budget SET queue_limit=queued WHERE tenant_id='tenant-a'");
    try {
      reject("queue", "QUEUE_LIMIT", () -> ledger.submit(OWNER, tag + "-full", request));
      check(ledger.submit(OWNER, key, request).equals(first), "retry survives full queue");
    } finally {
      db.update("UPDATE tenant_budget SET queue_limit=? WHERE tenant_id='tenant-a'", limit);
    }
    reject(
        "session-combination",
        "INVALID_CAPABILITIES",
        () ->
            ledger.submit(
                OWNER, tag + "-session", request("goal", List.of("session_event_search"))));
    reject(
        "directory-combination",
        "INVALID_CAPABILITIES",
        () ->
            ledger.submit(
                OWNER, tag + "-directory", request("goal", List.of("working_directory"))));
    reject(
        "todo-combination",
        "INVALID_CAPABILITIES",
        () -> ledger.submit(OWNER, tag + "-todo", request("goal", List.of("todo_write", "read"))));
    reject(
        "question-disabled",
        "QUESTION_NOT_AUTHORIZED",
        () -> ledger.submit(OWNER, tag + "-ask", request("goal", List.of("ask_user_question"))));
    reject(
        "mcp-disabled",
        "MCP_NOT_CONFIGURED",
        () -> ledger.submit(OWNER, tag + "-mcp", request("goal", List.of("list_mcp_resources"))));
    var q = new QuestionContracts();
    var descriptor = q.contract("0.1.0").descriptor();
    db.update(
        "INSERT IGNORE INTO installation(tenant_id,plugin_id,plugin_version,plugin_digest) VALUES('tenant-a','ask_user_question',?,?)",
        descriptor.version(),
        descriptor.outputSha256());
    ledger.questionsEnabled(true);
    var ask =
        ledger.submit(
            OWNER, tag + "-question", request("ask a question", List.of("ask_user_question")));
    check(
        number(
                "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND task_id=?",
                ask.taskId())
            == 3,
        "question task/storage/queue held once");
    check(
        ledger
            .submit(
                OWNER, tag + "-question", request("ask a question", List.of("ask_user_question")))
            .equals(ask),
        "question replay exact");
    result("question", "reservations=3 replay=true");
    // Meter failure on the first real newly inserted task row, after INSERT/bind and queue prework.
    var scoped = scoped(System.nanoTime() + 10_000_000_000L);
    var query = scoped.queryContext();
    boolean[] inserted = {false};
    try (var ignored =
        query.meter(
            () ->
                (label, value) -> {
                  if (label.equals("request_snapshot")) {
                    inserted[0] = true;
                    throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
                  }
                })) {
      reject(
          "late-meter-rollback",
          "SESSION_HISTORY_LIMIT",
          () -> scoped.submit(OWNER, tag + "-late", request));
    }
    check(inserted[0], "injected failure reached inserted task result");
    check(
        number(
                "SELECT COUNT(*) FROM task WHERE tenant_id='tenant-a' AND idempotency_key=?",
                tag + "-late")
            == 0,
        "late failure rolled back task/queue/reservation/event");
    var metered = scoped(System.nanoTime() + 10_000_000_000L);
    check(metered.submit(OWNER, key, request).equals(first), "explicit context same UoW retry");
    var once = metered.queryContext().resources();
    check(
        once.reads() > 0 && once.bytes() > 0 && once.operations() > 0, "physical results metered");
    metered.submit(OWNER, key, request);
    var twice = metered.queryContext().resources();
    check(
        twice.reads() == once.reads() * 2
            && twice.bytes() == once.bytes() * 2
            && twice.operations() == once.operations() * 2,
        "repeated MP/XML reads uncached and counted once");
    System.out.println("QUERY " + tag + " " + Payloads.json(once));
    var expired = scoped(System.nanoTime() - 1);
    reject(
        "deadline",
        "SESSION_QUERY_TIMEOUT",
        () -> expired.submit(OWNER, tag + "-expired", request));
    race();
    ledger.cancel(OWNER, ask.taskId());
    ledger.cancel(OWNER, first.taskId());
    System.out.println("PASS task submit " + tag);
  }

  private static JdbcLedger scoped(long end) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(ledger, end, new QueryResources(0, 0, 0));
  }

  private static void race() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var barrier = new CyclicBarrier(2);
    long queued = number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'");
    var results = new ArrayList<Tasks.View>();
    try {
      var futures = new ArrayList<java.util.concurrent.Future<Tasks.View>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  return ledger.submit(OWNER, tag + "-race", request("race", List.of("web_fetch")));
                }));
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      check(
          results.getFirst().equals(results.getLast()),
          "two connection barrier same immutable task view");
      check(
          number("SELECT queued FROM tenant_budget WHERE tenant_id='tenant-a'") == queued + 1,
          "concurrent same-key exactly one queue hold");
      check(
          number(
                  "SELECT COUNT(*) FROM reservation WHERE tenant_id='tenant-a' AND task_id=?",
                  results.getFirst().taskId())
              == 1,
          "race one reservation");
      ledger.cancel(OWNER, results.getFirst().taskId());
      result("race", "sameView=true oneQueue=true oneReservation=true");
    } finally {
      pool.shutdownNow();
      check(pool.awaitTermination(5, TimeUnit.SECONDS), "both contenders collected");
    }
  }

  private static void reject(String mode, String code, Runnable action) {
    String before = state();
    try {
      action.run();
      throw new AssertionError("accepted " + mode);
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (!(cause instanceof TaskFailure) && cause.getCause() != null) cause = cause.getCause();
      if (!(cause instanceof TaskFailure rejection)) throw failure;
      check(code.equals(rejection.code()), mode + " actual " + rejection.code());
      System.out.println("REFUSAL_WRAPPER " + mode + " " + failure.getClass().getName());
    }
    check(before.equals(state()), mode + " no durable mutation");
    result(mode, code + " rollback=true");
  }

  private static String state() {
    return Payloads.json(
        List.of(
            db.queryForList(
                "SELECT tenant_id,queued,question_tasks_held,storage_held,history_storage_held,directory_storage_held,todo_storage_held FROM tenant_budget ORDER BY tenant_id"),
            number("SELECT COUNT(*) FROM task"),
            number("SELECT COUNT(*) FROM reservation"),
            number("SELECT COUNT(*) FROM event")));
  }

  private static long number(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  private static void result(String mode, String value) {
    System.out.println("RESULT " + mode + " " + value);
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }
}
