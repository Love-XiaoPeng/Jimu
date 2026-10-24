package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Document;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Invocation;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Read;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Step;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Stored;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Subject;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Terminal;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionEventProofServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 五种来源及拒绝顺序、scope计量、trace400映射和NULL证明边界。 */
class SessionEventProofServiceTest {
  private final SessionEventProofServiceImpl service = new SessionEventProofServiceImpl();
  private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 25, 0, 0);
  private Subject subject = new Subject("tenant", "task", 3, false, true, "request");
  private Stored stored;
  private Document document;
  private final List<String> log = new ArrayList<>();
  private final Map<String, Object> values = new java.util.HashMap<>();
  private String failAt, failCode;
  private int failStatus;

  private void event(String type) {
    long seq = type.equals("user.message") ? 0 : 1;
    String fact =
        switch (type) {
          case "user.message" -> "accepted-user";
          case "step.started" -> "step:step";
          case "model.response" -> "response:step";
          case "session.read" -> "read:inv";
          default -> "terminal";
        };
    String digest =
        switch (type) {
          case "user.message" -> "request";
          case "step.started" -> "hash:req";
          case "model.response" -> "hash:res";
          case "session.read" -> "input";
          default -> "hash:terminal";
        };
    String step = type.equals("user.message") || type.equals("turn.ended") ? null : "step";
    String inv = type.equals("session.read") ? "inv" : null;
    stored =
        new Stored(
            "tenant",
            "task",
            1,
            3,
            "PRESENT",
            "raw",
            3,
            "hash:raw",
            seq,
            type,
            TIME,
            step,
            inv,
            fact,
            digest,
            7,
            "attempt",
            1L);
    document =
        new Document(
            true,
            3,
            type,
            "task",
            seq,
            "2026-10-25T00:00:00.000000Z",
            step,
            inv,
            type.equals("model.response")
                ? "model"
                : type.equals("session.read") ? "invocation" : "step",
            2,
            "SUCCEEDED",
            null,
            "data",
            true,
            true);
    values.put("step", new Step("step", 2, "req", "hash:req", "res", "hash:res"));
    values.put("invocation", new Invocation("inv", "SUCCEEDED", "input", "output", "hash:output"));
    values.put("read", new Read(1L, "SUCCEEDED", "hash:output", 6L));
    values.put("readReceipt", "data");
    values.put("scopeRead", "input");
    values.put("terminal", new Terminal("SUCCEEDED", null, "hash:terminal"));
    values.put("reliableType", "session_history.changed");
    values.put("sourceMatches", true);
  }

  private Proof proof() {
    return (Proof)
        Proxy.newProxyInstance(
            Proof.class.getClassLoader(),
            new Class<?>[] {Proof.class},
            (p, m, args) -> {
              String name = m.getName();
              log.add(name);
              if (name.equals(failAt)) throw new TaskFailure(failStatus, failCode);
              return switch (name) {
                case "subject" -> subject;
                case "stored" -> stored;
                case "decode" -> document;
                case "hash" -> "hash:" + args[0];
                case "bytes" -> ((String) args[0]).length();
                default -> values.get(name);
              };
            });
  }

  @SuppressWarnings("unchecked")
  private static <T extends Record> T with(T row, String field, Object value) {
    try {
      var parts = row.getClass().getRecordComponents();
      var types = new Class<?>[parts.length];
      var args = new Object[parts.length];
      for (int i = 0; i < parts.length; i++) {
        types[i] = parts[i].getType();
        args[i] = parts[i].getName().equals(field) ? value : parts[i].getAccessor().invoke(row);
      }
      return (T) row.getClass().getDeclaredConstructor(types).newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private TaskFailure rejects(String code) {
    var failure = assertThrows(TaskFailure.class, () -> service.verify(proof()));
    assertEquals(code, failure.code());
    return failure;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"user.message", "step.started", "model.response", "session.read", "turn.ended"})
  void everySourceProvesReliableEventThenAttempt(String type) {
    event(type);
    service.verify(proof());
    assertTrue(log.indexOf("validateDocument") < log.indexOf("reliableType"));
    assertTrue(log.indexOf("reliableType") < log.indexOf("requireAttempt"));
    assertFalse(log.contains("operation"));
    assertEquals(type.equals("session.read"), log.contains("readRelation"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tenant",
        "task",
        "recordVersion",
        "eventVersion",
        "content",
        "raw",
        "bytes",
        "digest",
        "time",
        "step",
        "invocation",
        "fact",
        "sourceDigest"
      })
  void storedMetadataRejectsAtOriginalBoundary(String field) {
    event("user.message");
    Object value =
        switch (field) {
          case "recordVersion", "eventVersion" -> 9L;
          case "bytes" -> 99L;
          case "time" -> TIME.plusNanos(1000);
          default -> "other";
        };
    stored = with(stored, field, value);
    rejects(
        field.equals("recordVersion") || field.equals("eventVersion")
            ? "SESSION_HISTORY_VERSION_UNSUPPORTED"
            : field.equals("content") ? "SESSION_HISTORY_EXPIRED" : "SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("reliableType"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "integralVersion",
        "version",
        "type",
        "session",
        "seq",
        "time",
        "step",
        "invocation"
      })
  void decodedMetadataRejectsBeforeSourceSql(String field) {
    event("user.message");
    Object value =
        switch (field) {
          case "integralVersion" -> false;
          case "version", "seq" -> 99L;
          default -> "other";
        };
    document = with(document, field, value);
    rejects(
        List.of("integralVersion", "version", "type").contains(field)
            ? "SESSION_HISTORY_VERSION_UNSUPPORTED"
            : "SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("step"));
    assertFalse(log.contains("reliableType"));
  }

  @Test
  void scopeMetersBeforeTenantRefusal() {
    event("user.message");
    subject = with(subject, "scoped", true);
    stored = with(stored, "tenant", "other");
    rejects("SESSION_HISTORY_CORRUPT");
    assertTrue(log.indexOf("operation") < log.indexOf("stored"));
  }

  @Test
  void expiredNullBodyPrecedesJsonParsing() {
    event("user.message");
    stored = with(with(stored, "content", "PURGED"), "raw", null);
    assertEquals(410, rejects("SESSION_HISTORY_EXPIRED").status());
    assertFalse(log.contains("decode"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void decoderFailuresAlwaysBecomeCorrupt(boolean trace) {
    event("user.message");
    subject = with(subject, "trace", trace);
    failAt = "decode";
    failStatus = 400;
    failCode = "INVALID_JSON";
    assertEquals(409, rejects("SESSION_HISTORY_CORRUPT").status());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void contract400MappingIsTraceSpecific(boolean trace) {
    event("user.message");
    subject = with(subject, "trace", trace);
    failAt = "validateDocument";
    failStatus = 400;
    failCode = "TOOL_INPUT_INVALID";
    assertEquals(
        trace ? 409 : 400,
        rejects(trace ? "SESSION_HISTORY_CORRUPT" : "TOOL_INPUT_INVALID").status());
  }

  @Test
  void scopeReadUsesOriginalSpecializedProofAndNoNativeLookup() {
    event("session.read");
    subject = with(with(subject, "scoped", true), "trace", false);
    service.verify(proof());
    assertTrue(log.contains("scopeRead"));
    assertFalse(log.contains("invocation"));
    assertFalse(log.contains("readRelation"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"receipt", "outcome", "outputDigest", "outputBytes"})
  void nativeReceiptAndOutputProofRejectBeforeRelation(String field) {
    event("session.read");
    Read read = (Read) values.get("read");
    values.put(
        "read",
        with(read, field, field.equals("receipt") || field.equals("outputBytes") ? 99L : "other"));
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("readRelation"));
    assertFalse(log.contains("reliableType"));
  }

  @Test
  void missingReceiptNumberDoesNotOverrideFactRefusal() {
    event("session.read");
    stored = with(stored, "fact", "wrong");
    values.put("read", new Read(null, "PENDING", null, null));
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("readReceipt"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sourceMatches", "step", "terminal", "reliableType"})
  void branchProofRefusalsDoNotAdvance(String branch) {
    event(
        branch.equals("step")
            ? "step.started"
            : branch.equals("terminal") ? "turn.ended" : "user.message");
    switch (branch) {
      case "sourceMatches" -> values.put(branch, false);
      case "step" -> values.put(branch, new Step("other", 2, "req", "hash:req", "res", "hash:res"));
      case "terminal" -> values.put(branch, new Terminal("FAILED", null, "hash:terminal"));
      default -> values.put(branch, "tool.completed");
    }
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("requireAttempt"));
  }

  @Test
  void nullTerminalCodeIsDistinctFromEmptyCode() {
    event("turn.ended");
    document = with(document, "code", "");
    rejects("SESSION_HISTORY_CORRUPT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"sourcesEmpty", "refsEmpty"})
  void nonReadRelationsRejectInTheirOwnVersion(String field) {
    event("user.message");
    subject =
        with(
            with(subject, "scoped", field.equals("refsEmpty")),
            "trace",
            field.equals("sourcesEmpty"));
    document = with(document, field, false);
    rejects("SESSION_HISTORY_CORRUPT");
    assertFalse(log.contains("reliableType"));
  }

  @Test
  void absentAttemptSkipsOnlyAttemptProof() {
    event("user.message");
    stored = with(with(stored, "attempt", null), "fence", null);
    service.verify(proof());
    assertTrue(log.contains("reliableType"));
    assertFalse(log.contains("requireAttempt"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "step",
        "invocation",
        "readRelation",
        "scopeRead",
        "reliableType",
        "requireAttempt"
      })
  void queryFailurePropagatesWithoutAlternateProof(String stage) {
    event(
        stage.equals("step")
            ? "step.started"
            : List.of("invocation", "readRelation", "scopeRead").contains(stage)
                ? "session.read"
                : "user.message");
    if (stage.equals("scopeRead")) subject = with(subject, "scoped", true);
    failAt = stage;
    failStatus = 504;
    failCode = "SESSION_QUERY_TIMEOUT";
    assertEquals(504, rejects(failCode).status());
  }
}
