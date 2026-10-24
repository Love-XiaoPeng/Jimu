package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallBindings;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionCallBindingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests decision order and lazy unchecked boundaries independently of adapter implementation. */
class SessionCallBindingServiceTest {
  private static final class Fixture {
    final List<String> seen = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    final Map<String, RuntimeException> failures = new HashMap<>();
    final SessionCallBindings.Invocation call = proxy(SessionCallBindings.Invocation.class);
    final SessionCallBindings records = proxy(SessionCallBindings.class);

    Fixture() {
      values.put("hasUpper", true);
      values.put("historyBinding", "binding");
      values.put("stepBinding", "binding");
      values.put("authority", "authority");
      values.put("cutAuthority", "authority");
      values.put("stepContext", "context");
      values.put("cutContext", "context");
      values.put("tool", "session_event_read");
      values.put("invocations", List.of(call));
    }

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, args) -> {
                String name = m.getName();
                seen.add(name + (args == null ? "" : ":" + args[0]));
                if (failures.containsKey(name)) throw failures.get(name);
                return values.containsKey(name)
                    ? values.get(name)
                    : m.getReturnType() == boolean.class ? false : null;
              });
    }

    void bind() {
      new SessionCallBindingServiceImpl().bind(records);
    }

    void corrupt() {
      var failure = assertThrows(TaskFailure.class, this::bind);
      assertEquals(409, failure.status());
      assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
    }
  }

  @Test
  void upperStopsBeforeBindingAccess() {
    var f = new Fixture();
    f.values.put("hasUpper", false);
    f.corrupt();
    assertEquals(List.of("lockHistory", "loadStep", "hasUpper"), f.seen);
  }

  @Test
  void bindingMismatchStopsBeforeInvocations() {
    var f = new Fixture();
    f.values.put("stepBinding", "other");
    f.corrupt();
    assertEquals("stepBinding", f.seen.getLast());
  }

  @Test
  void nullBindingKeepsOriginalNpeAfterStepGetter() {
    var f = new Fixture();
    f.values.put("historyBinding", null);
    assertThrows(NullPointerException.class, f::bind);
    assertEquals("stepBinding", f.seen.getLast());
  }

  @Test
  void scopeWinsAndRetainsInsertBeforeCharge() {
    var f = new Fixture();
    f.values.put("scopeTask", true);
    f.bind();
    assertEquals(
        List.of(
            "scopeTask",
            "requireScope",
            "normalizeScope",
            "loadCut",
            "authority",
            "cutAuthority",
            "stepContext",
            "cutContext",
            "insertScope",
            "chargeScope"),
        f.seen.subList(6, f.seen.size()));
  }

  @Test
  void authorityMismatchDoesNotReadContext() {
    var f = new Fixture();
    f.values.put("scopeTask", true);
    f.values.put("cutAuthority", "other");
    f.corrupt();
    assertEquals("cutAuthority", f.seen.getLast());
    assertFalse(f.seen.contains("stepContext"));
  }

  @Test
  void contextMismatchDoesNotInsert() {
    var f = new Fixture();
    f.values.put("scopeTask", true);
    f.values.put("cutContext", "other");
    f.corrupt();
    assertEquals("cutContext", f.seen.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"session_event_search", "session_event_trace"})
  void nativeVersionRefusesBeforeCodec(String tool) {
    var f = new Fixture();
    f.values.put("tool", tool);
    f.corrupt();
    assertEquals(tool.endsWith("trace") ? "traceTask" : "searchTask", f.seen.getLast());
    assertFalse(f.seen.contains("parseTrace"));
    assertFalse(f.seen.contains("normalizeSearch"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chargeSearch",
        "insertSearch",
        "parseTrace",
        "prepareRead",
        "requireScope",
        "normalizeScope",
        "insertScope",
        "chargeScope"
      })
  void firstExceptionPropagatesWithoutLaterEffects(String point) {
    var f = new Fixture();
    boolean scope = point.endsWith("Scope");
    f.values.put("scopeTask", scope);
    if (point.contains("Search")) {
      f.values.put("tool", "session_event_search");
      f.values.put("searchTask", true);
    }
    if (point.contains("Trace")) {
      f.values.put("tool", "session_event_trace");
      f.values.put("traceTask", true);
    }
    var original = new ClassCastException("original boundary");
    f.failures.put(point, original);
    assertSame(original, assertThrows(ClassCastException.class, f::bind));
    assertEquals(point, f.seen.getLast());
    if (point.equals("chargeSearch")) assertFalse(f.seen.contains("insertSearch"));
    if (point.equals("chargeScope")) assertTrue(f.seen.contains("insertScope"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void readPreparesAllGettersBeforeRelationVersion(boolean trace) {
    var f = new Fixture();
    f.values.put("traceTask", trace);
    f.bind();
    assertEquals(
        List.of("parseRead", "prepareRead", "traceTask", "insertRead:" + (trace ? 1 : 0)),
        f.seen.subList(f.seen.size() - 4, f.seen.size()));
  }

  @Test
  void invocationOrderAndFirstFailureAreNotRearranged() {
    var f = new Fixture();
    var second = f.proxy(SessionCallBindings.Invocation.class);
    f.values.put("invocations", List.of(f.call, second));
    f.bind();
    assertEquals(2, f.seen.stream().filter("insertRead:0"::equals).count());
    assertTrue(f.seen.indexOf("insertRead:0") < f.seen.lastIndexOf("parseRead"));
  }
}
