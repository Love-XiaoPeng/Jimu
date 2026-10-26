package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionContinuationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionContinuationPortTest {
  @Test
  void constructionStoresReferencesWithoutMapOrCallbackReads() throws Exception {
    var poison =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            throw new AssertionError("constructor");
          }
        };
    var p =
        new MapperQuestionContinuation(
            null,
            poison,
            poison,
            v -> {
              throw new AssertionError();
            },
            v -> {
              throw new AssertionError();
            });
    for (String name : List.of("task", "question")) {
      var f = MapperQuestionContinuation.class.getDeclaredField(name);
      f.setAccessible(true);
      assertSame(poison, f.get(p));
    }
    new MapperQuestionContinuation(null, null, null, null, null);
  }

  @Test
  void queryArgumentsAreOriginalStringReadsBeforeSessionAndNullTaskStopsFirst() {
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
    var p = new MapperQuestionContinuation(null, task, q, null, null);
    assertThrows(
        NullPointerException.class,
        () -> new QuestionContinuationServiceImpl().continueQuestion(p));
    assertEquals(List.of("task.tenant_id", "q.invocation_id", "task.id"), calls);
    calls.clear();
    assertThrows(
        NullPointerException.class,
        () ->
            new QuestionContinuationServiceImpl()
                .continueQuestion(new MapperQuestionContinuation(null, null, q, null, null)));
    assertEquals(List.of(), calls);
    task.put("tenant_id", 17);
    assertThrows(ClassCastException.class, p::tenant);
    q.put("invocation_id", 17);
    assertThrows(ClassCastException.class, p::invocationId);
    task.put("id", 17);
    assertThrows(ClassCastException.class, p::taskId);
  }

  @Test
  void originalStringProviderIdAndNumberLongValueDoNotGainValidation() {
    var q = new HashMap<String, Object>();
    var p = new MapperQuestionContinuation(null, Map.of(), q, null, null);
    q.put("revision", new BigDecimal("3.99"));
    assertEquals(3, p.revision());
    q.put("revision", null);
    assertThrows(NullPointerException.class, p::revision);
    q.put("revision", "3");
    assertThrows(ClassCastException.class, p::revision);
    assertNull(p.questionId());
    q.put("id", 17);
    assertThrows(ClassCastException.class, p::questionId);
    assertNull(p.provider(Map.of()));
    assertThrows(ClassCastException.class, () -> p.provider(Map.of("provider_id", 17)));
    assertThrows(NullPointerException.class, () -> p.version(null));
    assertNull(p.version(new PluginPin(null, null, 0)));
  }

  @Test
  void loadedMpUsesOnlyProviderAndExactThreeNullableStringKeysAndPhaseUpdate() {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var c = session.getConfiguration();
    for (var args :
        List.of(
            Arrays.asList("tenant", "invocation", "task"),
            Arrays.<String>asList(null, null, null))) {
      var parameters =
          Map.of(
              "ew",
              MapperQuestionContinuation.providerQuery(args.get(0), args.get(1), args.get(2)));
      var bound =
          c.getMappedStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionInvocationReadMapper.selectMaps")
              .getBoundSql(parameters);
      assertEquals(
          "SELECT provider_id FROM invocation WHERE (tenant_id = ? AND id = ? AND task_id = ?)",
          bound.getSql().replaceAll("\\s+", " ").trim());
      assertEquals(
          args,
          bound.getParameterMappings().stream()
              .map(v -> SystemMetaObject.forObject(parameters).getValue(v.getProperty()))
              .toList());
    }
    var bound =
        c.getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionContinuationMapper.continuePhase")
            .getBoundSql(Map.of("tenant", "tenant", "question", "q", "revision", 3L));
    assertEquals(
        "UPDATE user_question SET phase='CONTINUED',revision=revision+1 WHERE tenant_id=? AND id=? AND phase='OPEN' AND revision=?",
        bound.getSql());
    assertEquals(
        List.of("tenant", "question", "revision"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
  }

  @Test
  void originalHistoricalPendingRawIsPassedWithoutReencodingAndCallbacksAreSingleEffects() {
    var ledger =
        new JdbcLedger(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
            null,
            null,
            null,
            null);
    var values = new ArrayList<String>();
    var p = new MapperQuestionContinuation(ledger, null, null, values::add, values::add);
    var original =
        assertThrows(
            IllegalArgumentException.class, () -> ledger.questions.pending("0.1.0", "call🙂"));
    var delegated =
        assertThrows(IllegalArgumentException.class, () -> p.pending("0.1.0", "call🙂"));
    assertEquals(original.getMessage(), delegated.getMessage());
    assertEquals(0, values.size());
    String raw = p.pending("0.2.0", "call🙂");
    assertEquals(ledger.questions.pending("0.2.0", "call🙂"), raw);
    p.completeOutput(raw);
    assertSame(raw, values.getLast());
    p.resume(null);
    assertNull(values.getLast());
  }
}
