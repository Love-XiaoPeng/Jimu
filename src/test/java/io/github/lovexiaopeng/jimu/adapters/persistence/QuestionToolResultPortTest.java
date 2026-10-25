package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionToolResultServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionToolResultPortTest {
  @Test
  void constructorReadsNothingAndKeepsOriginalTaskReference() throws Exception {
    var poison =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            throw new AssertionError("constructor");
          }
        };
    var p = new MapperQuestionToolResult(null, poison, poison);
    var field = MapperQuestionToolResult.class.getDeclaredField("task");
    field.setAccessible(true);
    assertSame(poison, field.get(p));
    var lazy = new MapperQuestionToolResult(null, null, null);
    assertThrows(
        NullPointerException.class,
        () -> new QuestionToolResultServiceImpl().complete(lazy, "raw"));
  }

  @Test
  void firstTwoOriginalStringCastsPrecedeToolsAndTaskId() {
    var calls = new ArrayList<String>();
    var task =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            calls.add("task." + key);
            return super.get(key);
          }
        };
    var q =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            calls.add("q." + key);
            return super.get(key);
          }
        };
    var p = new MapperQuestionToolResult(null, task, q);
    task.put("tenant_id", 17);
    assertThrows(
        ClassCastException.class, () -> new QuestionToolResultServiceImpl().complete(p, "raw"));
    assertEquals(List.of("task.tenant_id"), calls);
    calls.clear();
    task.put("tenant_id", null);
    q.put("invocation_id", 17);
    assertThrows(
        ClassCastException.class, () -> new QuestionToolResultServiceImpl().complete(p, "raw"));
    assertEquals(List.of("task.tenant_id", "q.invocation_id"), calls);
    calls.clear();
    q.put("invocation_id", null);
    assertThrows(
        NullPointerException.class, () -> new QuestionToolResultServiceImpl().complete(p, "raw"));
    assertEquals(List.of("task.tenant_id", "q.invocation_id"), calls);
    task.put("id", 17);
    assertThrows(ClassCastException.class, p::taskId);
    assertNull(p.invocation());
    assertNull(p.tenant());
  }

  @Test
  void originalPinHashAndHistoricalOutputBoundariesRemainUntranslated() {
    var ledger =
        new JdbcLedger(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
            null,
            null,
            null,
            null);
    var p = new MapperQuestionToolResult(ledger, null, null);
    assertThrows(NullPointerException.class, () -> p.version(null));
    assertNull(p.version(new PluginPin(null, null, 0)));
    assertEquals(Payloads.hash(" raw🙂 "), p.hash(" raw🙂 "));
    assertThrows(NullPointerException.class, () -> p.hash(null));
    String valid = "{\"answers\":[{\"id\":\"q\",\"selected\":[]}]}";
    p.output("0.1.0", valid);
    p.output("0.2.0", valid);
    assertEquals(
        "TOOL_OUTPUT_TOO_LARGE",
        assertThrows(TaskFailure.class, () -> p.output("unknown", "🙂".repeat(4097))).code());
    assertEquals(
        "PLUGIN_PIN_MISMATCH",
        assertThrows(TaskFailure.class, () -> p.output("unknown", "{")).code());
    assertEquals(
        "PLUGIN_PIN_MISMATCH", assertThrows(TaskFailure.class, () -> p.output(null, "{")).code());
    assertThrows(NullPointerException.class, () -> p.output("unknown", null));
    assertThrows(IllegalArgumentException.class, () -> p.output("0.2.0", "{"));
    assertThrows(IllegalArgumentException.class, () -> p.output("0.2.0", "{}"));
  }

  @Test
  void loadedExactStatementsRetainFivePlusTwoStringBindings() {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var c = session.getConfiguration();
    String ns =
        "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionToolResultMapper.";
    var args =
        Map.of(
            "output",
            "raw",
            "digest",
            "digest",
            "tenant",
            "tenant",
            "task",
            "task",
            "invocation",
            "invocation");
    var first = c.getMappedStatement(ns + "completeInvocation").getBoundSql(args);
    var second = c.getMappedStatement(ns + "settleReservation").getBoundSql(args);
    assertEquals(
        "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND id=? AND state='WAITING_USER'",
        first.getSql());
    assertEquals(
        "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=? AND category='TOOL' AND state='RESERVED'",
        second.getSql());
    assertEquals(
        List.of("output", "digest", "tenant", "task", "invocation"),
        first.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    assertEquals(
        List.of("tenant", "invocation"),
        second.getParameterMappings().stream().map(v -> v.getProperty()).toList());
  }
}
