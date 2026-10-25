package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionAvailability;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionAvailabilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionAvailabilityServiceTest {
  private final SessionAvailabilityServiceImpl service = new SessionAvailabilityServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Function<Object[], Object>> values = new HashMap<>();

  private <T> T port(Class<T> type, String prefix) {
    return type.cast(
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              calls.add(
                  prefix
                      + m.getName()
                      + (a == null || m.getName().startsWith("require") ? "" : ":" + a[0]));
              var value = values.get(prefix + m.getName());
              return value == null ? null : value.apply(a);
            }));
  }

  private void value(String key, Object value) {
    values.put(key, a -> value);
  }

  private ToolDescriptor descriptor(String capability, String version, String digest) {
    return new ToolDescriptor("p", version, capability, "i", "o", digest, "e");
  }

  private SessionAvailability records() {
    for (String flag : List.of("readEnabled", "searchEnabled", "traceEnabled", "priorEnabled"))
      value(flag, true);
    for (String name : List.of("traceRead", "traceSearch", "trace", "priorRead", "prior"))
      value(name, descriptor(name, "v", "d"));
    value("readDescriptor", descriptor("read", "v", "d"));
    value("searchDescriptor", descriptor("search", "v", "d"));
    var installed = port(SessionAvailability.Installation.class, "i.");
    value("installed", List.of(installed));
    value("readInstallation", installed);
    value("searchInstallation", installed);
    value("i.version", "v");
    value("i.digest", "d");
    return port(SessionAvailability.class, "");
  }

  @Test
  void traceDescriptorsAllPrecedeFirstQueryAndEachRowIsReadLazily() {
    assertTrue(service.trace(records()));
    assertEquals(
        List.of(
            "readEnabled",
            "searchEnabled",
            "traceEnabled",
            "selected",
            "requireTrace",
            "traceRead",
            "traceSearch",
            "trace",
            "installed:traceRead",
            "i.version",
            "i.digest",
            "installed:traceSearch",
            "i.version",
            "i.digest",
            "installed:trace",
            "i.version",
            "i.digest"),
        calls);
  }

  @Test
  void priorDescriptorsBothPrecedeFirstQuery() {
    assertTrue(service.prior(records()));
    assertEquals(
        List.of(
            "readEnabled",
            "priorEnabled",
            "selected",
            "requireSearch",
            "priorRead",
            "prior",
            "installed:priorRead",
            "i.version",
            "i.digest",
            "installed:prior",
            "i.version",
            "i.digest"),
        calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"readEnabled", "searchEnabled", "traceEnabled"})
  void traceDisabledFlagsShortCircuitOutsideTry(String flag) {
    var r = records();
    value(flag, false);
    assertFalse(service.trace(r));
    assertEquals(
        List.of("readEnabled", "searchEnabled", "traceEnabled")
            .subList(0, List.of("readEnabled", "searchEnabled", "traceEnabled").indexOf(flag) + 1),
        calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"readEnabled", "priorEnabled"})
  void priorDisabledFlagsStopBeforeModel(String flag) {
    var r = records();
    value(flag, false);
    assertFalse(service.prior(r));
    assertEquals(flag.equals("readEnabled") ? List.of(flag) : List.of("readEnabled", flag), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"traceRead", "traceSearch", "trace", "priorRead", "prior"})
  void listOfNullStillEvaluatesAllDescriptorArgumentsBeforeFirstQuery(String name) {
    var r = records();
    value(name, null);
    assertThrows(
        NullPointerException.class,
        () -> {
          if (name.startsWith("prior")) service.prior(r);
          else service.trace(r);
        });
    assertFalse(calls.stream().anyMatch(s -> s.startsWith("installed")));
    assertEquals(name.startsWith("prior") ? "prior" : "trace", calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void traceAndPriorRejectCardinalityWithoutReadingFirstRow(int size) {
    var r = records();
    value("installed", Collections.nCopies(size, (SessionAvailability.Installation) null));
    assertFalse(service.trace(r));
    assertFalse(service.prior(r));
    assertFalse(calls.contains("i.version"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "digest"})
  void comparisonMismatchStopsBeforeLaterQueriesAndDigestIsConditional(String field) {
    var r = records();
    value("i." + field, "bad");
    assertFalse(service.trace(r));
    assertEquals(1, calls.stream().filter(s -> s.startsWith("installed:")).count());
    assertEquals(field.equals("digest"), calls.contains("i.digest"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "digest"})
  void nullDescriptorValueEvaluatesInstalledArgumentBeforeThrowing(String field) {
    var r = records();
    value(
        "traceRead",
        descriptor("a", field.equals("version") ? null : "v", field.equals("digest") ? null : "d"));
    assertThrows(NullPointerException.class, () -> service.trace(r));
    assertEquals("i." + field, calls.getLast());
  }

  @Test
  void nullInstalledStringIsMismatchAndDoesNotReadDigest() {
    var r = records();
    value("i.version", null);
    assertFalse(service.trace(r));
    assertFalse(calls.contains("i.digest"));
  }

  @Test
  void onlyTaskFailureInsideTryIsCaughtAndDescriptorConstructionStopsInOrder() {
    var r = records();
    values.put(
        "traceSearch",
        a -> {
          throw new TaskFailure(403, "no");
        });
    assertFalse(service.trace(r));
    assertEquals("traceSearch", calls.getLast());
    values.put(
        "traceSearch",
        a -> {
          throw new IllegalStateException("original");
        });
    assertEquals(
        "original", assertThrows(IllegalStateException.class, () -> service.trace(r)).getMessage());
  }

  @Test
  void flagTaskFailureIsOutsideCatchIncludingPriorProbeOutsideSessionTry() {
    var r = records();
    values.put(
        "priorEnabled",
        a -> {
          throw new TaskFailure(403, "flag");
        });
    assertThrows(TaskFailure.class, () -> service.session(r, false));
    assertEquals(List.of("readEnabled", "priorEnabled"), calls);
    values.put(
        "readEnabled",
        a -> {
          throw new TaskFailure(403, "flag");
        });
    assertThrows(TaskFailure.class, () -> service.trace(r));
  }

  @Test
  void modelTaskFailureIsCaughtBeforeDescriptorsButSqlRuntimePropagates() {
    var r = records();
    values.put(
        "requireTrace",
        a -> {
          throw new TaskFailure(403, "model");
        });
    assertFalse(service.trace(r));
    assertEquals("requireTrace", calls.getLast());
    values.remove("requireTrace");
    values.put(
        "installed",
        a -> {
          throw new IllegalArgumentException("SQL-wrapper");
        });
    assertThrows(IllegalArgumentException.class, () -> service.trace(r));
  }

  @Test
  void successfulPriorReturnsImmediatelyWithoutNativeReadProbe() {
    assertTrue(service.session(records(), false));
    assertFalse(calls.contains("requireRead"));
    assertFalse(calls.contains("readInstallation"));
  }

  @Test
  void nativeReadSuccessOnlyAfterPriorFailureUsesFirstRowBeforeDescriptor() {
    var r = records();
    value("priorEnabled", false);
    assertTrue(service.session(r, false));
    assertEquals(
        List.of(
            "readEnabled",
            "priorEnabled",
            "readEnabled",
            "selected",
            "requireRead",
            "readInstallation",
            "readDescriptor:false",
            "i.version",
            "i.digest"),
        calls);
  }

  @Test
  void readMissingOrModelRefusalDoesNotFallbackOrConstructDescriptor() {
    var r = records();
    values.put(
        "readInstallation",
        a -> {
          throw new TaskFailure(404, "NOT_FOUND");
        });
    assertFalse(service.session(r, true));
    assertEquals("readInstallation", calls.getLast());
    assertFalse(calls.contains("readDescriptor:true"));
    calls.clear();
    values.put(
        "requireSearch",
        a -> {
          throw new TaskFailure(403, "MODEL");
        });
    assertFalse(service.session(r, true));
    assertEquals("requireSearch", calls.getLast());
  }

  @Test
  void nativeMismatchRecursesAndReselectsSearchWithTwoConditionalDescriptors() {
    var r = records();
    value("priorEnabled", false);
    values.put(
        "readDescriptor", a -> descriptor("read", Boolean.TRUE.equals(a[0]) ? "v" : "old", "d"));
    assertTrue(service.session(r, false));
    assertEquals(
        List.of(
            "readEnabled",
            "priorEnabled",
            "readEnabled",
            "selected",
            "requireRead",
            "readInstallation",
            "readDescriptor:false",
            "i.version",
            "readEnabled",
            "searchEnabled",
            "selected",
            "requireSearch",
            "readInstallation",
            "readDescriptor:true",
            "i.version",
            "i.digest",
            "searchInstallation",
            "searchDescriptor",
            "i.version",
            "searchDescriptor",
            "i.digest"),
        calls);
  }

  @Test
  void onlyMismatchedReadFallsBackToTraceAndReselectsModel() {
    var r = records();
    value("readDescriptor", descriptor("read", "old", "d"));
    assertTrue(service.session(r, true));
    assertEquals(1, calls.stream().filter("requireSearch"::equals).count());
    assertEquals(1, calls.stream().filter("requireTrace"::equals).count());
    assertFalse(calls.contains("searchInstallation"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "version", "digest", "null-version"})
  void finalSearchFailureNeverFallsBackAndSecondDescriptorIsConditional(String kind) {
    var r = records();
    var installed = port(SessionAvailability.Installation.class, "s.");
    value("searchInstallation", installed);
    value("s.version", kind.equals("version") ? "bad" : "v");
    value("s.digest", kind.equals("digest") ? "bad" : "d");
    if (kind.equals("missing"))
      values.put(
          "searchInstallation",
          a -> {
            throw new TaskFailure(404, "NOT_FOUND");
          });
    if (kind.equals("null-version")) value("searchDescriptor", descriptor("s", null, "d"));
    if (kind.equals("null-version"))
      assertThrows(NullPointerException.class, () -> service.session(r, true));
    else assertFalse(service.session(r, true));
    assertFalse(calls.contains("requireTrace"));
    assertEquals(
        kind.equals("missing") ? 0 : kind.equals("digest") ? 2 : 1,
        calls.stream().filter("searchDescriptor"::equals).count());
  }

  @Test
  void nonTaskFailureInPriorOrInstallationGetterPropagates() {
    var r = records();
    values.put(
        "requireSearch",
        a -> {
          throw new NullPointerException("model");
        });
    assertThrows(NullPointerException.class, () -> service.session(r, false));
    values.remove("requireSearch");
    values.put(
        "i.version",
        a -> {
          throw new ClassCastException("original-column");
        });
    assertThrows(ClassCastException.class, () -> service.session(r, true));
  }

  @Test
  void selectedFailurePrecedesRequireAndRemainsWithinOriginalCatch() {
    var r = records();
    values.put(
        "selected",
        a -> {
          throw new TaskFailure(503, "SELECTED");
        });
    assertFalse(service.trace(r));
    assertEquals("selected", calls.getLast());
    assertFalse(calls.contains("requireTrace"));
    calls.clear();
    values.put(
        "selected",
        a -> {
          throw new NullPointerException("original-selected");
        });
    assertThrows(NullPointerException.class, () -> service.prior(r));
    assertFalse(calls.contains("requireSearch"));
  }
}
