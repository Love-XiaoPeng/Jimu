package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
import java.util.function.Supplier;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 报告两公开入口的有界旧新对照；合法已存调用，外层 rollback，无 Host/provider 执行。 */
public final class ReportPublicationScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;
  private static List<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile> files;
  private static List<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload> uploads;
  private static List<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication>
      publications;
  private static final List<String> compositions = new ArrayList<>();
  private static String output;

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static Statement statement(Statement actual, String text, int connection) {
    var binds = new LinkedHashMap<String, Object>();
    var setters = new ArrayList<String>();
    var getters = new ArrayList<Map<String, Object>>();
    return (Statement)
        Proxy.newProxyInstance(
            ReportPublicationScenario.class.getClassLoader(),
            new Class<?>[] {text == null ? Statement.class : PreparedStatement.class},
            (p, m, a) -> {
              if (m.getName().startsWith("set")
                  && a != null
                  && a.length >= 2
                  && a[0] instanceof Integer) {
                setters.add(m.getName());
                binds.put(a[0].toString(), m.getName().equals("setNull") ? null : a[1]);
              }
              if (measuring && m.getName().startsWith("execute")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("connection", connection);
                row.put("statement", text == null ? "Statement" : "PreparedStatement");
                row.put("execute", m.getName());
                row.put(
                    "sql", (text == null ? a[0].toString() : text).replaceAll("\\s+", " ").strip());
                row.put("binds", new LinkedHashMap<>(binds));
                row.put("setters", new ArrayList<>(setters));
                row.put("getters", getters);
                sql.add(row);
              }
              Object value = invoke(actual, m, a);
              if (value instanceof java.sql.ResultSet result)
                return Proxy.newProxyInstance(
                    ReportPublicationScenario.class.getClassLoader(),
                    new Class<?>[] {java.sql.ResultSet.class},
                    (rp, rm, ra) -> {
                      Object item = invoke(result, rm, ra);
                      if (measuring
                          && rm.getName().startsWith("get")
                          && ra != null
                          && ra.length == 1)
                        getters.add(
                            Map.of(
                                "method",
                                rm.getName(),
                                "column",
                                ra[0].toString(),
                                "type",
                                item == null ? "NULL" : item.getClass().getName()));
                      return item;
                    });
              return value;
            });
  }

  private static Map<String, Object> taskRow() {
    return db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
  }

  private static Map<String, Object> proof() {
    var out = new LinkedHashMap<String, Object>();
    out.put("task", taskRow());
    for (String table : List.of("artifact", "reservation", "event", "invocation", "task_attempt"))
      out.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND task_id=?", task));
    out.put("budget", db.queryForMap("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'"));
    // Raw JDBC types/NULL/full columns are retained in toString; Jackson is evidence only.
    return out;
  }

  private static String facts() {
    var out = new LinkedHashMap<String, Object>();
    for (String table : db.queryForList("SHOW TABLES", String.class))
      out.put(
          table,
          db.queryForList("SELECT * FROM `" + table + "`").stream()
              .map(Object::toString)
              .sorted()
              .toList());
    return Payloads.json(out);
  }

  private static Object evidenceValue(Object value) {
    if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    if (value instanceof Map<?, ?> map) {
      var converted = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> converted.put(key.toString(), evidenceValue(item)));
      return converted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(ReportPublicationScenario::evidenceValue).toList();
    return value;
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    var before = proof();
    Object result = null;
    Throwable primary = null;
    measuring = true;
    try {
      result = work.get();
    } catch (Throwable failure) {
      primary = failure;
    } finally {
      measuring = false;
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    out.put("before", before);
    out.put("after", proof());
    out.put("result", result);
    out.put("sql", new ArrayList<>(sql));
    out.put(
        "code",
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName());
    System.out.println("CALL " + Payloads.json(evidenceValue(out)));
    if (primary != null) primary.printStackTrace(System.out);
    require(
        expected.equals(out.get("code")),
        name + " expected " + expected + " got " + out.get("code"));
  }

  private static void setup() {
    ledger.registerWorker(
        "report-private", "report-private", ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim("report-private").orElseThrow();
    require(task.equals(lease.taskId()), "exact admitted task");
    var row = taskRow();
    var attempt =
        db.queryForMap(
            "SELECT * FROM task_attempt WHERE tenant_id=? AND id=?",
            lease.tenant(),
            lease.attemptId());
    require(
        "RUNNING".equals(row.get("state")) && "ACTIVE".equals(attempt.get("state")),
        "RUNNING/ACTIVE");
    require(
        lease.attemptId().equals(row.get("current_attempt_id"))
            && lease.worker().equals(row.get("worker_id"))
            && lease.fence() == ((Number) row.get("fence")).longValue(),
        "complete lease keys");
    require(
        db.queryForObject(
            "SELECT lease_until>CURRENT_TIMESTAMP(6) FROM task WHERE tenant_id=? AND id=?",
            Boolean.class,
            lease.tenant(),
            task),
        "live lease");
    System.out.println(
        "ACTIVE "
            + Payloads.json(
                evidenceValue(Map.of("task", row, "attempt", attempt, "lease", lease.toString()))));
    // 合法已存 fetch/compose/present 快照；不声称执行了插件、Host 或模型。
    step = JdbcLedger.id();
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest) VALUES(?,?,?,?,1,'PREPARED','{}',?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        Payloads.hash("{}"));
    // 复用 ReportComposerTest 已验过的来源正文，满足原32 code point最小长度。
    String url = "https://fixture.example/source",
        quote = "中文资料包含 `https://api.example.test/anthropic`，端点仅作标识。";
    String fetched =
        Payloads.json(
            new io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput(
                200,
                url,
                "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + url + "\n\n原文开始。 " + quote,
                false));
    addInvocation("web_fetch", 1, "SUCCEEDED", "{}", fetched);
    var segment =
        new io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Segment(
            "text", "受控摘要", null, null, url, quote);
    var draft =
        new io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Draft(
            1, "本地报告", List.of(segment), List.of(segment), List.of("合成输入。"));
    var composer = new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer();
    var reportFiles =
        new ArrayList<io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile>();
    for (int index = 0; index < 2; index++) {
      String path = "reports/" + index + ".md";
      var composed = composer.compose(path, draft, ledger.reportContext(lease));
      long size = composed.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      String hash = Payloads.hash(composed.markdown());
      var receipt =
          composed.receipt(
              new io.github.lovexiaopeng.jimu.plugin.api.FilePort.Written(path, 1, hash, size));
      String raw =
          Payloads.json(
              new io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ComposeReportPlugin.Input(
                  path, draft));
      compositions.add(
          addInvocation("compose_report", index + 2, "SUCCEEDED", raw, Payloads.json(receipt)));
      reportFiles.add(
          new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile(
              path, 1, hash, size, "text/markdown; charset=utf-8"));
    }
    files = List.copyOf(reportFiles);
    String input =
        Payloads.json(
            Map.of(
                "files",
                files.stream()
                    .map(file -> Map.of("path", file.path(), "description", "报告"))
                    .toList()));
    invocation = addInvocation("present", 4, "RUNNING", input, null);
    ledger.reserve(lease.tenant(), task, invocation, null, "TOOL", 1, "RESERVED", "KNOWN");
  }

  private static String addInvocation(
      String tool, int ordinal, String state, String input, String output) {
    String id = JdbcLedger.id();
    var pin = ledger.tools(taskRow()).pins().get(tool);
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        lease.tenant(),
        id,
        task,
        step,
        lease.attemptId(),
        ordinal,
        "fixture-" + ordinal,
        "fixture-provider-" + ordinal,
        JdbcLedger.id(),
        tool,
        pin.version(),
        pin.digest(),
        lease.installationEpoch(),
        input,
        Payloads.hash(input),
        state,
        output,
        output == null ? null : Payloads.hash(output));
    return id;
  }

  private static Object present(String value) {
    ledger.presentSucceeded(lease, invocation, publications, value);
    return null;
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-publication", props));
    var plain =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    var observed =
        new DelegatingDataSource(plain) {
          int sequence;

          public Connection getConnection() throws SQLException {
            var raw = super.getConnection();
            int connection = ++sequence;
            return (Connection)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      Object result = invoke(raw, m, a);
                      if (result instanceof Statement s
                          && (m.getName().equals("prepareStatement")
                              || m.getName().equals("createStatement")))
                        return statement(
                            s,
                            m.getName().equals("prepareStatement") ? a[0].toString() : null,
                            connection);
                      return result;
                    });
          }
        };
    var source =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(observed);
    db = new JdbcTemplate(source);
    ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    task = args[1];
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {
      setup();
      call(
          "stage-empty",
          () -> ledger.stageReports(lease, invocation, List.of()),
          "TOOL_INPUT_INVALID");
      call(
          "stage-duplicate",
          () -> ledger.stageReports(lease, invocation, List.of(files.getFirst(), files.getFirst())),
          "TOOL_INPUT_INVALID");
      call(
          "stage-path-order",
          () -> ledger.stageReports(lease, invocation, files.reversed()),
          "INVOCATION_MISMATCH");
      db.update(
          "UPDATE invocation SET state='RUNNING' WHERE tenant_id=? AND tool='compose_report'",
          lease.tenant());
      call(
          "composition-before-invalid-file",
          () ->
              ledger.stageReports(
                  lease,
                  invocation,
                  List.of(
                      new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile(
                          files.getFirst().path(), 0, null, 0, null),
                      files.getLast())),
          "REPORT_COMPOSITION_REQUIRED");
      db.update(
          "UPDATE invocation SET state='SUCCEEDED' WHERE tenant_id=? AND tool='compose_report'",
          lease.tenant());
      var c = compositions.getFirst();
      String digest =
          db.queryForObject(
              "SELECT input_digest FROM invocation WHERE tenant_id=? AND id=?",
              String.class,
              lease.tenant(),
              c);
      db.update(
          "UPDATE invocation SET input_digest=? WHERE tenant_id=? AND id=?",
          "0".repeat(64),
          lease.tenant(),
          c);
      call(
          "composition-digest",
          () -> ledger.stageReports(lease, invocation, files),
          "REPORT_COMPOSITION_INVALID");
      db.update(
          "UPDATE invocation SET input_digest=? WHERE tenant_id=? AND id=?",
          digest,
          lease.tenant(),
          c);
      long limit =
          db.queryForObject(
              "SELECT storage_limit FROM tenant_budget WHERE tenant_id=?",
              Long.class,
              lease.tenant());
      db.update("UPDATE tenant_budget SET storage_limit=0 WHERE tenant_id=?", lease.tenant());
      call(
          "stage-budget",
          () -> ledger.stageReports(lease, invocation, files),
          "STORAGE_BUDGET_EXCEEDED");
      db.update(
          "UPDATE tenant_budget SET storage_limit=? WHERE tenant_id=?", limit, lease.tenant());
      call(
          "stage-new",
          () -> {
            uploads = ledger.stageReports(lease, invocation, files);
            return uploads;
          },
          "OK");
      call("stage-replay", () -> ledger.stageReports(lease, invocation, files), "OK");
      String firstReceipt =
          db.queryForObject(
              "SELECT file_receipt FROM artifact WHERE tenant_id=? AND id=?",
              String.class,
              lease.tenant(),
              uploads.getFirst().id());
      db.update(
          "UPDATE artifact SET file_receipt='{}' WHERE tenant_id=? AND id=?",
          lease.tenant(),
          uploads.getFirst().id());
      call(
          "stage-receipt-mismatch",
          () -> ledger.stageReports(lease, invocation, files),
          "ARTIFACT_MISMATCH");
      db.update(
          "UPDATE artifact SET file_receipt=? WHERE tenant_id=? AND id=?",
          firstReceipt,
          lease.tenant(),
          uploads.getFirst().id());
      publications =
          uploads.stream()
              .map(
                  u ->
                      new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication(
                          u.id(), u.uploadId() + "-" + u.hash()))
              .toList();
      var result = new ArrayList<Map<String, Object>>();
      for (int index = 0; index < files.size(); index++) {
        var file = files.get(index);
        result.add(
            Map.of(
                "artifactId",
                uploads.get(index).id(),
                "path",
                file.path(),
                "version",
                file.version(),
                "sha256",
                file.sha256(),
                "size",
                file.size(),
                "mediaType",
                file.mediaType()));
      }
      output = Payloads.json(Map.of("files", result));
      call("present-output-shape", () -> present("{\"files\":{}}"), "TOOL_OUTPUT_INVALID");
      var goodPublications = publications;
      publications = List.of(publications.getFirst(), publications.getFirst());
      call("present-duplicate", () -> present(output), "ARTIFACT_MISMATCH");
      publications = goodPublications;
      call(
          "present-index-mismatch",
          () -> present(Payloads.json(Map.of("files", result.reversed()))),
          "ARTIFACT_MISMATCH");
      var good = lease;
      lease =
          new Lease(
              good.tenant(),
              good.actor(),
              good.taskId(),
              good.attemptId(),
              good.fence() + 1,
              good.worker(),
              good.deadline(),
              good.goal(),
              good.capabilities(),
              good.installationEpoch());
      call("fence-stage", () -> ledger.stageReports(lease, invocation, files), "FENCE_REJECTED");
      call("fence-present", () -> present(output), "FENCE_REJECTED");
      lease = good;
      db.update(
          "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call(
          "permission-stage",
          () -> ledger.stageReports(lease, invocation, files),
          "ACCESS_REVOKED");
      call("permission-present", () -> present(output), "ACCESS_REVOKED");
      db.update(
          "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
          lease.tenant(),
          lease.actor());
      call("present-success", () -> present(output), "OK");
      call("present-replay", () -> present(output), "INVOCATION_STATE");
      db.update(
          "UPDATE invocation SET state='RUNNING' WHERE tenant_id=? AND id=?",
          lease.tenant(),
          invocation);
      call("stage-published-replay", () -> ledger.stageReports(lease, invocation, files), "OK");
      db.update(
          "UPDATE artifact SET state='STAGING' WHERE tenant_id=? AND task_id=?",
          lease.tenant(),
          task);
      db.update(
          "UPDATE reservation SET state='RESERVED',actual=NULL WHERE tenant_id=? AND task_id=?",
          lease.tenant(),
          task);
      var invalid = new ArrayList<>(result);
      var second = new LinkedHashMap<>(result.getLast());
      second.put("size", 0);
      invalid.set(1, second);
      call(
          "present-second-item-reject-outer-rollback",
          () -> present(Payloads.json(Map.of("files", invalid))),
          "ARTIFACT_MISMATCH");
    } catch (Throwable failure) {
      primary = failure;
      failure.printStackTrace(System.out);
    } finally {
      try {
        transactions.rollback(tx);
        System.out.println("ROLLBACK complete");
      } catch (Throwable failure) {
        failure.printStackTrace(System.out);
        if (primary == null) primary = failure;
        else primary.addSuppressed(failure);
      }
    }
    require(
        initial.equals(facts()), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
