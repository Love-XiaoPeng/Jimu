package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionResponsePreflight;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionResponseServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实 codec/事件合同与惰性端口：校验拒绝顺序、NULL 及冻结 preview。 */
class SessionResponseServiceTest {
  private final List<String> order = new ArrayList<>();
  private Map<String, Object> preview;
  private String stop;
  private long reserve;

  private static String response(String calls, String content) {
    return "{\"version\":2,\"content\":" + content + ",\"calls\":" + calls + "}";
  }

  private static String wire(String type, String name, String id) {
    return Payloads.json(Map.of("id", id, "type", type, "name", name, "arguments", "{\"seq\":0}"));
  }

  private static Call call(String name, String id) {
    return new Call("0:" + id, id, name, "{\"seq\":0}");
  }

  private void validate(String raw, List<Call> calls, int version) {
    var records = new JdbcSessionRecords(null);
    records.sensitive(List.of("SYNTHETIC"));
    var task =
        Map.<String, Object>of(
            "id",
            "00000000-0000-4000-8000-000000000001",
            "capabilities",
            version == 4
                ? "session_event_read,session_search"
                : version == 3
                    ? "session_event_read,session_event_search,session_event_trace"
                    : "session_event_read");
    var actual = new StoredSessionResponsePreflight(records, task);
    var port =
        (SessionResponsePreflight)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionResponsePreflight.class},
                (p, m, a) -> {
                  order.add(m.getName());
                  if (m.getName().equals(stop)) throw new IllegalStateException(stop);
                  if (m.getName().equals("metadata")) {
                    reserve = (long) a[0];
                    return null;
                  }
                  if (m.getName().equals("validateInput")) return null;
                  if (m.getName().equals("event")) {
                    @SuppressWarnings("unchecked")
                    var event = (Map<String, Object>) a[0];
                    preview = event;
                  }
                  try {
                    return m.invoke(actual, a);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    new SessionResponseServiceImpl().validate(port, raw, calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 4})
  void frozenPreviewAndRealContract(int version) {
    validate(response("[]", "\"public SYNTHETIC 🧱\""), List.of(), version);
    assertEquals(2048, reserve);
    var fields =
        new ArrayList<>(
            List.of(
                "version",
                "session_id",
                "seq",
                "type",
                "time",
                "source",
                "content_policy",
                "redacted",
                "data"));
    if (version == 3) fields.add("source_event_seqs");
    if (version == 4) fields.add("source_event_refs");
    assertEquals(fields, new ArrayList<>(preview.keySet()));
    assertEquals(127, preview.get("seq"));
    assertEquals(version, preview.get("version"));
    assertEquals("2026-10-23T00:00:00.000000Z", preview.get("time"));
    assertEquals(true, preview.get("redacted"));
    assertEquals(
        Map.of("content", "public [redacted] 🧱", "calls", List.of()), preview.get("data"));
    assertEquals(
        List.of(
            "metadata",
            "bytes",
            "decode",
            "publicText",
            "version",
            "sessionId",
            "source",
            "traceTask",
            "scopeTask",
            "event"),
        order);
  }

  @Test
  void metadataBeforeBytesAndDecode() {
    stop = "metadata";
    assertThrows(IllegalStateException.class, () -> validate(null, List.of(), 1));
    assertEquals(List.of("metadata"), order);
  }

  @Test
  void nullCallsBeforeMetadata() {
    assertThrows(NullPointerException.class, () -> validate("bad", null, 1));
    assertEquals(List.of(), order);
  }

  @Test
  void oversizedResponseBeforeDecode() {
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> validate("x".repeat(16385), List.of(), 1)).code());
    assertEquals(List.of("metadata", "bytes"), order);
  }

  @Test
  void tooManyCallsBeforeDecodeWithOriginalReservation() {
    assertThrows(
        TaskFailure.class,
        () ->
            validate("bad", java.util.Collections.nCopies(5, call("session_event_read", "id")), 1));
    assertEquals(22528, reserve);
    assertEquals(List.of("metadata", "bytes"), order);
  }

  @Test
  void wrongVersionShortCircuitsNullParsedCalls() {
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(
                TaskFailure.class, () -> validate("{\"version\":1,\"calls\":null}", List.of(), 1))
            .code());
    assertEquals(List.of("metadata", "bytes", "decode"), order);
  }

  @Test
  void nullParsedCallsKeepsUncheckedBoundary() {
    assertThrows(
        NullPointerException.class, () -> validate(response("null", "null"), List.of(), 1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrongSlot", "wrongType", "wrongTool", "badId"})
  void perCallRefusalBeforeInputAndText(String mode) {
    var c =
        call(
            mode.equals("wrongTool") ? "other" : "session_event_read",
            mode.equals("badId") ? "bad id" : "id");
    if (mode.equals("wrongSlot")) c = new Call("1:id", c.providerId(), c.tool(), c.input());
    var selected = c;
    assertEquals(
        mode.equals("badId") ? "SESSION_EVENT_UNSAFE" : "TOOL_INPUT_INVALID",
        assertThrows(
                TaskFailure.class,
                () ->
                    validate(
                        response(
                            "["
                                + wire(
                                    mode.equals("wrongType") ? "other" : "function",
                                    selected.tool(),
                                    selected.providerId())
                                + "]",
                            "\"text\""),
                        List.of(selected),
                        1))
            .code());
    assertFalse(order.contains("validateInput"));
    assertFalse(order.contains("publicText"));
  }

  @Test
  void callIdBeforeInputAndInputBeforePublicText() {
    stop = "validateInput";
    assertThrows(
        IllegalStateException.class,
        () ->
            validate(
                response("[" + wire("function", "session_event_read", "id") + "]", "null"),
                List.of(call("session_event_read", "id")),
                1));
    assertEquals(List.of("metadata", "bytes", "decode", "callId", "validateInput"), order);
    assertEquals(6144, reserve);
  }
}
