package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionInputEligibility;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionInputServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证实际codec与资格惰性边界，以及拒绝/异常发生前的调用顺序。 */
class SessionInputServiceTest {
  private static final String SCOPE = "session_event_read,session_search";
  private static final String SEARCH = "session_event_read,session_event_search";
  private static final String TRACE = SEARCH + ",session_event_trace";

  private static void validate(String caps, String tool, String raw) {
    var records = new JdbcSessionRecords(null);
    if ("session_search".equals(tool)) records.scopes = ScopeCodec.LEDGER.sessionScopes;
    records.validateInput(
        Map.of("id", "00000000-0000-4000-8000-000000000001", "capabilities", caps),
        new Call(
            "0:p",
            "p",
            tool,
            raw == null
                ? null
                : raw.replace("self", "00000000-0000-4000-8000-000000000001")
                    .replace("other", "00000000-0000-4000-8000-000000000002")));
  }

  private static final class ScopeCodec {
    // Mapper/codec assembly only. Invalid input must fail before this unconnectable DS is used.
    static final JdbcLedger LEDGER =
        new JdbcLedger(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
            new io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles(environment()));

    private static org.springframework.core.env.StandardEnvironment environment() {
      var env = new org.springframework.core.env.StandardEnvironment();
      env.getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "codec-test", Map.of("jimu.test-mode", true)));
      return env;
    }
  }

  private static void failure(String caps, String tool, String raw, int status, String code) {
    var f = assertThrows(TaskFailure.class, () -> validate(caps, tool, raw));
    assertEquals(status, f.status());
    assertEquals(code, f.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"seq\":0}", "{\"seq\":0,\"session_id\":\"self\"}"})
  void scopeSelfAndDefaultNeverLoadEligibility(String raw) {
    // No scopes/DS installed: an eager require would fail.
    assertDoesNotThrow(() -> validate(SCOPE, "session_event_read", raw));
  }

  @Test
  void scopeCrossTargetLoadsOnlyAfterCodec() {
    failure(
        SCOPE,
        "session_event_read",
        "{\"seq\":1.0,\"session_id\":\"other\"}",
        400,
        "TOOL_INPUT_INVALID");
    assertThrows(
        NullPointerException.class,
        () -> validate(SCOPE, "session_event_read", "{\"seq\":0,\"session_id\":\"other\"}"));
  }

  @Test
  void scopeSearchCodecBeforeEligibilityAndUnknownToolBeforeCodec() {
    failure(SCOPE, "session_search", "{}", 400, "TOOL_INPUT_INVALID");
    failure(SCOPE, "session_event_trace", "not-json", 400, "TOOL_INPUT_INVALID");
    failure(SCOPE, "session_event_search", "not-json", 400, "TOOL_INPUT_INVALID");
  }

  @ParameterizedTest
  @ValueSource(strings = {SEARCH, TRACE})
  void nativeReadKeepsV1CodecAndCrossTargetRefusal(String caps) {
    assertDoesNotThrow(() -> validate(caps, "session_event_read", "{\"seq\":0}"));
    failure(
        caps,
        "session_event_read",
        "{\"seq\":0,\"session_id\":\"other\"}",
        404,
        "SESSION_EVENT_NOT_FOUND");
    failure(caps, "session_event_read", "{\"seq\":null}", 400, "TOOL_INPUT_INVALID");
  }

  @ParameterizedTest
  @ValueSource(strings = {SEARCH, TRACE})
  void nativeSearchRetainsVersionCodecAndTargetErrors(String caps) {
    assertDoesNotThrow(() -> validate(caps, "session_event_search", "{\"query\":\"public\"}"));
    failure(
        caps,
        "session_event_search",
        "{\"query\":\"public\",\"session_id\":\"other\"}",
        404,
        "SESSION_EVENT_NOT_FOUND");
    failure(caps, "session_event_search", "{}", 400, "TOOL_INPUT_INVALID");
  }

  @Test
  void traceVersionAndUnsupportedToolsRejectBeforeInput() {
    assertDoesNotThrow(() -> validate(TRACE, "session_event_trace", "{\"seq\":0}"));
    failure(
        TRACE,
        "session_event_trace",
        "{\"seq\":0,\"session_id\":\"other\"}",
        404,
        "SESSION_EVENT_NOT_FOUND");
    failure(SEARCH, "session_event_trace", "invalid", 400, "TOOL_INPUT_INVALID");
    failure("session_event_read", "session_event_search", "invalid", 400, "TOOL_INPUT_INVALID");
  }

  @Test
  void originalNullAndTaskCastBoundaries() {
    var r = new JdbcSessionRecords(null);
    assertThrows(NullPointerException.class, () -> r.validateInput(null, null));
    assertThrows(NullPointerException.class, () -> r.validateInput(Map.of(), null));
    var task = new HashMap<String, Object>();
    task.put("capabilities", 42);
    assertThrows(ClassCastException.class, () -> r.validateInput(task, null));
    assertThrows(NullPointerException.class, () -> validate(SCOPE, null, "{}"));
    assertThrows(
        NullPointerException.class,
        () -> validate("session_event_read", "session_event_read", null));
  }

  private static final class Port implements SessionInputEligibility {
    final List<String> calls = new ArrayList<>();
    String tool = "session_search", target;
    boolean scope = true, present = true;
    RuntimeException parseFailure, requireFailure;
    List<String> targets = List.of("a", "b");

    private void add(String name) {
      calls.add(name);
    }

    public boolean scopeTask() {
      add("scope");
      return scope;
    }

    public boolean searchTask() {
      add("search");
      return true;
    }

    public boolean traceTask() {
      add("trace");
      return true;
    }

    public String tool() {
      add("tool");
      return tool;
    }

    public String callerId() {
      add("id");
      return "self";
    }

    public List<String> scopeSearchTargets() {
      add("parse-search");
      if (parseFailure != null) throw parseFailure;
      return targets;
    }

    public String scopeReadTarget() {
      add("parse-read");
      return target;
    }

    public String traceTarget() {
      add("parse-trace");
      return target;
    }

    public String searchTarget() {
      add("parse-native-search");
      return target;
    }

    public String nativeReadTarget() {
      add("parse-native-read");
      return target;
    }

    public Scope requireScope() {
      add("require");
      if (requireFailure != null) throw requireFailure;
      return id -> {
        add("contains:" + id);
        return present;
      };
    }
  }

  @Test
  void searchRequiresExactlyOnceAfterParseAndBeforeMembership() {
    var p = new Port();
    new SessionInputServiceImpl().validate(p);
    assertEquals(
        List.of("scope", "tool", "parse-search", "require", "contains:a", "contains:b"), p.calls);
    p.calls.clear();
    p.targets = List.of();
    new SessionInputServiceImpl().validate(p);
    assertEquals(List.of("scope", "tool", "parse-search", "require"), p.calls);
  }

  @Test
  void parserAndRequireExceptionsAreNotTranslated() {
    var p = new Port();
    p.parseFailure = new IllegalStateException("codec");
    assertEquals(
        p.parseFailure,
        assertThrows(IllegalStateException.class, () -> new SessionInputServiceImpl().validate(p)));
    assertEquals(List.of("scope", "tool", "parse-search"), p.calls);
    p.calls.clear();
    p.parseFailure = null;
    p.requireFailure = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    assertEquals(
        p.requireFailure,
        assertThrows(TaskFailure.class, () -> new SessionInputServiceImpl().validate(p)));
    assertEquals(List.of("scope", "tool", "parse-search", "require"), p.calls);
  }

  @Test
  void firstMissingSourceStopsAndReadChecksOnlyCrossTarget() {
    var p = new Port();
    p.present = false;
    var f = assertThrows(TaskFailure.class, () -> new SessionInputServiceImpl().validate(p));
    assertEquals("SESSION_SCOPE_UNAVAILABLE", f.code());
    assertEquals(List.of("scope", "tool", "parse-search", "require", "contains:a"), p.calls);
    p.calls.clear();
    p.tool = "session_event_read";
    p.target = null;
    new SessionInputServiceImpl().validate(p);
    assertEquals(List.of("scope", "tool", "tool", "parse-read"), p.calls);
    p.calls.clear();
    p.target = "self";
    new SessionInputServiceImpl().validate(p);
    assertEquals(List.of("scope", "tool", "tool", "parse-read", "id"), p.calls);
    p.calls.clear();
    p.target = "other";
    assertThrows(TaskFailure.class, () -> new SessionInputServiceImpl().validate(p));
    assertEquals(
        List.of("scope", "tool", "tool", "parse-read", "id", "require", "contains:other"), p.calls);
  }
}
