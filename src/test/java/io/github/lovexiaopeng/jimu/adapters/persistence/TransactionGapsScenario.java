package io.github.lovexiaopeng.jimu.adapters.persistence;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Three named gaps on private MySQL. No outer fixture transaction or provider execution. */
public final class TransactionGapsScenario {
  private static final Tasks.Identity OWNER = new Tasks.Identity("tenant-a", "alice");
  private static final ModelUsage USAGE =
      new ModelUsage(10L, 5L, 15L, null, null, null, null, null);
  private static final List<Map<String, Object>> trace = new ArrayList<>();
  private static JdbcTemplate db, independent;
  private static JdbcLedger ledger;
  private static String phase = "setup";
  private static int sequence;

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      var result = new TreeMap<String, Object>();
      map.forEach((k, v) -> result.put(k.toString(), normalize(v)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(TransactionGapsScenario::normalize).toList();
    if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date)
      return value.toString();
    return value;
  }

  private static List<Map<String, Object>> errors(Throwable primary) {
    var result = new ArrayList<Map<String, Object>>();
    errors(primary, result, new java.util.IdentityHashMap<Throwable, Boolean>());
    return result;
  }

  private static void errors(
      Throwable failure,
      List<Map<String, Object>> result,
      java.util.IdentityHashMap<Throwable, Boolean> seen) {
    if (failure == null || seen.put(failure, true) != null) return;
    var item = new LinkedHashMap<String, Object>();
    item.put("type", failure.getClass().getName());
    item.put("message", failure.getMessage());
    item.put(
        "stack", java.util.Arrays.stream(failure.getStackTrace()).map(Object::toString).toList());
    result.add(item);
    if (failure instanceof SQLException sql) {
      item.put("sqlState", sql.getSQLState());
      item.put("vendor", sql.getErrorCode());
      errors(sql.getNextException(), result, seen);
    }
    errors(failure.getCause(), result, seen);
    for (Throwable suppressed : failure.getSuppressed()) errors(suppressed, result, seen);
    if (failure instanceof org.springframework.transaction.TransactionSystemException transaction)
      errors(transaction.getApplicationException(), result, seen);
  }

  private static Map<String, Object> facts(JdbcTemplate reader) {
    var all = new TreeMap<String, Object>();
    for (String table :
        reader.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",
            String.class)) {
      if (table.equals("flyway_schema_history")) continue;
      all.put(
          table,
          reader.queryForList("SELECT * FROM `" + table + "`").stream()
              .map(v -> Payloads.json(normalize(v)))
              .sorted()
              .toList());
    }
    return all;
  }

  private static Map<String, Object> replayFacts(Map<String, Object> facts) {
    var result = new TreeMap<String, Object>();
    for (var entry : facts.entrySet()) {
      var rows = new ArrayList<String>();
      for (Object raw : (List<?>) entry.getValue()) {
        var row =
            Payloads.read(raw.toString(), com.fasterxml.jackson.databind.node.ObjectNode.class);
        switch (entry.getKey()) {
          case "task" -> {
            row.remove("revision");
            row.remove("updated_at");
          }
          case "tenant_budget" -> row.remove("history_storage_held");
          case "task_session_history" -> row.remove("metadata_bytes");
          case "reservation" -> {
            if (row.path("category").asText().equals("SESSION_HISTORY_STORAGE"))
              row.remove("actual");
          }
          default -> {}
        }
        rows.add(Payloads.json(row));
      }
      rows.sort(String::compareTo);
      result.put(entry.getKey(), rows);
    }
    return result;
  }

  private static Map<String, Object> row(String table, String key, String id) {
    return independent.queryForMap(
        "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?", id);
  }

  private static JdbcLedger view(Lease lease, boolean metered) throws Exception {
    var method = JdbcLedger.class.getDeclaredMethod("queryView", Lease.class, boolean.class);
    method.setAccessible(true);
    return (JdbcLedger) method.invoke(ledger, lease, metered);
  }

  private static String submit(String key, List<String> sources) {
    return ledger
        .submitSessionSearch(
            OWNER,
            key,
            "合成账本 " + key,
            sources.stream().map(UUID::fromString).toList(),
            System.nanoTime() + 2_000_000_000L)
        .taskId();
  }

  private static Lease claim(String id) {
    String worker = UUID.randomUUID().toString();
    ledger.registerWorker(
        worker, "synthetic-ledger-only", ProcessHandle.current().pid(), 1, "private", "private");
    var lease = ledger.claim(worker).orElseThrow();
    check(id.equals(lease.taskId()), "exact live attempt and task claimed");
    return lease;
  }

  private static String request(Lease lease) {
    String context =
        lease.capabilities().contains("session_event_read") ? ledger.sessionContext(lease) : null;
    var messages = new ArrayList<WireMessage>();
    messages.add(new WireMessage("user", lease.goal(), List.of(), List.of()));
    if (context != null) messages.add(new WireMessage("user", context, List.of(), List.of()));
    String body =
        Payloads.json(
            Map.of(
                "messages",
                messages.stream()
                    .map(v -> Map.of("role", v.role(), "content", v.content()))
                    .toList()));
    var model = ledger.modelSnapshot(lease);
    return Payloads.json(
        new Checkpoint(
            2,
            model.model(),
            messages,
            lease.capabilities(),
            lease.installationEpoch(),
            model.stream(),
            ModelProfiles.digest(model),
            body,
            Payloads.hash(body)));
  }

  private static String respond(Lease lease, String step, List<Call> calls) {
    String dispatch = ledger.dispatchModel(lease, step);
    var wire =
        calls.stream()
            .map(v -> new ToolCall(v.providerId(), "function", v.tool(), v.input()))
            .toList();
    ledger.modelResponded(
        lease,
        step,
        dispatch,
        Payloads.json(new Response(2, "合成响应", wire, 15, USAGE)),
        calls,
        15,
        USAGE);
    return dispatch;
  }

  private static TaskFailure rejected(String code, Runnable work) {
    try {
      work.run();
      throw new AssertionError("Expected " + code);
    } catch (TaskFailure failure) {
      failure.printStackTrace(System.out);
      check(failure.code().equals(code), "original rejection " + code);
      return failure;
    }
  }

  private static void finalCommit() throws Exception {
    String id =
        ledger
            .submit(
                OWNER,
                "G01",
                new Tasks.Request(
                    "G01 core final prepare",
                    List.of("web_fetch"),
                    List.of(),
                    new Tasks.Budget(120, 8, 20, null)))
            .taskId();
    var lease = claim(id);
    String request = request(lease);
    var before = facts(independent);
    var active = view(lease, false);
    boolean[] reached = {false, false};
    trace.clear();
    phase = "G01";
    long original = active.queryContext().deadlineNanos();
    var logged = new ListAppender<ILoggingEvent>();
    logged.start();
    var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logger.addAppender(logged);
    var error =
        rejected(
            "SESSION_QUERY_TIMEOUT",
            () ->
                active.sessionTransaction(
                    () -> {
                      var step = active.prepare(lease, request);
                      check(
                          step != null,
                          "core prepare returned after all original final business mutations");
                      check(
                          db.queryForObject(
                                  "SELECT COUNT(*) FROM step WHERE tenant_id='tenant-a' AND task_id=?",
                                  Integer.class,
                                  id)
                              == 1,
                          "staged step visible on original transaction connection");
                      reached[0] = true;
                      TransactionSynchronizationManager.registerSynchronization(
                          new TransactionSynchronization() {
                            public int getOrder() {
                              return Integer.MIN_VALUE;
                            }

                            public void beforeCommit(boolean readOnly) {
                              reached[1] = true;
                              check(
                                  active.queryContext().deadlineNanos() == original,
                                  "absolute deadline never refreshed");
                              while (System.nanoTime() <= original)
                                LockSupport.parkNanos(
                                    Math.min(
                                        10_000_000L, Math.max(1, original - System.nanoTime())));
                              System.out.println(
                                  "BARRIER "
                                      + Payloads.json(
                                          Map.of(
                                              "originalDeadline",
                                              original,
                                              "observedNanos",
                                              System.nanoTime(),
                                              "afterBusinessReturned",
                                              reached[0])));
                            }
                          });
                      return step;
                    },
                    original));
    phase = "proof";
    logger.detachAppender(logged);
    logged.stop();
    var originalErrors = new ArrayList<>(errors(error));
    for (var event : logged.list)
      if (event.getThrowableProxy() instanceof ThrowableProxy proxy)
        originalErrors.addAll(errors(proxy.getThrowable()));
    check(reached[0] && reached[1], "actual final beforeCommit barrier reached");
    check(
        originalErrors.stream()
            .anyMatch(
                v -> v.get("stack").toString().contains("SessionQueryTransaction$1.beforeCommit")),
        "original final beforeCommit owns timeout first error");
    var after = facts(independent);
    check(
        before.equals(after), "G01 rollback restores every private table including worker_process");
    System.out.println(
        "G01 "
            + Payloads.json(
                Map.of(
                    "before", before, "after", after, "trace", trace, "errors", originalErrors)));
  }

  private static void scoped() throws Exception {
    phase = "setup";
    String source = submit("G02-source", List.of());
    var sourceLease = claim(source);
    String sourceStep = ledger.prepare(sourceLease, request(sourceLease)).id();
    respond(sourceLease, sourceStep, List.of());
    ledger.finish(sourceLease, "合法合成来源完成；无provider执行");
    ledger.quiescent(sourceLease);
    String caller = submit("G02-G03-caller", List.of(source));
    var lease = claim(caller);
    String step = ledger.prepare(lease, request(lease)).id();
    var success =
        new Call(
            "0:read-success",
            "read-success",
            "session_event_read",
            Payloads.json(Map.of("session_id", source, "seq", 0)));
    var missing =
        new Call(
            "1:read-missing",
            "read-missing",
            "session_event_read",
            Payloads.json(Map.of("session_id", source, "seq", 127)));
    respond(lease, step, List.of(success, missing));
    var pin = ledger.toolSnapshot(lease).pins().get("session_event_read");
    trace.clear();
    phase = "G02";
    var active = view(lease, true);
    String result = active.scopedOrResult(lease, step, success, pin.version(), pin.digest());
    var successful = facts(independent);
    var firstResources = active.queryContext().resources();
    phase = "G02-replay";
    var replay = view(lease, true);
    check(
        result.equals(replay.scopedOrResult(lease, step, success, pin.version(), pin.digest())),
        "same original slot returns exact committed output");
    var replayed = facts(independent);
    check(
        replayFacts(successful).equals(replayFacts(replayed)),
        "successful replay adds no execution, receipt, event or tool consumption; original fence and storage remeasurement retained");
    var invocation =
        independent.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND slot=?",
            caller,
            success.slot());
    var receipt =
        row("invocation_session_scope_query", "invocation_id", (String) invocation.get("id"));
    check(
        "SUCCEEDED".equals(invocation.get("state")) && "SUCCEEDED".equals(receipt.get("outcome")),
        "G02 successful invocation and original receipt persisted");
    check(
        lease.attemptId().equals(receipt.get("committed_attempt_id"))
            && lease.fence() == ((Number) receipt.get("committed_fence")).longValue(),
        "receipt binds original live attempt/fence");
    check(
        ((Number) row("task", "id", caller).get("tools_used")).intValue() == 1,
        "successful tool consumes exactly one original slot");
    check(
        firstResources.reads() > 0 && firstResources.bytes() > 0 && firstResources.operations() > 0,
        "actual cumulative query resources charged");
    System.out.println(
        "G02 "
            + Payloads.json(
                normalize(
                    Map.of(
                        "caller",
                        caller,
                        "source",
                        source,
                        "result",
                        result,
                        "receipt",
                        receipt,
                        "resources",
                        firstResources,
                        "replayResources",
                        replay.queryContext().resources(),
                        "facts",
                        successful,
                        "trace",
                        List.copyOf(trace),
                        "afterReplay",
                        replayed))));
    trace.clear();
    phase = "G03-failed";
    rejected(
        "SESSION_EVENT_NOT_FOUND",
        () -> ledger.scopedOrResult(lease, step, missing, pin.version(), pin.digest()));
    var failedFacts = facts(independent);
    phase = "G03-failed-replay";
    rejected(
        "SESSION_EVENT_NOT_FOUND",
        () -> ledger.scopedOrResult(lease, step, missing, pin.version(), pin.digest()));
    check(
        replayFacts(failedFacts).equals(replayFacts(facts(independent))),
        "FAILED replay preserves original execution facts and tool consumption with original fence bookkeeping");
    var failedInvocation =
        independent.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? AND slot=?",
            caller,
            missing.slot());
    var failedReceipt =
        row("invocation_session_scope_query", "invocation_id", (String) failedInvocation.get("id"));
    check(
        "FAILED".equals(failedInvocation.get("state"))
            && "FAILED".equals(failedReceipt.get("outcome")),
        "independent connection observes durable FAILED read and receipt");
    check(
        lease.attemptId().equals(failedReceipt.get("committed_attempt_id"))
            && lease.fence() == ((Number) failedReceipt.get("committed_fence")).longValue(),
        "FAILED receipt preserves attempt/fence association");
    check(
        ((Number) row("task", "id", caller).get("tools_used")).intValue() == 2,
        "success plus FAILED consume exactly two original slots");
    System.out.println(
        "G03FAILED "
            + Payloads.json(
                normalize(
                    Map.of(
                        "facts",
                        failedFacts,
                        "receipt",
                        failedReceipt,
                        "trace",
                        List.copyOf(trace)))));
    // Natural authority loss is a separate original rollback/afterCompletion path, not the 404
    // commit.
    db.update(
        "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='session_search'");
    trace.clear();
    phase = "G03-note";
    rejected(
        "SESSION_SCOPE_UNAVAILABLE",
        () -> ledger.scopedOrResult(lease, step, success, pin.version(), pin.digest()));
    var noted = row("task_session_scope", "caller_task_id", caller);
    check(
        noted.get("invalidated_at") != null,
        "independent connection observes committed afterCompletion authority-loss note");
    phase = "G03-note-repeat";
    rejected(
        "SESSION_SCOPE_UNAVAILABLE",
        () -> ledger.scopedOrResult(lease, step, success, pin.version(), pin.digest()));
    var repeated = row("task_session_scope", "caller_task_id", caller);
    check(
        noted.get("invalidated_at").equals(repeated.get("invalidated_at")),
        "original identifier preserves first invalidation timestamp");
    check(
        row("invocation_session_scope_query", "invocation_id", (String) failedInvocation.get("id"))
            .equals(failedReceipt),
        "independent note does not rewrite prior FAILED receipt");
    check(
        ((Number) row("task", "id", caller).get("tools_used")).intValue() == 2,
        "natural permission refusals add no tool consumption");
    System.out.println(
        "G03NOTE "
            + Payloads.json(
                normalize(Map.of("noted", noted, "repeated", repeated, "trace", trace))));
    phase = "cleanup";
    db.update(
        "UPDATE installation SET enabled=TRUE WHERE tenant_id='tenant-a' AND plugin_id='session_search'");
  }

  private static void coreFixture() {
    String id =
        ledger
            .submit(
                OWNER,
                "core-fixture",
                new Tasks.Request(
                    "ordinary synthetic setup",
                    List.of("web_fetch"),
                    List.of(),
                    new Tasks.Budget(120, 8, 20, null)))
            .taskId();
    var lease = claim(id);
    String step = ledger.prepare(lease, request(lease)).id();
    respond(lease, step, List.of());
    ledger.finish(lease, "synthetic setup without provider");
    ledger.quiescent(lease);
    System.out.println("PASS separate original core fixture completed before scoped validation");
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", props));
    var plain =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    independent = new JdbcTemplate(plain);
    var observed =
        new DelegatingDataSource(plain) {
          public Connection getConnection() throws SQLException {
            var raw = super.getConnection();
            int connection = ++sequence;
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object value;
                      try {
                        value = invoke(raw, m, a);
                      } catch (Throwable failure) {
                        if (List.of("commit", "rollback").contains(m.getName()))
                          trace.add(
                              Map.of(
                                  "phase",
                                  phase,
                                  "connection",
                                  connection,
                                  "operation",
                                  m.getName(),
                                  "errors",
                                  errors(failure)));
                        throw failure;
                      }
                      if (List.of("commit", "rollback").contains(m.getName()))
                        trace.add(
                            Map.of(
                                "phase",
                                phase,
                                "connection",
                                connection,
                                "operation",
                                m.getName(),
                                "stack",
                                List.of(Thread.currentThread().getStackTrace()).stream()
                                    .map(Object::toString)
                                    .toList()));
                      if (value instanceof Statement statement
                          && (m.getName().equals("prepareStatement")
                              || m.getName().equals("createStatement"))) {
                        String prepared =
                            m.getName().equals("prepareStatement")
                                ? a[0].toString().replaceAll("\\s+", " ")
                                : null;
                        var binds = new TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {
                              prepared == null ? Statement.class : PreparedStatement.class
                            },
                            (sp, sm, sa) -> {
                              if (sm.getName().startsWith("set")
                                  && sa != null
                                  && sa.length >= 2
                                  && sa[0] instanceof Integer i)
                                binds.put(i, sm.getName().equals("setNull") ? null : sa[1]);
                              Object result = invoke(statement, sm, sa);
                              if (sm.getName().startsWith("execute")) {
                                var item = new LinkedHashMap<String, Object>();
                                item.put("phase", phase);
                                item.put("connection", connection);
                                item.put("sql", prepared == null ? sa[0] : prepared);
                                item.put("binds", normalize(binds));
                                item.put("api", sm.getName());
                                if (prepared != null
                                    && prepared.contains("invalidated_at=COALESCE"))
                                  item.put(
                                      "stack",
                                      List.of(Thread.currentThread().getStackTrace()).stream()
                                          .map(Object::toString)
                                          .toList());
                                trace.add(item);
                              }
                              return result;
                            });
                      }
                      return value;
                    });
          }
        };
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(observed);
    db = new JdbcTemplate(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(true, List.of("SYNTHETIC_PRIVATE_SENTINEL"));
    ledger.priorSessionSearchEnabled(true);
    System.out.println(
        "PROVENANCE private committed synthetic ledger setup; no outer fixture transaction; no tool/provider/worker loop");
    check(
        db.queryForObject("SELECT 1", Integer.class) == 1,
        "separate read-only connection preflight; no business operation or deadline refresh");
    try {
      if (args.length < 2 || !args[1].equals("scoped-only")) finalCommit();
      else coreFixture();
      scoped();
    } catch (Throwable failure) {
      System.out.println("THROWABLE " + Payloads.json(errors(failure)));
      throw failure;
    }
    System.out.println("PASS G01 G02 G03 complete");
  }
}
