package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEligibility;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionEligibilityServiceTest {
  private final SessionEligibilityServiceImpl service = new SessionEligibilityServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Function<Object[], Object>> values = new HashMap<>();

  private <T> T port(Class<T> type, String prefix) {
    return type.cast(
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              String key = prefix + m.getName();
              calls.add(key + (a != null && a.length > 0 ? ":" + a[0] : ""));
              var value = values.get(key);
              return value == null ? null : value.apply(a);
            }));
  }

  private void value(String name, Object value) {
    values.put(name, a -> value);
  }

  private ToolDescriptor descriptor(String version, String digest) {
    return new ToolDescriptor("plugin", version, "cap", "in", "out", digest, "entry");
  }

  private SessionEligibility.Access access() {
    value("session", true);
    value("capabilities", List.of("b", "a"));
    value("descriptor", descriptor("v", "d"));
    value("pin", new PluginPin("v", "d", 9));
    value("installation", port(SessionEligibility.Installation.class, "i."));
    value("i.version", "v");
    value("i.digest", "d");
    value("i.epoch", 9L);
    return port(SessionEligibility.Access.class, "");
  }

  @Test
  void sortedCapabilitiesKeepDescriptorSnapshotThenLockForEveryItem() {
    service.access(access());
    assertEquals(
        List.of(
            "session",
            "history",
            "capabilities",
            "descriptor:a",
            "pin:a",
            "installation:a",
            "i.version",
            "i.digest",
            "i.epoch",
            "descriptor:b",
            "pin:b",
            "installation:b",
            "i.version",
            "i.digest",
            "i.epoch"),
        calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void fivePinChecksPreserveShortCircuitAndLazyNumericRead(int mismatch) {
    var port = access();
    if (mismatch == 1) value("i.version", "other");
    if (mismatch == 2) value("i.digest", "other");
    if (mismatch == 3) value("i.epoch", 10L);
    if (mismatch == 4) value("descriptor", descriptor("other", "d"));
    if (mismatch == 5) value("descriptor", descriptor("v", "other"));
    var error = assertThrows(TaskFailure.class, () -> service.access(port));
    assertEquals("CAPABILITY_REVOKED", error.getMessage());
    assertEquals(403, error.status());
    var expected =
        new ArrayList<>(
            List.of(
                "session",
                "history",
                "capabilities",
                "descriptor:a",
                "pin:a",
                "installation:a",
                "i.version"));
    if (mismatch >= 2) expected.add("i.digest");
    if (mismatch >= 3) expected.add("i.epoch");
    assertEquals(expected, calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pin", "version", "epoch"})
  void nullPinAndNumericValuesFailOnlyAfterTheirOriginalPredecessors(String kind) {
    var port = access();
    if (kind.equals("pin")) value("pin", null);
    if (kind.equals("version")) value("pin", new PluginPin(null, "d", 9));
    if (kind.equals("epoch")) value("i.epoch", null);
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(
        kind.equals("epoch") ? "i.epoch" : kind.equals("version") ? "i.version" : "installation:a",
        calls.getLast());
  }

  @Test
  void nonSessionStopsBeforeHistoryAndFactDecoding() {
    var port = access();
    value("session", false);
    assertEquals(
        "SESSION_HISTORY_UNAVAILABLE",
        assertThrows(TaskFailure.class, () -> service.access(port)).getMessage());
    assertEquals(List.of("session"), calls);
  }

  @Test
  void descriptorAndSnapshotFailurePrecedeInstallation() {
    for (String key : List.of("descriptor", "pin")) {
      calls.clear();
      var port = access();
      values.put(
          key,
          a -> {
            throw new IllegalArgumentException(key);
          });
      assertEquals(
          key,
          assertThrows(IllegalArgumentException.class, () -> service.access(port)).getMessage());
      assertEquals(key + ":a", calls.getLast());
    }
  }

  private SessionEligibility.Admission admission(String kind) {
    value("readEnabled", true);
    value("scopeEnabled", true);
    value("searchEnabled", true);
    value("traceEnabled", true);
    value("scope", kind.equals("scope"));
    value("search", kind.equals("search") || kind.equals("trace"));
    value("trace", kind.equals("trace"));
    return port(SessionEligibility.Admission.class, "");
  }

  @ParameterizedTest
  @ValueSource(strings = {"read", "scope", "search", "trace"})
  void selectedModelBranchKeepsRepeatedPredicatesAndScopeUsesRead(String kind) {
    service.admission(admission(kind));
    var expected = new ArrayList<>(List.of("readEnabled", "scope"));
    if (kind.equals("scope")) expected.add("scopeEnabled");
    expected.add("search");
    if (kind.equals("search") || kind.equals("trace")) expected.add("searchEnabled");
    expected.add("trace");
    if (kind.equals("trace")) expected.add("traceEnabled");
    expected.addAll(List.of("history", "trace"));
    if (!kind.equals("trace")) expected.add("search");
    expected.add("snapshot");
    expected.add(
        "require"
            + (kind.equals("trace") ? "Trace" : kind.equals("search") ? "Search" : "Read")
            + ":null");
    assertEquals(expected, calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"read", "scope", "search", "trace"})
  void disabledFlagsDoNotReadHistoryOrSnapshot(String kind) {
    var port = admission(kind);
    value(kind + "Enabled", false);
    var failure = assertThrows(TaskFailure.class, () -> service.admission(port));
    assertEquals(
        kind.equals("read")
            ? "SESSION_READ_DISABLED"
            : kind.equals("trace") ? "SESSION_TRACE_DISABLED" : "SESSION_SEARCH_DISABLED",
        failure.getMessage());
    assertEquals(kind + "Enabled", calls.getLast());
  }

  @Test
  void historyFailurePrecedesSnapshotAndModelRequirement() {
    var port = admission("scope");
    values.put(
        "history",
        a -> {
          throw new IllegalStateException("history");
        });
    assertEquals(
        "history",
        assertThrows(IllegalStateException.class, () -> service.admission(port)).getMessage());
    assertEquals("history", calls.getLast());
  }

  @Test
  void snapshotFailureRemainsBeforeSelectedRequireAndNoSessionGuardIsAdded() {
    var port = admission("untyped");
    values.put(
        "snapshot",
        a -> {
          throw new IllegalArgumentException("snapshot");
        });
    assertEquals(
        "snapshot",
        assertThrows(IllegalArgumentException.class, () -> service.admission(port)).getMessage());
    assertEquals("snapshot", calls.getLast());
  }
}
