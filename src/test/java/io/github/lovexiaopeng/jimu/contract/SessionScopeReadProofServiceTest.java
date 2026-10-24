package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Binding;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Cut;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Endpoint;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Event;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Invocation;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Normalized;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Output;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Ref;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Refs;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Source;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Step;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeReadProofServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原槽/切点/收据/直接端点证明与短路顺序；不调用provider或递归证明。 */
class SessionScopeReadProofServiceTest {
  private final SessionScopeReadProofServiceImpl service = new SessionScopeReadProofServiceImpl();
  private final Map<String, Object> values = new HashMap<>();
  private final List<String> calls = new ArrayList<>();
  private String failAt;

  SessionScopeReadProofServiceTest() {
    values.put("event", new Event("read:inv", 5, "invocation", "data", "refs"));
    values.put(
        "invocation",
        new Invocation(
            "inv", "step", "session_event_read", "in", "hash:in", "SUCCEEDED", "out", "hash:out"));
    values.put(
        "binding",
        new Binding(
            "READ",
            "auth",
            "hash:context",
            "bind",
            "norm",
            "hash:norm",
            4,
            5L,
            "SUCCEEDED",
            "hash:in",
            "hash:out",
            3L));
    values.put("step", new Step("hash:context", "bind"));
    values.put("authority", "auth");
    values.put("cut", new Cut("auth", "hash:context", "context"));
    values.put("normalizedInput", "norm");
    values.put("sameJson", true);
    values.put("receipt", "data");
    values.put("refs", new Refs("refs", List.of(new Ref("source", 0, "hash:source"))));
    values.put("output", new Output("source", 0, 0, 0, "source", List.of(), List.of()));
    values.put("normalized", new Normalized("source", 0, 0, 0, 0));
    values.put("endpoints", List.of(new Endpoint(0, "source", "hash:source", 6)));
    values.put("source", new Source("source", 0, "source"));
    values.put("sourceSummary", "summary");
  }

  private Proof proof() {
    return (Proof)
        Proxy.newProxyInstance(
            Proof.class.getClassLoader(),
            new Class<?>[] {Proof.class},
            (p, m, a) -> {
              String name = m.getName();
              calls.add(name);
              if (name.equals(failAt)) throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
              if (name.equals("hash")) return "hash:" + a[0];
              if (name.equals("bytes")) return ((String) a[0]).length();
              if (name.equals("source") && values.containsKey("sources"))
                return ((Map<?, ?>) values.get("sources")).get(a[0]);
              return values.get(name);
            });
  }

  private void corrupt() {
    var e = assertThrows(TaskFailure.class, () -> service.verify(proof()));
    assertEquals(409, e.status());
    assertEquals("SESSION_HISTORY_CORRUPT", e.code());
  }

  @Test
  void provesBothSummarySidesAndTargetWithoutRecursiveExpansion() {
    values.put("normalized", new Normalized("source", 1, 1, 1, 2));
    values.put(
        "output", new Output("source", 2, 0, 2, "source", List.of("summary"), List.of("summary")));
    values.put(
        "refs",
        new Refs(
            "refs",
            List.of(
                new Ref("source", 0, "hash:before"),
                new Ref("source", 1, "hash:source"),
                new Ref("source", 2, "hash:after"))));
    values.put(
        "endpoints",
        List.of(
            new Endpoint(0, "before", "hash:before", 6),
            new Endpoint(1, "source", "hash:source", 6),
            new Endpoint(2, "after", "hash:after", 5)));
    values.put(
        "sources",
        Map.of(
            "before",
            new Source("source", 0, "before"),
            "source",
            new Source("source", 1, "source"),
            "after",
            new Source("source", 2, "after")));
    assertEquals("hash:in", service.verify(proof()));
    assertEquals(2, calls.stream().filter("sourceSummary"::equals).count());
  }

  @Test
  void finalTargetCanonicalDigestMustMatchStoredRef() {
    values.put("refs", new Refs("refs", List.of(new Ref("source", 0, "hash:raw-other"))));
    values.put("endpoints", List.of(new Endpoint(0, "raw-other", "hash:raw-other", 9)));
    corrupt();
    assertTrue(calls.contains("source"));
  }

  private static <T> T with(T record, String name, Object value) {
    try {
      var fields = record.getClass().getRecordComponents();
      var types = new Class<?>[fields.length];
      var args = new Object[fields.length];
      for (int i = 0; i < fields.length; i++) {
        types[i] = fields[i].getType();
        args[i] = fields[i].getName().equals(name) ? value : fields[i].getAccessor().invoke(record);
      }
      @SuppressWarnings("unchecked")
      T result = (T) record.getClass().getDeclaredConstructor(types).newInstance(args);
      return result;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private <T> void change(String key, String field, Object value) {
    values.put(key, with(values.get(key), field, value));
  }

  @Test
  void provesOriginalSlotAndDirectTarget() {
    assertEquals("hash:in", service.verify(proof()));
    assertEquals(2, calls.stream().filter("refs"::equals).count());
    assertFalse(calls.contains("sourceSummary"));
    assertTrue(calls.indexOf("validateStep") < calls.indexOf("authority"));
    assertTrue(calls.indexOf("cut") < calls.indexOf("normalizedInput"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "operation",
        "authority",
        "context",
        "binding",
        "normalizedDigest",
        "normalizedBytes",
        "nativeSeq",
        "outcome",
        "inputDigest",
        "outputDigest",
        "outputBytes"
      })
  void bindingMismatchesReject(String field) {
    Object wrong =
        field.equals("normalizedBytes")
            ? 99L
            : field.equals("nativeSeq") || field.equals("outputBytes") ? Long.valueOf(99) : "wrong";
    change("binding", field, wrong);
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"tool", "inputDigest", "outputDigest"})
  void invocationMismatchesReject(String field) {
    change("invocation", field, "wrong");
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"fact", "sourceKind", "data", "refs", "seq"})
  void eventMismatchesReject(String field) {
    change("event", field, field.equals("seq") ? 99L : "wrong");
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"authority", "context", "rawContext"})
  void cutMismatchesReject(String field) {
    change("cut", field, "wrong");
    corrupt();
  }

  @Test
  void normalizedMismatchRejectsBeforeReceipt() {
    values.put("sameJson", false);
    corrupt();
    assertFalse(calls.contains("receipt"));
  }

  @Test
  void failedReadRequiresEmptyRefsNullOutputZeroBytes() {
    change("invocation", "state", "FAILED");
    change("invocation", "output", null);
    change("binding", "outcome", "FAILED");
    change("binding", "outputBytes", 0L);
    values.put("refs", new Refs("refs", List.of()));
    assertEquals("hash:in", service.verify(proof()));
    assertFalse(calls.contains("endpoints"));
    assertFalse(calls.contains("validateOutput"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"refs", "output", "bytes"})
  void failedReadRejectsResidualFacts(String field) {
    change("invocation", "state", "FAILED");
    change("binding", "outcome", "FAILED");
    change("invocation", "output", field.equals("output") ? "out" : null);
    change("binding", "outputBytes", field.equals("bytes") ? 3L : 0L);
    if (!field.equals("refs")) values.put("refs", new Refs("refs", List.of()));
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"session", "upper", "from", "to", "before", "after", "target"})
  void outputWindowAndTargetProofReject(String field) {
    Object wrong =
        List.of("before", "after").contains(field)
            ? List.of("unexpected")
            : List.of("upper", "from", "to").contains(field) ? 99L : "wrong";
    change("output", field, wrong);
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"seq", "raw", "digest", "bytes", "count"})
  void endpointProofRejects(String field) {
    var endpoint = new Endpoint(0, "source", "hash:source", 6);
    values.put(
        "endpoints",
        field.equals("count")
            ? List.of()
            : List.of(
                with(endpoint, field, List.of("seq", "bytes").contains(field) ? 99L : "wrong")));
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"session", "seq", "canonical"})
  void sourceProofRejects(String field) {
    change("source", field, field.equals("seq") ? 99L : "wrong");
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invocation",
        "binding",
        "step",
        "validateStep",
        "authority",
        "cut",
        "normalizedInput",
        "refs",
        "endpoints",
        "validateEvent"
      })
  void failuresPropagateWithoutFallback(String method) {
    failAt = method;
    var e = assertThrows(TaskFailure.class, () -> service.verify(proof()));
    assertEquals(504, e.status());
    assertEquals("SESSION_QUERY_TIMEOUT", e.code());
  }

  @Test
  void endpointKeyRefusalPrecedesSummaryLimit() {
    values.put("normalized", new Normalized("source", 1, 1, 0, 1));
    values.put("output", new Output("source", 1, 0, 1, "source", List.of("summary"), List.of()));
    values.put(
        "refs",
        new Refs(
            "refs",
            List.of(new Ref("source", 0, "hash:source"), new Ref("source", 1, "hash:source"))));
    values.put(
        "endpoints",
        List.of(
            new Endpoint(0, "source", "hash:source", 6),
            new Endpoint(1, "source", "hash:source", 6)));
    change("source", "session", "other");
    failAt = "sourceSummary";
    corrupt();
    assertFalse(calls.contains("sourceSummary"));
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 1, 127})
  void outOfWindowRefRetainsCorruptFailureBeforeProjection(long seq) {
    values.put("refs", new Refs("refs", List.of(new Ref("source", seq, "hash:source"))));
    corrupt();
    assertFalse(calls.contains("validateEvent"));
  }
}
