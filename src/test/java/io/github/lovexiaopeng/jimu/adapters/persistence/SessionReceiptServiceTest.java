package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 无DataSource的真实共享调用边界：冻结字节/Java和JsonNode类型/缺失及异常顺序。 */
class SessionReceiptServiceTest {
  private final JdbcSessionRecords records = new JdbcSessionRecords(null);

  private static class Row extends LinkedHashMap<String, Object> {
    final List<String> reads = new ArrayList<>();

    @Override
    public Object get(Object key) {
      reads.add((String) key);
      return super.get(key);
    }
  }

  private static Row row() {
    var r = new Row();
    r.put("target_seq", 3L);
    r.put("before_count", 1);
    r.put("after_count", 2L);
    r.put("upper_seq", 9L);
    r.put("outcome", "SUCCEEDED");
    r.put("failure_code", null);
    r.put("output_digest", "digest");
    r.put("output_bytes", 42L);
    r.put(
        "normalized_snapshot",
        "{\"target_session_id\":\"original\",\"upper_seq\":9,\"after\":2,\"before\":1,\"seq\":3}");
    return r;
  }

  @Test
  void nativeGoldenBytesAndOrderedLongs() {
    var r = row();
    var value = records.receipt(r);
    assertEquals(
        "{\"seq\":3,\"before\":1,\"after\":2,\"upper_seq\":9,\"outcome\":\"SUCCEEDED\",\"code\":null,\"output_sha256\":\"digest\",\"output_bytes\":42}",
        Payloads.json(value));
    assertEquals(
        List.of(
            "seq",
            "before",
            "after",
            "upper_seq",
            "outcome",
            "code",
            "output_sha256",
            "output_bytes"),
        new ArrayList<>(value.keySet()));
    for (String key : List.of("seq", "before", "after", "upper_seq", "output_bytes"))
      assertInstanceOf(Long.class, value.get(key));
    assertEquals(
        List.of(
            "target_seq",
            "before_count",
            "after_count",
            "upper_seq",
            "outcome",
            "failure_code",
            "output_digest",
            "output_bytes"),
        r.reads);
  }

  @Test
  void scopeGoldenBytesAndOriginalJsonNodeTypes() {
    var r = row();
    var value = records.scopeReceipt(r);
    assertEquals(
        "{\"seq\":3,\"before\":1,\"after\":2,\"upper_seq\":9,\"target_session_id\":\"original\",\"outcome\":\"SUCCEEDED\",\"code\":null,\"output_sha256\":\"digest\",\"output_bytes\":42}",
        Payloads.json(value));
    assertTrue(((JsonNode) value.get("seq")).isInt());
    assertTrue(((JsonNode) value.get("target_session_id")).isTextual());
    assertInstanceOf(Long.class, value.get("output_bytes"));
    assertEquals(
        List.of("normalized_snapshot", "outcome", "failure_code", "output_digest", "output_bytes"),
        r.reads);
  }

  @ParameterizedTest
  @ValueSource(strings = {"target_seq", "before_count", "after_count", "upper_seq", "output_bytes"})
  void nativeNumbersUseOriginalLongValue(String field) {
    for (Number value : List.of(7, 7L, new BigInteger("7"), new BigDecimal("7.9"))) {
      var r = row();
      r.put(field, value);
      var result = records.receipt(r);
      String key =
          switch (field) {
            case "target_seq" -> "seq";
            case "before_count" -> "before";
            case "after_count" -> "after";
            case "upper_seq" -> "upper_seq";
            default -> "output_bytes";
          };
      assertEquals(7L, result.get(key));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"target_seq", "before_count", "after_count", "upper_seq", "output_bytes"})
  void nativeNullUnboxingStopsAtOriginalRead(String field) {
    var r = row();
    r.put(field, null);
    assertThrows(NullPointerException.class, () -> records.receipt(r));
    assertEquals(field, r.reads.getLast());
    assertEquals(1, r.reads.stream().filter(field::equals).count());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void commonNullStringsRemainExplicitKeys(boolean scope) {
    var r = row();
    r.put("outcome", null);
    r.remove("failure_code");
    r.put("output_digest", null);
    var value = scope ? records.scopeReceipt(r) : records.receipt(r);
    for (String key : List.of("outcome", "code", "output_sha256")) {
      assertTrue(value.containsKey(key));
      assertNull(value.get(key));
    }
  }

  @Test
  void scopeMissingIsJavaNullAndExplicitNullRemainsNullNode() {
    var r = row();
    r.put(
        "normalized_snapshot",
        "{\"seq\":null,\"before\":null,\"upper_seq\":9223372036854775808,\"target_session_id\":null}");
    var value = records.scopeReceipt(r);
    assertTrue(((JsonNode) value.get("seq")).isNull());
    assertNull(value.get("after"));
    assertTrue(value.containsKey("after"));
    assertTrue(((JsonNode) value.get("upper_seq")).isBigInteger());
    assertTrue(((JsonNode) value.get("target_session_id")).isNull());
    assertTrue(Payloads.json(value).contains("\"after\":null"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "3", "\"scalar\""})
  void originalJsonRootSemanticsAreNotStrengthened(String raw) {
    var r = row();
    r.put("normalized_snapshot", raw);
    var value = records.scopeReceipt(r);
    for (String key : List.of("seq", "before", "after", "upper_seq", "target_session_id")) {
      assertTrue(value.containsKey(key));
      assertNull(value.get(key));
    }
  }

  @Test
  void scopeDoesNotCoerceNodeTypesOrModifyOriginalBytes() {
    var r = row();
    String raw =
        " {\"after\":[1],\"seq\":1.25,\"before\":true,\"upper_seq\":\"9\",\"target_session_id\":{\"x\":1},\"ignored\":7} ";
    r.put("normalized_snapshot", raw);
    var value = records.scopeReceipt(r);
    assertTrue(((JsonNode) value.get("seq")).isFloatingPointNumber());
    assertTrue(((JsonNode) value.get("before")).isBoolean());
    assertTrue(((JsonNode) value.get("after")).isArray());
    assertTrue(((JsonNode) value.get("upper_seq")).isTextual());
    assertTrue(((JsonNode) value.get("target_session_id")).isObject());
    assertEquals(raw, r.get("normalized_snapshot"));
    assertFalse(value.containsKey("ignored"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"seq\":1,\"seq\":2}"})
  void invalidNormalizedStopsBeforeOutcome(String raw) {
    var r = row();
    r.put("normalized_snapshot", raw);
    r.put("outcome", 7);
    assertThrows(
        io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure.class,
        () -> records.scopeReceipt(r));
    assertEquals(List.of("normalized_snapshot"), r.reads);
  }

  @Test
  void nullNormalizedAndNativeClassCastBoundariesRemain() {
    var r = row();
    r.put("normalized_snapshot", null);
    assertThrows(NullPointerException.class, () -> records.scopeReceipt(r));
    assertEquals(List.of("normalized_snapshot"), r.reads);
    var invalid = row();
    invalid.put("target_seq", "3");
    invalid.put("output_bytes", null);
    assertThrows(ClassCastException.class, () -> records.receipt(invalid));
    assertEquals(List.of("target_seq"), invalid.reads);
  }

  @Test
  void normalizedOriginalByteLimitPrecedesRowValues() {
    var r = row();
    r.put("normalized_snapshot", "{\"seq\":\"" + "x".repeat(4097) + "\"}");
    r.put("outcome", 7);
    var failure =
        assertThrows(
            io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure.class,
            () -> records.scopeReceipt(r));
    assertEquals(400, failure.status());
    assertEquals("TOOL_INPUT_INVALID", failure.code());
    assertEquals(List.of("normalized_snapshot"), r.reads);
  }

  @Test
  void sharedScopeOutputBytesNullIsReadAfterStrings() {
    var r = row();
    r.put("output_bytes", null);
    assertThrows(NullPointerException.class, () -> records.scopeReceipt(r));
    assertEquals(
        List.of("normalized_snapshot", "outcome", "failure_code", "output_digest", "output_bytes"),
        r.reads);
  }

  @Test
  void resultMapsAreFreshAndMutableAsBefore() {
    var r = row();
    var first = records.receipt(r);
    var second = records.receipt(r);
    assertNotSame(first, second);
    first.put("seq", 99L);
    assertEquals(3L, second.get("seq"));
    var scope = records.scopeReceipt(r);
    scope.put("code", "changed");
    assertNull(records.scopeReceipt(r).get("code"));
  }
}
