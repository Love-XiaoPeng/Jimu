package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 真实 catalog/可用性链；原 NULL/异常及每笔外层回滚证据分存。 */
public final class ImageAvailabilityScenario {
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static DataSourceTransactionManager transactions;
  private static boolean measuring;
  private static String phase;
  private static final List<Object> timeline = new ArrayList<>();
  private static int storeChecks;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore realStore;
  private static io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore trackedStore;
  private static Map<String, io.github.lovexiaopeng.jimu.model.api.ModelSnapshot> profiles;
  private static Map<String, io.github.lovexiaopeng.jimu.model.api.ModelSnapshot> originalProfiles;
  private static String imageBase64;
  private static int injected;
  private static final List<Map<String, Object>> sql = new ArrayList<>();

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static String facts() {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "step",
            "invocation",
            "invocation_session_read",
            "invocation_session_trace",
            "invocation_session_search",
            "invocation_session_scope_query",
            "task_session_scope",
            "task_session_scope_target",
            "step_session_scope",
            "model_dispatch",
            "task_attempt",
            "reservation",
            "event",
            "task_session_history",
            "session_public_event",
            "tenant_budget",
            "actor_access",
            "installation",
            "task_image_input",
            "task_image_object")) {
      result.put(
          table,
          db
              .queryForList("SELECT * FROM " + table + " WHERE tenant_id='tenant-a' ORDER BY 1,2,3")
              .stream()
              .map(Object::toString)
              .sorted()
              .toList());
    }
    return Payloads.json(evidenceValue(result));
  }

  private static void errors(Throwable failure, String role) {
    if (failure == null) return;
    System.out.println("ERROR_ROLE " + role);
    failure.printStackTrace(System.out);
    errorChain(failure, role, new java.util.IdentityHashMap<>());
  }

  private static void errorChain(
      Throwable failure, String role, java.util.IdentityHashMap<Throwable, Boolean> seen) {
    if (failure == null || seen.put(failure, Boolean.TRUE) != null) return;
    if (failure instanceof SQLException sqlFailure) {
      System.out.println(
          "SQLERROR "
              + Payloads.json(
                  Map.of(
                      "role",
                      role,
                      "class",
                      failure.getClass().getName(),
                      "state",
                      String.valueOf(sqlFailure.getSQLState()),
                      "vendor",
                      sqlFailure.getErrorCode())));
      errorChain(sqlFailure.getNextException(), role + ".next", seen);
    }
    errorChain(failure.getCause(), role + ".cause", seen);
    for (var suppressed : failure.getSuppressed())
      errorChain(suppressed, role + ".suppressed", seen);
  }

  private static void trial(String name, Runnable setup, Supplier<String> call, String expected) {
    if (!phase.equals("all") && !List.of(phase.split(",")).contains(name)) return;
    String before = facts();
    var status = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    Throwable rollback = null;
    Throwable evidenceFailure = null;
    String raw = null;
    sql.clear();
    injected = 0;
    storeChecks = 0;
    timeline.clear();
    long started = 0;
    long elapsed = 0;
    try {
      setup.run();
      measuring = true;
      started = System.nanoTime();
      raw = call.get();
      elapsed = System.nanoTime() - started;
      System.out.println("RETURN " + name + " " + elapsed);
    } catch (Throwable failure) {
      elapsed = started == 0 ? 0 : System.nanoTime() - started;
      primary = failure;
      errors(failure, "production-or-setup-" + name);
    } finally {
      measuring = false;
      ledger.imageStorage(trackedStore);
      profiles.clear();
      profiles.putAll(originalProfiles);
      ledger.searchAvailable = () -> false;
      ledger.questionsEnabled = false;
      ledger.sessionReadEnabled = true;
      ledger.sessionSearchEnabled = true;
      ledger.sessionTraceEnabled = true;
      ledger.priorSessionSearchEnabled = true;
      try {
        transactions.rollback(status);
        System.out.println("ROLLBACK " + name);
      } catch (Throwable failure) {
        rollback = failure;
        errors(failure, "rollback-" + name);
      }
      // Persist primary and SQL evidence before any assertion or proof read can fail.
      try {
        System.out.println("SQL " + name + " " + Payloads.json(sql));
        System.out.println("TIMELINE " + name + " " + Payloads.json(timeline));
      } catch (Throwable failure) {
        evidenceFailure = failure;
        errors(failure, "sql-evidence-" + name);
      }
      System.out.println(
          "OUTCOME "
              + Payloads.json(
                  Map.of(
                      "name",
                      name,
                      "returned",
                      primary == null,
                      "elapsedNanos",
                      elapsed,
                      "primary",
                      primary == null ? "none" : primary.getClass().getName(),
                      "rollback",
                      rollback == null ? "complete" : rollback.getClass().getName())));
    }
    require(rollback == null, "UNKNOWN/rollback failure ends this private instance");
    String after = facts();
    System.out.println(
        "FACTS " + Payloads.json(Map.of("name", name, "before", before, "after", after)));
    require(before.equals(after), "all captured tenant facts restored: " + name);
    System.out.println("RESTORED " + name);
    require(evidenceFailure == null, "SQL evidence failure kept separate from saved primary");
    String code =
        primary == null
            ? "OK"
            : primary instanceof TaskFailure failure
                ? failure.getMessage()
                : primary.getClass().getName();
    if (expected.equals("SQL_FAILURE")) {
      boolean original = false;
      for (Throwable cause = primary; cause != null; cause = cause.getCause())
        if (cause instanceof SQLException sqlFailure
            && "HY000".equals(sqlFailure.getSQLState())
            && sqlFailure.getErrorCode() == 1644) original = true;
      require(original, "original single SQL failure retained: " + name);
    } else require(expected.equals(code), name + " expected " + expected + " got " + code);
    if (name.endsWith("-sql")) require(injected == 1, "one SQL fault only");
    var result = new LinkedHashMap<String, Object>();
    result.put("name", name);
    result.put("code", code);
    result.put("raw", raw);
    result.put("injected", injected);
    result.put("storeChecks", storeChecks);
    System.out.println("RESULT " + Payloads.json(result));
  }

  static Object evidenceValue(Object value) {
    if (value instanceof java.time.LocalDateTime) return bindValue(value);
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), evidenceValue(item)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(ImageAvailabilityScenario::evidenceValue).toList();
    return value;
  }

  private static Object bindValue(Object value) {
    // Evidence representation only; invoke(statement, method, args) receives unchanged JDBC args.
    if (value instanceof java.time.LocalDateTime time)
      return Map.of("javaType", value.getClass().getName(), "isoValue", time.toString());
    return value;
  }

  private static final Identity IDENTITY = new Identity("tenant-a", "alice");

  private static String available() {
    require(ledger.queryContext() == null, "unchanged nullable query");
    return Boolean.toString(ledger.imagesAvailable());
  }

  private static String admission(boolean replay) {
    var request =
        new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
            "image-availability",
            List.of("read_image"),
            List.of(),
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(60, 2, 4, null));
    var input =
        List.of(
            new io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Input(
                "inputs/images/sample.png", imageBase64));
    var accepted = ledger.submit(IDENTITY, "image-availability", request, input);
    var task =
        db.queryForMap(
            "SELECT state,record_version,media_snapshot,media_digest,next_event_seq FROM task WHERE tenant_id=? AND id=?",
            "tenant-a",
            accepted.taskId());
    var images =
        db.queryForList(
            "SELECT input_id,logical_path,raw_sha256,raw_size,raw_base64 FROM task_image_input WHERE tenant_id=? AND task_id=?",
            "tenant-a",
            accepted.taskId());
    var events =
        db.queryForList(
            "SELECT seq,event_id,fact_id,type,code,status FROM event WHERE tenant_id=? AND task_id=? ORDER BY seq",
            "tenant-a",
            accepted.taskId());
    var reservations =
        db.queryForList(
            "SELECT id,category,amount,state,actual,usage_quality FROM reservation WHERE tenant_id=? AND task_id=? ORDER BY id",
            "tenant-a",
            accepted.taskId());
    require(accepted.state().equals("QUEUED"), "new media remains queued");
    require(
        events.size() == 1
            && events.getFirst().get("type").equals("task.accepted")
            && ((Number) events.getFirst().get("seq")).longValue() == 1,
        "single reliable accepted event");
    require(
        images.size() == 1 && images.getFirst().get("raw_base64").equals(imageBase64),
        "actual request image preserved");
    require(
        Payloads.hash((String) task.get("media_snapshot")).equals(task.get("media_digest")),
        "original media snapshot hash");
    System.out.println(
        "ACCEPTED_PROOF "
            + Payloads.json(
                Map.of(
                    "taskId",
                    accepted.taskId(),
                    "task",
                    task,
                    "images",
                    images,
                    "events",
                    events,
                    "reservations",
                    reservations)));
    if (replay) {
      ledger.imageStorage(null);
      profiles.remove("image-local");
      var again = ledger.submit(IDENTITY, "image-availability", request, input);
      require(again.equals(accepted), "idempotent existing skips absent store and missing model");
      require(storeChecks == 1, "replay does not repeat store check");
    }
    var view = new LinkedHashMap<String, Object>();
    view.put("taskId", accepted.taskId());
    view.put("state", accepted.state());
    view.put("revision", accepted.revision());
    view.put("createdAt", accepted.createdAt().toString());
    view.put("updatedAt", accepted.updatedAt().toString());
    view.put("cancelRequestedAt", accepted.cancelRequestedAt());
    view.put("code", accepted.code());
    view.put("summary", accepted.summary());
    view.put("artifacts", accepted.artifacts());
    return Payloads.json(view);
  }

  private static String catalog(Identity identity) {
    require(
        ledger.queryContext() == null,
        "catalog keeps original nullable query; no session queryView");
    return Payloads.json(ledger.capabilities(identity));
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var plain =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var source =
        new DelegatingDataSource(plain) {
          public Connection getConnection() throws SQLException {
            Connection c = super.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object value = invoke(c, m, a);
                      if (value instanceof PreparedStatement statement
                          && m.getName().equals("prepareStatement")) {
                        String text = a[0].toString().replaceAll("\\s+", " ").trim();
                        var binds = new java.util.TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sv) -> {
                              if (sm.getName().startsWith("set")
                                  && sv != null
                                  && sv.length > 1
                                  && sv[0] instanceof Integer index)
                                binds.put(
                                    index,
                                    sm.getName().equals("setNull") ? null : bindValue(sv[1]));
                              if (measuring && sm.getName().startsWith("execute")) {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("text", text);
                                row.put("binds", new LinkedHashMap<>(binds));
                                sql.add(row);
                                timeline.add(row);
                              }
                              return invoke(statement, sm, sv);
                            });
                      }
                      return value;
                    });
          }
        };
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    transactions = new DataSourceTransactionManager(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    phase = args.length > 1 ? args[1] : "all";
    imageBase64 =
        java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(args[2])));
    realStore =
        new io.github.lovexiaopeng.jimu.adapters.storage.LocalArtifactStore(
            Path.of(env.getRequiredProperty("jimu.images.root")));
    trackedStore =
        (io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore)
            Proxy.newProxyInstance(
                ImageAvailabilityScenario.class.getClassLoader(),
                new Class<?>[] {io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore.class},
                (proxy, method, values) -> {
                  if (method.getName().equals("check") && measuring) {
                    storeChecks++;
                    timeline.add("store-check");
                  }
                  return invoke(realStore, method, values);
                });
    ledger.imageStorage(trackedStore);
    var settingsField = ModelProfiles.class.getDeclaredField("settings");
    settingsField.setAccessible(true);
    profiles = ((ModelProfiles.Settings) settingsField.get(ledger.models)).profiles();
    originalProfiles = new java.util.HashMap<>(profiles);
    require(!ledger.models.selected("tenant-a").paid(), "private paid=false");
    trial(
        "direct-absent",
        () -> ledger.imageStorage(null),
        ImageAvailabilityScenario::available,
        "OK");
    trial("direct-normal", () -> {}, ImageAvailabilityScenario::available, "OK");
    trial(
        "direct-file-failure",
        () ->
            ledger.imageStorage(
                (io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore)
                    Proxy.newProxyInstance(
                        ImageAvailabilityScenario.class.getClassLoader(),
                        new Class<?>[] {
                          io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore.class
                        },
                        (proxy, method, values) -> {
                          if (method.getName().equals("check")) {
                            storeChecks++;
                            timeline.add("synthetic-store-FileFailure");
                            throw new io.github.lovexiaopeng.jimu.plugin.api.FileFailure(
                                "SYNTHETIC_STORE");
                          }
                          throw new AssertionError("no store operation expected");
                        })),
        ImageAvailabilityScenario::available,
        "OK");
    trial("catalog-normal", () -> {}, () -> catalog(IDENTITY), "OK");
    trial(
        "catalog-absent-model-missing",
        () -> {
          ledger.imageStorage(null);
          profiles.remove("image-local");
        },
        () -> catalog(IDENTITY),
        "OK");
    trial(
        "catalog-present-model-missing",
        () -> profiles.remove("image-local"),
        () -> catalog(IDENTITY),
        "MODEL_CONFIG_UNAVAILABLE");
    trial(
        "catalog-no-image-model-missing",
        () -> {
          profiles.remove("image-local");
          db.update(
              "UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a' AND plugin_id='read_image'");
        },
        () -> catalog(IDENTITY),
        "OK");
    trial(
        "catalog-text-model",
        () -> profiles.put("image-local", profiles.get("text-only")),
        () -> catalog(IDENTITY),
        "OK");
    trial(
        "catalog-actor-hidden",
        () -> {},
        () -> catalog(new Identity("tenant-a", "absent")),
        "ACCESS_REVOKED");
    trial(
        "admission-absent",
        () -> ledger.imageStorage(null),
        () -> admission(false),
        "IMAGE_STORE_UNAVAILABLE");
    trial(
        "admission-model-rejected",
        () -> {
          ledger.imageStorage(null);
          profiles.put("image-local", profiles.get("text-only"));
        },
        () -> admission(false),
        "MODEL_CAPABILITY_UNSUPPORTED");
    trial("admission-accepted-replay", () -> {}, () -> admission(true), "OK");
  }
}
