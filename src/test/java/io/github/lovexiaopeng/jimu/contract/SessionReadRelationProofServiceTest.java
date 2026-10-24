package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReadRelationProofs;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionReadRelationProofServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原生来源严格有序、原槽与pin和直接端点证明；保留短路及原失败边界。 */
class SessionReadRelationProofServiceTest {
  private static final String D = "a".repeat(64);
  private final SessionReadRelationProofServiceImpl service =
      new SessionReadRelationProofServiceImpl();
  private final Map<String, Object> values = new HashMap<>();
  private final List<String> calls = new ArrayList<>();
  private final TaskFailure timeout = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
  private String failAt;

  SessionReadRelationProofServiceTest() {
    values.put(
        "sourceBinding",
        new SessionReadRelationProofs.SourceBinding(1L, "PRESENT", "[]", 2L, "hash:[]"));
    values.put(
        "sourceDocument",
        new SessionReadRelationProofs.SourceDocument(true, 1, List.of(source(0))));
    values.put("sequences", "[0]");
    values.put("eventSequences", "[0]");
    values.put("step", new SessionReadRelationProofs.Step(0L, "response", "hash:response"));
    values.put("historyBinding", "binding");
    values.put(
        "invocation",
        new SessionReadRelationProofs.Invocation(
            "{}", "hash:{}", "session_event_read", "0:call", "call", "output", "0.3.0", "pin", 1L));
    values.put(
        "binding",
        new SessionReadRelationProofs.Binding(
            "binding", 0L, "hash:{}", 0L, 0L, 0L, "SUCCEEDED", 1L, 0L, 0L, D));
    values.put("input", new SessionReadRelationProofs.Input(null, 0, 0, 0));
    values.put("session", "task");
    values.put(
        "response",
        new SessionReadRelationProofs.Response(
            2, List.of(new SessionReadRelationProofs.Call("call", "session_event_read", "{}"))));
    values.put("pin", new SessionReadRelationProofs.Pin("0.3.0", "pin", 1));
    values.put(
        "output",
        new SessionReadRelationProofs.Output(0, "pre-step", "task", 0, 0, 0, 0, 0, "target"));
    values.put("eventSeq", 3L);
    values.put("endpointDigest", D);
  }

  private static SessionReadRelationProofs.Source source(long seq) {
    return new SessionReadRelationProofs.Source(true, 2, true, seq, D);
  }

  private SessionReadRelationProofs.Proof proof() {
    return (SessionReadRelationProofs.Proof)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionReadRelationProofs.Proof.class},
            (p, m, a) -> {
              calls.add(m.getName());
              if (m.getName().equals(failAt)) throw timeout;
              if (m.getName().equals("hash")) return "target".equals(a[0]) ? D : "hash:" + a[0];
              if (m.getName().equals("bytes")) return ((String) a[0]).length();
              return values.get(m.getName());
            });
  }

  private void change(String key, String field, Object next) throws Exception {
    Object original = values.get(key);
    var components = original.getClass().getRecordComponents();
    var types = new Class<?>[components.length];
    var arguments = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      arguments[i] =
          components[i].getName().equals(field)
              ? next
              : components[i].getAccessor().invoke(original);
      if (arguments[i] instanceof Number number) {
        if (types[i] == int.class) arguments[i] = number.intValue();
        else if (types[i] == long.class) arguments[i] = number.longValue();
      }
    }
    values.put(key, original.getClass().getDeclaredConstructor(types).newInstance(arguments));
  }

  private void corrupt() {
    var failure = assertThrows(TaskFailure.class, () -> service.verify(proof()));
    assertEquals(409, failure.status());
    assertEquals("SESSION_HISTORY_CORRUPT", failure.code());
  }

  private void sourceDocument(SessionReadRelationProofs.Source... sources) {
    values.put(
        "sourceDocument",
        new SessionReadRelationProofs.SourceDocument(true, sources.length, List.of(sources)));
  }

  private void failed() throws Exception {
    sourceDocument();
    values.put("sequences", "[]");
    values.put("eventSequences", "[]");
    change("binding", "outcome", "FAILED");
    change("binding", "outputBytes", 0L);
    change("invocation", "output", null);
  }

  @Test
  void orderedProofUsesOnlyDirectDigestEndpoints() {
    service.verify(proof());
    for (String[] pair :
        List.of(
            new String[] {"sourceDocument", "step"},
            new String[] {"validateStep", "historyBinding"},
            new String[] {"input", "response"},
            new String[] {"response", "pin"},
            new String[] {"pin", "validateOutput"},
            new String[] {"output", "endpointDigest"}))
      assertTrue(calls.indexOf(pair[0]) < calls.indexOf(pair[1]));
    assertEquals(1, calls.stream().filter("sourceBinding"::equals).count());
    assertEquals(1, calls.stream().filter("endpointDigest"::equals).count());
  }

  @Test
  void provesBothSidesAndTargetInOriginalEndpointOrder() throws Exception {
    sourceDocument(source(0), source(1), source(2));
    change("input", "seq", 1L);
    change("input", "before", 1);
    change("input", "after", 1);
    change("binding", "target", 1L);
    change("binding", "before", 1L);
    change("binding", "after", 1L);
    change("binding", "upper", 2L);
    change("binding", "end", 2L);
    change("step", "upper", 2L);
    values.put(
        "output",
        new SessionReadRelationProofs.Output(2, "pre-step", "task", 1, 0, 2, 1, 1, "target"));
    service.verify(proof());
    assertEquals(3, calls.stream().filter("endpointDigest"::equals).count());
  }

  @Test
  void allowsNineStrictlyOrderedSourcesWithoutReplayingThem() {
    var sources =
        LongStream.range(0, 9).mapToObj(SessionReadRelationProofServiceTest::source).toList();
    values.put("sourceDocument", new SessionReadRelationProofs.SourceDocument(true, 9, sources));
    assertEquals(9, service.sources(proof()).size());
    assertFalse(calls.contains("step"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "state", "raw", "bytes", "digest"})
  void sourceSnapshotRefusesBeforeStep(String field) throws Exception {
    change(
        "sourceBinding",
        field,
        switch (field) {
          case "version" -> 2L;
          case "bytes" -> 3L;
          case "raw" -> null;
          default -> "other";
        });
    corrupt();
    assertFalse(calls.contains("step"));
    assertFalse(calls.contains("sourceDocument"));
  }

  @Test
  void sourceByteLimitPrecedesDigestAndDecode() throws Exception {
    change("sourceBinding", "raw", "x".repeat(1025));
    corrupt();
    assertFalse(calls.contains("hash"));
  }

  @Test
  void rejectsNonArrayBeforeSourceChecks() throws Exception {
    change("sourceDocument", "array", false);
    corrupt();
    assertFalse(calls.contains("step"));
  }

  @Test
  void rejectsMoreThanNineSources() throws Exception {
    change("sourceDocument", "size", 10);
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(strings = {"object", "fields", "integral", "negative", "upper", "digest"})
  void rejectsMalformedSource(String field) {
    sourceDocument(
        new SessionReadRelationProofs.Source(
            !field.equals("object"),
            field.equals("fields") ? 3 : 2,
            !field.equals("integral"),
            field.equals("negative") ? -1 : field.equals("upper") ? 128 : 0,
            field.equals("digest") ? "A".repeat(64) : D));
    corrupt();
    assertFalse(calls.contains("step"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsDuplicateOrDescendingSources(long second) {
    sourceDocument(source(0), source(second));
    corrupt();
    assertFalse(calls.contains("step"));
  }

  @Test
  void eventSequenceProofPrecedesStepLookup() {
    values.put("eventSequences", "[]");
    corrupt();
    assertFalse(calls.contains("step"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "binding",
        "upper",
        "inputDigest",
        "target",
        "before",
        "after",
        "outcome",
        "start",
        "end",
        "targetDigest"
      })
  void bindingRelationsRefuse(String field) throws Exception {
    change(
        "binding",
        field,
        List.of("upper", "target", "before", "after", "start", "end").contains(field)
            ? 1L
            : "other");
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "inputDigest",
        "tool",
        "slot",
        "provider",
        "pluginVersion",
        "pluginDigest",
        "epoch"
      })
  void originalSlotAndPinMustMatch(String field) throws Exception {
    change("invocation", field, field.equals("epoch") ? 2L : "other");
    corrupt();
    assertFalse(calls.contains("validateOutput"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"session", "seq", "before", "after"})
  void inputMustMatchStoredReadAndCurrentSession(String field) throws Exception {
    change("input", field, field.equals("session") ? "other" : field.equals("seq") ? 1L : 1);
    corrupt();
    assertFalse(calls.contains("response"));
  }

  @Test
  void responseDigestPrecedesDecoding() throws Exception {
    change("step", "responseDigest", "other");
    corrupt();
    assertFalse(calls.contains("response"));
  }

  @Test
  void responseVersionRequiresOriginalTwo() throws Exception {
    change("response", "version", 1);
    corrupt();
  }

  @Test
  void missingOriginalCallCannotSkipPinProof() throws Exception {
    change("response", "calls", List.of());
    corrupt();
    assertTrue(calls.contains("pin"));
  }

  @Test
  void callArgumentsMustMatchOriginalInput() throws Exception {
    change(
        "response",
        "calls",
        List.of(new SessionReadRelationProofs.Call("call", "session_event_read", "other")));
    corrupt();
  }

  @Test
  void missingPinRefuses() {
    values.put("pin", null);
    corrupt();
  }

  @Test
  void failedReadStillProvesSlotAndPinButSkipsOutput() throws Exception {
    failed();
    service.verify(proof());
    assertTrue(calls.contains("pin"));
    assertFalse(calls.contains("validateOutput"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sources", "output", "bytes"})
  void failedReadCannotLeaveResidualOutput(String field) throws Exception {
    failed();
    if (field.equals("sources")) sourceDocument(source(0));
    else if (field.equals("output")) change("invocation", "output", "other");
    else change("binding", "outputBytes", 1L);
    corrupt();
    assertFalse(calls.contains("validateOutput"));
  }

  @Test
  void successRequiresSources() {
    sourceDocument();
    corrupt();
    assertFalse(calls.contains("validateOutput"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"upper", "boundary", "session", "targetSeq", "from", "to", "before", "after"})
  void outputWindowMustMatchOriginalCut(String field) throws Exception {
    Object next =
        List.of("boundary", "session").contains(field)
            ? "other"
            : List.of("before", "after").contains(field) ? 1 : 1L;
    change("output", field, next);
    corrupt();
    assertFalse(calls.contains("endpointDigest"));
  }

  @Test
  void sparseSourcesRefuseBeforeDirectLookup() {
    sourceDocument(source(1));
    corrupt();
    assertFalse(calls.contains("endpointDigest"));
  }

  @Test
  void futureEndpointRefusesBeforeLookup() {
    values.put("eventSeq", 0L);
    corrupt();
    assertFalse(calls.contains("endpointDigest"));
  }

  @Test
  void endpointDigestMustMatchStoredSource() {
    values.put("endpointDigest", "other");
    corrupt();
  }

  @Test
  void canonicalTargetDigestMustMatch() throws Exception {
    change("output", "target", "other");
    corrupt();
  }

  @Test
  void nullableFenceLikeEpochRetainsOriginalUnboxingBoundary() throws Exception {
    change("invocation", "epoch", null);
    assertThrows(NullPointerException.class, () -> service.verify(proof()));
  }

  @Test
  void priorHistoryRefusalShortCircuitsNullUpper() throws Exception {
    values.put("historyBinding", "other");
    change("step", "upper", null);
    corrupt();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sourceBinding",
        "step",
        "validateStep",
        "historyBinding",
        "input",
        "response",
        "pin",
        "validateOutput",
        "endpointDigest"
      })
  void originalFailureAndDeadlinePropagateUnchanged(String operation) {
    failAt = operation;
    assertSame(timeout, assertThrows(TaskFailure.class, () -> service.verify(proof())));
  }
}
