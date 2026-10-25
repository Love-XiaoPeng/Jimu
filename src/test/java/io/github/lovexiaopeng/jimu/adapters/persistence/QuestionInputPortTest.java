package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionInputServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionInputPortTest {
  @Test
  void constructionDoesNotReadRowsOrDependencies() {
    new LedgerQuestionInput(null, null, null);
    var row =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            throw new AssertionError("constructor read");
          }
        };
    new LedgerQuestionInput(null, row, row);
  }

  @Test
  void versionsKeepNumberLongNullAndCastRules() {
    var row = new HashMap<String, Object>();
    var port = new LedgerQuestionInput(null, row, null);
    row.put("record_version", new BigDecimal("1.99"));
    row.put("question_version", -1.9d);
    assertEquals(1, port.recordVersion());
    assertEquals(-1, port.questionVersion());
    row.put("record_version", null);
    assertThrows(NullPointerException.class, port::recordVersion);
    row.put("question_version", "1");
    assertThrows(ClassCastException.class, port::questionVersion);
  }

  @Test
  void independentRowsReadOnlyReachedMembersInOriginalOrder() {
    var calls = new ArrayList<String>();
    var q =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            calls.add("q." + key);
            return super.get(key);
          }
        };
    var task =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            calls.add("task." + key);
            return 17;
          }
        };
    q.put("record_version", 1);
    q.put("question_version", 1);
    var p = new LedgerQuestionInput(null, q, task);
    assertThrows(ClassCastException.class, () -> new QuestionInputServiceImpl().input(p));
    assertEquals(
        List.of(
            "q.record_version",
            "q.question_version",
            "q.binding_digest",
            "task.question_binding_digest"),
        calls);
  }

  @Test
  void stringMembersRemainNullOrCastWithoutNormalization() {
    var q = new HashMap<String, Object>();
    q.put("binding_digest", 17);
    q.put("request_snapshot", " raw 🙂 ");
    var p = new LedgerQuestionInput(null, q, Map.of());
    assertThrows(ClassCastException.class, p::bindingDigest);
    assertEquals(" raw 🙂 ", p.requestSnapshot());
    assertNull(p.requestDigest());
    assertNull(p.taskBindingDigest());
    assertEquals(Payloads.hash(" raw 🙂 "), p.hash(p.requestSnapshot()));
    assertThrows(NullPointerException.class, () -> p.hash(null));
  }

  @Test
  void nullPinAndOriginalHistoricalCodecRemainLazy() {
    var ledger =
        new JdbcLedger(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
            null,
            null,
            null,
            null);
    var p = new LedgerQuestionInput(ledger, null, null);
    assertThrows(NullPointerException.class, () -> p.version(null));
    assertNull(p.version(new PluginPin(null, null, 0)));
    String raw = "{\"questions\":[{\"id\":\"q\",\"question\":\"Choose🙂\"}]}";
    assertEquals("Choose🙂", p.input("0.1.0", raw).questions().getFirst().question());
    assertEquals(
        120, p.input("0.2.0", raw.substring(0, raw.length() - 1) + ",\"timeout\":120}").timeout());
    assertEquals(
        "TOOL_INPUT_INVALID", assertThrows(TaskFailure.class, () -> p.input("0.2.0", "{}")).code());
    assertEquals(
        "PLUGIN_PIN_MISMATCH",
        assertThrows(TaskFailure.class, () -> p.input("unknown", raw)).code());
    assertThrows(NullPointerException.class, () -> p.input("0.2.0", null));
  }
}
