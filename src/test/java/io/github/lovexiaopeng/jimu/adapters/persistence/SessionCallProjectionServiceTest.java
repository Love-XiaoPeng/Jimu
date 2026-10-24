package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real codec/sanitizer and lazy typed projection, including original unchecked boundaries. */
class SessionCallProjectionServiceTest {
  private static final String NATIVE = "session_event_read,session_event_search";
  private static final String SCOPE = "session_event_read,session_search";

  private static Map<String, Object> task(boolean scope) {
    return Map.of("capabilities", scope ? SCOPE : NATIVE);
  }

  private static String tool(boolean scope) {
    return scope ? "session_search" : "session_event_search";
  }

  private static ToolCall call(String tool, String raw) {
    return new ToolCall("slot", "function", tool, raw);
  }

  private static JdbcSessionRecords records() {
    var r = new JdbcSessionRecords(null);
    r.sensitive(List.of("SYNTHETIC"));
    return r;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queryOnlyRedactionPreservesFieldTypesOrderAndCallIdentity(boolean scope) {
    String raw = "{\"before\":null,\"query\":\"SYNTHETIC 🧱\",\"extra\":[1.0,true]}";
    var source =
        List.of(call(tool(scope), raw), new ToolCall("two", "other", "untouched", "bad-json"));
    var result = records().projectedCalls(task(scope), source);
    assertTrue(result.redacted());
    assertEquals(
        "{\"before\":null,\"query\":\"[redacted] 🧱\",\"extra\":[1.0,true]}",
        result.calls().getFirst().arguments());
    assertEquals("slot", result.calls().getFirst().id());
    assertEquals("function", result.calls().getFirst().type());
    assertEquals(tool(scope), result.calls().getFirst().name());
    assertEquals(source.get(1), result.calls().get(1));
    assertSame(source.get(1).arguments(), result.calls().get(1).arguments());
    assertNotSame(source.get(1), result.calls().get(1));
    assertThrows(UnsupportedOperationException.class, () -> result.calls().clear());
    assertEquals(raw, source.getFirst().arguments());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rawAndSerializedUtf8LimitsAreSeparate(boolean scope) {
    int max = scope ? 4096 : 2048;
    String prefix = "{\"query\":\"public\",\"pad\":\"";
    String exact = prefix + "x".repeat(max - prefix.length() - 2) + "\"}";
    assertEquals(max, SessionContracts.bytes(exact));
    assertEquals(
        exact,
        records()
            .projectedCalls(task(scope), List.of(call(tool(scope), exact)))
            .calls()
            .getFirst()
            .arguments());
    failure(scope, exact + " ", 400, "TOOL_INPUT_INVALID");
    String expansion = "{\"query\":\"SYNTHETIC\",\"pad\":\"";
    expansion += "x".repeat(max - expansion.length() - 2) + "\"}";
    failure(scope, expansion, 429, "SESSION_HISTORY_LIMIT");
  }

  private static void failure(boolean scope, String raw, int status, String code) {
    var f =
        assertThrows(
            TaskFailure.class,
            () -> records().projectedCalls(task(scope), List.of(call(tool(scope), raw))));
    assertEquals(status, f.status());
    assertEquals(code, f.code());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{\"query\":\"a\",\"query\":\"b\"}", "{} trailing", "{\"query\":\"\uD800\"}"})
  void strictCodecErrorsStay400(String raw) {
    failure(false, raw, 400, "TOOL_INPUT_INVALID");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "42", "\"value\""})
  void nonObjectCastRemainsUnchecked(String raw) {
    assertThrows(
        ClassCastException.class,
        () -> records().projectedCalls(task(false), List.of(call(tool(false), raw))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"query\":null}", "{\"query\":42}"})
  void sensitiveConfigurationBeforeNullQueryFailure(String raw) {
    var empty = new JdbcSessionRecords(null);
    var f =
        assertThrows(
            TaskFailure.class,
            () -> empty.projectedCalls(task(false), List.of(call(tool(false), raw))));
    assertEquals(503, f.status());
    assertEquals("SESSION_EVENT_UNSAFE", f.code());
    assertThrows(
        NullPointerException.class,
        () -> records().projectedCalls(task(false), List.of(call(tool(false), raw))));
  }

  @Test
  void unselectedArgumentsAreRawAndDoNotLoadSensitiveConfiguration() {
    var r = new JdbcSessionRecords(null);
    var source =
        Arrays.asList(call("session_event_read", null), call("session_search", "bad-json"));
    assertEquals(source, r.projectedCalls(task(false), source).calls());
    assertFalse(
        r.projectedCalls(
                Map.of("capabilities", "session_event_read"),
                List.of(call(tool(false), "bad-json")))
            .redacted());
    assertEquals(List.of(), r.projectedCalls(null, List.of()).calls());
  }

  @Test
  void traceIncludesNativeSearchAndPathsAreRedactedOnlyInQuery() {
    var result =
        records()
            .projectedCalls(
                Map.of("capabilities", NATIVE + ",session_event_trace"),
                List.of(
                    call(
                        tool(false),
                        "{\"query\":\"/private/file next public\",\"keep\":\"/private/file\"}")));
    assertEquals(
        "{\"query\":\"[redacted] [redacted] public\",\"keep\":\"/private/file\"}",
        result.calls().getFirst().arguments());
    assertTrue(result.redacted());
  }

  @Test
  void originalNullAndTaskTypeBoundariesAndFirstFailureStayLazy() {
    var r = records();
    assertThrows(
        NullPointerException.class, () -> r.projectedCalls(null, List.of(call(tool(false), "{}"))));
    assertThrows(NullPointerException.class, () -> r.projectedCalls(task(false), null));
    assertThrows(
        NullPointerException.class,
        () -> r.projectedCalls(task(false), Arrays.asList((ToolCall) null)));
    assertThrows(
        NullPointerException.class, () -> r.projectedCalls(task(false), List.of(call(null, "{}"))));
    var bad = new HashMap<String, Object>();
    bad.put("capabilities", 42);
    assertThrows(
        ClassCastException.class, () -> r.projectedCalls(bad, List.of(call(tool(false), "{}"))));
    assertThrows(
        NullPointerException.class,
        () -> r.projectedCalls(task(false), List.of(call(tool(false), null))));
    // The invalid first codec must win over a null later call; no eager mapping/access.
    var f =
        assertThrows(
            TaskFailure.class,
            () ->
                r.projectedCalls(task(false), Arrays.asList(call(tool(false), "bad-json"), null)));
    assertEquals(400, f.status());
  }
}
