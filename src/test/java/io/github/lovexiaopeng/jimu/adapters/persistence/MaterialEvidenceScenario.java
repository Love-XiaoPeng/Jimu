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

/** Frozen material/history and private image bytes, original authorization and outer rollback. */
public final class MaterialEvidenceScenario {
  private static final List<Map<String, Object>> sql = new ArrayList<>();
  private static boolean measuring;
  private static JdbcTemplate db;
  private static JdbcLedger ledger;
  private static Lease lease;
  private static String task;
  private static String invocation, step;
  private static io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore store;
  private static String raw, compose, dispatch;
  private static int reads;
  private static String imageKey;

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
            MaterialEvidenceScenario.class.getClassLoader(),
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
                    MaterialEvidenceScenario.class.getClassLoader(),
                    new Class<?>[] {java.sql.ResultSet.class},
                    (rp, rm, ra) -> {
                      Object item = invoke(result, rm, ra);
                      if (measuring
                          && rm.getName().startsWith("get")
                          && ra != null
                          && ra.length >= 1) {
                        var access = new LinkedHashMap<String, Object>();
                        access.put("method", rm.getName());
                        access.put("column", ra[0].toString());
                        access.put("type", item == null ? "NULL" : item.getClass().getName());
                        access.put("value", evidenceValue(item));
                        access.put(
                            "arguments",
                            java.util.Arrays.stream(ra)
                                .map(
                                    v ->
                                        v instanceof Class<?> type
                                            ? type.getName()
                                            : String.valueOf(v))
                                .toList());
                        getters.add(access);
                      }
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
    for (String table :
        List.of(
            "task_image_input",
            "task_image_object",
            "model_dispatch",
            "reservation",
            "event",
            "invocation",
            "step",
            "task_attempt"))
      out.put(
          table,
          db.queryForList(
              "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND task_id=?", task));
    out.put("worker_process", db.queryForList("SELECT * FROM worker_process"));
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
    if (value instanceof java.util.Optional<?> optional)
      return evidenceValue(optional.orElse(null));
    if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    if (value instanceof Record record) {
      var converted = new LinkedHashMap<String, Object>();
      for (var component : record.getClass().getRecordComponents()) {
        try {
          converted.put(component.getName(), evidenceValue(component.getAccessor().invoke(record)));
        } catch (ReflectiveOperationException failure) {
          throw new IllegalStateException("record evidence conversion", failure);
        }
      }
      return converted;
    }
    if (value instanceof Map<?, ?> map) {
      var converted = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> converted.put(key.toString(), evidenceValue(item)));
      return converted;
    }
    if (value instanceof List<?> list)
      return list.stream().map(MaterialEvidenceScenario::evidenceValue).toList();
    return value;
  }

  private static void call(String name, Supplier<Object> work, String expected) {
    sql.clear();
    var before = proof();
    int prior = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    Object result = null;
    Throwable primary = null;
    int priorReads = reads;
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
    int restored = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    out.put("sessionPrior", prior);
    out.put("sessionAfter", restored);
    require(prior == restored, "session restored after " + name);
    out.put("before", before);
    out.put("after", proof());
    out.put("result", result);
    out.put("imageStoreReads", reads);
    out.put("imageStoreReadDelta", reads - priorReads);
    out.put("sql", new ArrayList<>(sql));
    out.put(
        "code",
        primary == null
            ? "OK"
            : primary instanceof TaskFailure f ? f.code() : primary.getClass().getName());
    System.out.println("CALL " + Payloads.json(evidenceValue(out)));
    if (primary != null) {
      primary.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(primary)));
    }
    require(
        expected.equals(out.get("code")),
        name + " expected " + expected + " got " + out.get("code"));
  }

  private static List<Map<String, Object>> exceptionFacts(Throwable failure) {
    var queue = new java.util.ArrayDeque<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var facts = new ArrayList<Map<String, Object>>();
    queue.add(failure);
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      if (!seen.add(current)) continue;
      var fact = new LinkedHashMap<String, Object>();
      fact.put("type", current.getClass().getName());
      fact.put("message", current.getMessage());
      if (current instanceof SQLException sqlFailure) {
        fact.put("SQLState", sqlFailure.getSQLState());
        fact.put("vendor", sqlFailure.getErrorCode());
        if (sqlFailure.getNextException() != null) queue.add(sqlFailure.getNextException());
      }
      if (current.getCause() != null) queue.add(current.getCause());
      queue.addAll(java.util.Arrays.asList(current.getSuppressed()));
      facts.add(fact);
    }
    return facts;
  }

  private static void setup() {
    ledger.registerWorker(task, task, ProcessHandle.current().pid(), 1, "private", "private");
    lease = ledger.claim(task).orElseThrow();
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
        "lease keys");
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
  }

  private static String seed(
      String tool, String input, String output, String request, int ordinal) {
    step = JdbcLedger.id();
    invocation = JdbcLedger.id();
    var response =
        Payloads.json(
            Map.of(
                "version",
                2,
                "content",
                "",
                "calls",
                List.of(
                    Map.of(
                        "id",
                        "call" + ordinal,
                        "name",
                        tool,
                        "type",
                        "function",
                        "arguments",
                        input))));
    db.update(
        "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest) VALUES(?,?,?,?,?,'PREPARED',?,?,?,?)",
        lease.tenant(),
        step,
        task,
        lease.attemptId(),
        ordinal,
        request,
        Payloads.hash(request),
        response,
        Payloads.hash(response));
    var pin = ledger.tools(taskRow()).pins().get(tool);
    db.update(
        "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest) VALUES(?,?,?,?,?,? ,?,?,?,?,?,?,?,?,?,'SUCCEEDED',?,?)",
        lease.tenant(),
        invocation,
        task,
        step,
        lease.attemptId(),
        ordinal,
        "0:call" + ordinal,
        "call" + ordinal,
        JdbcLedger.id(),
        tool,
        pin.version(),
        pin.digest(),
        pin.installationEpoch(),
        input,
        Payloads.hash(input),
        output,
        Payloads.hash(output));
    return response;
  }

  private static Object history(String request, int ordinal) {
    ledger.materialBody(taskRow(), request, ordinal);
    return "VALID";
  }

  private static void materials() throws Exception {
    var plan =
        io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans.plan("qwen-main-v1");
    var material =
        io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials.load(plan, "W1");
    var owner = new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");
    task =
        ledger
            .submit(
                owner,
                "material-private",
                new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                    material.goal(),
                    List.of("read", "write", "present", "web_fetch"),
                    List.of(),
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(120, 8, 20, null)))
            .taskId();
    setup();
    call("file-no-material", () -> ledger.evaluationFile(lease), "OK");
    var binding =
        new io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Binding(
            plan.id(),
            Payloads.hash(Payloads.json(plan)),
            "A",
            "W1",
            "qwen3.8-max-0902",
            null,
            plan.maxRequests(),
            plan.maxCostMicros());
    String m = Payloads.json(material), b = Payloads.json(binding);
    // Explicit local frozen-material qualification fixture, not paid evaluation admission.
    db.update(
        "UPDATE task SET evaluation_snapshot=?,evaluation_digest=?,material_snapshot=?,material_digest=?,material_system_digest=? WHERE tenant_id=? AND id=?",
        b,
        Payloads.hash(b),
        m,
        Payloads.hash(m),
        Payloads.hash(
            material
                .systemTemplate()
                .replace(
                    "{{HOST_REPORT_TIME}}", JdbcLedger.time(taskRow(), "report_time").toString())),
        lease.tenant(),
        task);
    call("file-before", () -> ledger.evaluationFile(lease), "OK");
    var file =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile(
            material.target(), 0, null, 0, "text/markdown");
    call(
        "bind-first",
        () -> {
          ledger.bindEvaluationFile(lease, file);
          return "BOUND";
        },
        "OK");
    call(
        "bind-replay",
        () -> {
          ledger.bindEvaluationFile(lease, file);
          return "BOUND";
        },
        "OK");
    call(
        "bind-mismatch",
        () -> {
          ledger.bindEvaluationFile(
              lease,
              new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile(
                  material.target(), 1, null, 0, "text/markdown"));
          return null;
        },
        "EVALUATION_MATERIAL_MISMATCH");
    var body = Payloads.read("{}", com.fasterxml.jackson.databind.node.ObjectNode.class);
    body.set("tools", material.tools());
    var messages = body.putArray("messages");
    messages
        .addObject()
        .put("role", "system")
        .put(
            "content",
            material
                .systemTemplate()
                .replace(
                    "{{HOST_REPORT_TIME}}", JdbcLedger.time(taskRow(), "report_time").toString()));
    messages.addObject().put("role", "user").put("content", material.goal());
    String request = Payloads.json(Map.of("requestBody", Payloads.json(body)));
    call("history-initial", () -> history(request, 1), "OK");
    String input = Payloads.json(Map.of("file_path", material.target(), "content", "核对😀"));
    String output = Payloads.json(Map.of("ok", true));
    seed("write", input, output, request, 1);
    var assistant = messages.addObject().put("role", "assistant").put("content", "");
    assistant
        .putArray("tool_calls")
        .addObject()
        .put("id", "call1")
        .put("type", "function")
        .putObject("function")
        .put("name", "write")
        .put("arguments", input);
    messages.addObject().put("role", "tool").put("tool_call_id", "call1").put("content", output);
    String next = Payloads.json(Map.of("requestBody", Payloads.json(body)));
    call("history-prefix", () -> history(next, 2), "OK");
    ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(3)).put("content", "forged");
    String forged = Payloads.json(Map.of("requestBody", Payloads.json(body)));
    call("history-mismatch", () -> history(forged, 2), "EVALUATION_MATERIAL_MISMATCH");
    call("file-after-write", () -> ledger.evaluationFile(lease), "OK");
    String good = JdbcLedger.s(taskRow(), "material_file_digest");
    db.update(
        "UPDATE task SET material_file_digest=? WHERE tenant_id=? AND id=?",
        "0".repeat(64),
        lease.tenant(),
        task);
    call(
        "file-corrupt-receipt", () -> ledger.evaluationFile(lease), "EVALUATION_MATERIAL_MISMATCH");
    db.update(
        "UPDATE task SET material_file_digest=? WHERE tenant_id=? AND id=?",
        good,
        lease.tenant(),
        task);
    call("file-fence", () -> ledger.evaluationFile(altered(lease)), "FENCE_REJECTED");
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
        lease.tenant(),
        lease.actor());
    call("file-permission", () -> ledger.evaluationFile(lease), "ACCESS_REVOKED");
    db.update(
        "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
        lease.tenant(),
        lease.actor());
    db.update("UPDATE task SET state='CANCELLED' WHERE tenant_id=? AND id=?", lease.tenant(), task);
    db.update("UPDATE tenant_budget SET running=running-1 WHERE tenant_id=?", lease.tenant());
  }

  private static String checkpoint(
      java.util.List<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage>
          messages,
      java.util.List<org.springframework.ai.tool.definition.ToolDefinition> definitions,
      byte[] bytes) {
    var model = ledger.snapshot(taskRow());
    String body =
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol()
            .body(model, messages, definitions, ref -> bytes);
    return wire(
        messages,
        body,
        io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles.digest(model));
  }

  private static String wire(
      java.util.List<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage>
          messages,
      String body,
      String config) {
    var model = ledger.snapshot(taskRow());
    return Payloads.json(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
            3,
            model.model(),
            messages,
            ledger.caps(taskRow()),
            lease.installationEpoch(),
            model.stream(),
            config,
            body,
            Payloads.hash(body)));
  }

  private static String mediaHistory(
      io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result ref, byte[] bytes) throws Exception {
    var messages =
        new java.util.ArrayList<
            io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage>();
    var context =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext(
            JdbcLedger.time(taskRow(), "report_time"), List.of());
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "system",
            new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ImageReportComposer()
                .instruction(context),
            List.of(),
            List.of()));
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "user", JdbcLedger.s(taskRow(), "goal"), List.of(), List.of()));
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "user",
            io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.directory(
                ledger.mediaSnapshot(lease)),
            List.of(),
            List.of()));
    var definitions =
        new java.util.ArrayList<org.springframework.ai.tool.definition.ToolDefinition>();
    for (String name : ledger.caps(taskRow())) {
      var contract =
          new io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts()
              .load(name, ledger.tools(taskRow()).pins().get(name).version());
      definitions.add(
          org.springframework.ai.tool.definition.ToolDefinition.builder()
              .name(name)
              .description(
                  io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts
                      .description(name))
              .inputSchema(contract.input())
              .build());
    }
    String first = checkpoint(messages, definitions, bytes);
    db.update(
        "UPDATE step SET request_snapshot=?,request_digest=? WHERE tenant_id=? AND id=?",
        first,
        Payloads.hash(first),
        lease.tenant(),
        step);
    String input =
        JdbcLedger.s(
            db.queryForMap(
                "SELECT input_snapshot FROM invocation WHERE tenant_id=? AND id=?",
                lease.tenant(),
                invocation),
            "input_snapshot");
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "assistant",
            "",
            List.of(
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
                    "call1", "function", "read_image", input)),
            List.of()));
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "tool",
            "",
            List.of(),
            List.of(
                new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolReply(
                    "call1", "read_image", Payloads.json(ref)))));
    messages.add(
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage(
            "user",
            io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.text(ref),
            List.of(),
            List.of(),
            List.of(ref)));
    String next = checkpoint(messages, definitions, bytes);
    call("history-media-projection", () -> history(next, 2), "OK");
    var cp =
        Payloads.read(
            next, io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
    call(
        "history-media-config",
        () -> history(wire(messages, cp.requestBody(), "0".repeat(64)), 2),
        "SNAPSHOT_CORRUPT");
    var body =
        Payloads.read(cp.requestBody(), com.fasterxml.jackson.databind.node.ObjectNode.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("tools").get(0).path("function"))
        .put("description", "forged");
    String bad = wire(messages, Payloads.json(body), cp.configDigest());
    call("history-media-schema", () -> history(bad, 2), "SNAPSHOT_CORRUPT");
    return next;
  }

  private static void images() throws Exception {
    var owner = new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("tenant-a", "alice");
    task =
        ledger
            .submit(
                owner,
                "image-evidence-private",
                new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Request(
                    "image-evidence-private",
                    List.of("read_image", "compose_report", "present"),
                    List.of(),
                    new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Budget(120, 8, 20, null)),
                List.of(
                    new io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Input(
                        "inputs/images/sample.png", raw)))
            .taskId();
    setup();
    seed(
        "read_image",
        Payloads.json(Map.of("file_path", "inputs/images/sample.png")),
        "{}",
        "{}",
        1);
    db.update(
        "UPDATE invocation SET state='RUNNING',output_snapshot=NULL,output_digest=NULL WHERE tenant_id=? AND id=?",
        lease.tenant(),
        invocation);
    ledger.reserve(lease.tenant(), task, invocation, null, "TOOL", 1, "RESERVED", "KNOWN");
    db.update("UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
    db.update(
        "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?", lease.tenant(), task);
    var entry = ledger.mediaSnapshot(lease).entries().getFirst();
    String image = ledger.reserveImage(lease, invocation, entry.inputId());
    byte[] bytes = java.util.Base64.getDecoder().decode(raw);
    var normalized =
        new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Image(
            image, "image/png", Payloads.hash(bytes), bytes.length, 3, 2, 3, 2, "jimu-image-v1");
    var ref =
        new io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result(
            entry.filePath(),
            entry.inputId(),
            "image:" + invocation,
            entry.sha256(),
            entry.size(),
            normalized);
    imageKey = image + "-" + normalized.sha256();
    require(
        imageKey.equals(store.write(lease.tenant(), image, bytes, normalized.sha256())),
        "private image key");
    ledger.imageSucceeded(lease, invocation, imageKey, ref);
    String complete = mediaHistory(ref, bytes);
    var cp =
        Payloads.read(
            complete,
            io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
    var body =
        Payloads.read(cp.requestBody(), com.fasterxml.jackson.databind.node.ObjectNode.class);
    seed("compose_report", "{}", "{}", Payloads.json(cp), 2);
    compose = invocation;
    dispatch = JdbcLedger.id();
    db.update(
        "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state) VALUES(?,?,?,?,?,?,'RESPONDED')",
        lease.tenant(),
        dispatch,
        task,
        step,
        cp.configDigest(),
        cp.bodyDigest());
    db.update(
        "UPDATE step SET dispatch_id=? WHERE tenant_id=? AND id=?", dispatch, lease.tenant(), step);
    call("image-evidence", () -> ledger.imageReportContext(lease, compose), "OK");
    call("image-evidence-replay", () -> ledger.imageReportContext(lease, compose), "OK");
    call("image-internal", () -> ledger.imageReportContext(taskRow(), compose), "OK");
    var duplicateMessages = new java.util.ArrayList<>(cp.messages());
    duplicateMessages.add(cp.messages().getLast());
    var duplicateBody = body.deepCopy();
    duplicateBody
        .withArray("messages")
        .add(io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.provider(ref, bytes));
    String duplicate = wire(duplicateMessages, Payloads.json(duplicateBody), cp.configDigest());
    db.update(
        "UPDATE step SET request_snapshot=?,request_digest=? WHERE tenant_id=? AND id=?",
        duplicate,
        Payloads.hash(duplicate),
        lease.tenant(),
        step);
    call("image-duplicate-references", () -> ledger.imageReportContext(lease, compose), "OK");
    db.update(
        "UPDATE step SET request_snapshot=?,request_digest=? WHERE tenant_id=? AND id=?",
        complete,
        Payloads.hash(complete),
        lease.tenant(),
        step);
    call("image-fence", () -> ledger.imageReportContext(altered(lease), compose), "FENCE_REJECTED");
    db.update(
        "UPDATE actor_access SET enabled=FALSE WHERE tenant_id=? AND actor_id=?",
        lease.tenant(),
        lease.actor());
    call("image-permission", () -> ledger.imageReportContext(lease, compose), "ACCESS_REVOKED");
    db.update(
        "UPDATE actor_access SET enabled=TRUE WHERE tenant_id=? AND actor_id=?",
        lease.tenant(),
        lease.actor());
    if (Boolean.getBoolean("jimu.facade-verification")) return;
    db.update(
        "UPDATE model_dispatch SET state='STARTED' WHERE tenant_id=? AND id=?",
        lease.tenant(),
        dispatch);
    call(
        "image-dispatch",
        () -> ledger.imageReportContext(lease, compose),
        "REPORT_IMAGE_SOURCE_INVALID");
    db.update(
        "UPDATE model_dispatch SET state='RESPONDED' WHERE tenant_id=? AND id=?",
        lease.tenant(),
        dispatch);
    var bad = body.deepCopy();
    bad.putArray("messages");
    String badBody = Payloads.json(bad);
    var badCp =
        new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint(
            cp.version(),
            cp.model(),
            cp.messages(),
            cp.capabilities(),
            cp.epoch(),
            cp.stream(),
            cp.configDigest(),
            badBody,
            Payloads.hash(badBody));
    String badWire = Payloads.json(badCp);
    db.update(
        "UPDATE step SET request_snapshot=?,request_digest=? WHERE tenant_id=? AND id=?",
        badWire,
        Payloads.hash(badWire),
        lease.tenant(),
        step);
    call(
        "image-missing-part",
        () -> ledger.imageReportContext(lease, compose),
        "REPORT_IMAGE_SOURCE_INVALID");
    System.out.println(
        "IMAGESTORE reads="
            + reads
            + "; real private temporary bytes; duplicate references retained");
  }

  private static Lease altered(Lease good) {
    return new Lease(
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
  }

  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      props.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-mcp", props));
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

    raw = args[2];
    store =
        new io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore(
            Path.of(props.getProperty("jimu.images.root")),
            point -> {
              if (point.equals("image.read.before")) reads++;
            });
    ledger.imageStorage(
        new io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore() {
          public void check() {
            store.check();
          }

          public String write(String tenant, String upload, byte[] bytes, String hash) {
            return store.write(tenant, upload, bytes, hash);
          }

          public byte[] read(String tenant, String key, String hash, long size) {
            reads++;
            return store.read(tenant, key, hash, size);
          }

          public void removeUpload(String tenant, String upload) {
            store.removeUpload(tenant, upload);
          }
        });
    var transactions = new DataSourceTransactionManager(source);
    String initial = facts();
    System.out.println("FULLBEFORE " + initial);
    var tx = transactions.getTransaction(new DefaultTransactionDefinition());
    Throwable primary = null;
    try {

      System.out.println(
          "PROVENANCE public local paid=false admission plus explicit frozen-material and step/dispatch fixtures; no tool/provider execution; outer rollback only");
      if (!Boolean.getBoolean("jimu.facade-verification")) materials();
      images();
    } catch (Throwable failure) {
      primary = failure;
      failure.printStackTrace(System.out);
      System.out.println("EXCEPTIONS " + Payloads.json(exceptionFacts(failure)));
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
    String restored = facts();
    System.out.println("FULLAFTER " + restored);
    require(
        initial.equals(restored), "private all-table snapshot restored including worker_process");
    System.out.println("RESTORED all private tables including worker_process");
    if (primary != null) throw new AssertionError("see original primary/cleanup evidence", primary);
  }
}
