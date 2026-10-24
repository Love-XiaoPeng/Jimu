package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 在相同真实终态事实中比较旧新输入与response资格边界；不重放worker/provider。 */
public final class InputEligibilityScenario {
  private static JdbcLedger ledger;
  private static JdbcTemplate db;
  private static String taskId;
  private static boolean scoped;

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static String facts() {
    var facts = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "task",
            "invocation",
            "step",
            "task_attempt",
            "reservation",
            "event",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_scope_query",
            "step_session_scope",
            "task_session_scope",
            "task_session_history")) {
      String key =
          table.equals("task")
              ? "id"
              : List.of(
                          "invocation_session_scope_query",
                          "step_session_scope",
                          "task_session_scope")
                      .contains(table)
                  ? "caller_task_id"
                  : "task_id";
      facts.put(
          table,
          db.queryForList(
                  "SELECT * FROM " + table + " WHERE tenant_id='tenant-a' AND " + key + "=?",
                  taskId)
              .toString());
    }
    facts.put(
        "budget",
        db.queryForList("SELECT * FROM tenant_budget WHERE tenant_id='tenant-a'").toString());
    return Payloads.json(facts);
  }

  private static void probe(
      Map<String, Object> task, String label, String tool, String raw, boolean response)
      throws Exception {
    var view = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    view.setAccessible(true);
    var active =
        (JdbcLedger)
            view.invoke(
                ledger,
                System.nanoTime() + 2_000_000_000L,
                scoped ? new QueryResources(0, 0, 0) : null);
    var call =
        new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call(
            "0:probe", "probe", tool, raw);
    String result;
    try {
      active.sessionTransaction(
          () -> {
            if (response) {
              var wire =
                  new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall(
                      "probe", "function", tool, raw);
              var value =
                  new io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response(
                      2, "public input proof", List.of(wire), null, null);
              active.sessions.response(task, Payloads.json(value), List.of(call));
            } else active.sessions.validateInput(task, call);
            return true;
          },
          System.nanoTime() + 2_000_000_000L);
      result = "OK";
    } catch (TaskFailure f) {
      result = f.status() + ":" + f.code();
    } catch (NullPointerException f) {
      result = "NPE";
    }
    System.out.println("RESULT " + (response ? "response:" : "direct:") + label + " " + result);
    System.out.println(
        "METER "
            + (response ? "response:" : "direct:")
            + label
            + " "
            + Payloads.json(active.queryContext().resources()));
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var shared =
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline.source(source);
    db = new JdbcTemplate(shared);
    ledger = new JdbcLedger(shared, new ModelProfiles(env), null, null, null);
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    taskId = args[1];
    var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", taskId);
    scoped = JdbcSessionScopes.task(task);
    String before = facts();
    String other = "00000000-0000-4000-8000-000000000099";
    var cases = new java.util.ArrayList<String[]>();
    cases.add(new String[] {"read-default", "session_event_read", "{\"seq\":0}"});
    cases.add(
        new String[] {
          "read-self", "session_event_read", Payloads.json(Map.of("seq", 0, "session_id", taskId))
        });
    cases.add(
        new String[] {
          "read-other", "session_event_read", Payloads.json(Map.of("seq", 0, "session_id", other))
        });
    cases.add(new String[] {"read-decimal", "session_event_read", "{\"seq\":1.0}"});
    cases.add(new String[] {"read-null", "session_event_read", "{\"seq\":null}"});
    cases.add(new String[] {"read-raw-null", "session_event_read", null});
    cases.add(new String[] {"unknown", "unsupported", "bad-json"});
    cases.add(new String[] {"null-tool", null, "{}"});
    String search = scoped ? "session_search" : "session_event_search";
    cases.add(new String[] {"search-default", search, "{\"query\":\"public\"}"});
    cases.add(new String[] {"search-invalid", search, "{}"});
    cases.add(
        new String[] {
          "search-other",
          search,
          Payloads.json(
              scoped
                  ? Map.of("query", "public", "session_ids", List.of(other))
                  : Map.of("query", "public", "session_id", other))
        });
    cases.add(new String[] {"trace-default", "session_event_trace", "{\"seq\":0}"});
    cases.add(
        new String[] {
          "trace-other", "session_event_trace", Payloads.json(Map.of("seq", 0, "session_id", other))
        });
    if (scoped) {
      String sourceId =
          db.queryForObject(
              "SELECT source_task_id FROM task_session_scope_target WHERE tenant_id='tenant-a' AND caller_task_id=? ORDER BY ordinal LIMIT 1",
              String.class,
              taskId);
      cases.add(
          new String[] {
            "read-source",
            "session_event_read",
            Payloads.json(Map.of("seq", 0, "session_id", sourceId))
          });
      cases.add(
          new String[] {
            "search-source",
            search,
            Payloads.json(Map.of("query", "public", "session_ids", List.of(sourceId)))
          });
      cases.add(
          new String[] {
            "search-unsupported", search, "{\"query\":\"public\",\"availability\":\"all\"}"
          });
    }
    for (boolean response : List.of(false, true))
      for (var input : cases) probe(task, input[0], input[1], input[2], response);
    require(
        before.equals(facts()), "all input/response probes preserve full stored facts/accounting");
  }
}
