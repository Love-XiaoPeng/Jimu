package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionAnswerMapper;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionAnswerServiceImpl;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionAnswerPortTest {
  @Test
  void constructorOnlySavesReferencesAndNullIdentityFailsInsideTx() {
    var entered = new AtomicBoolean();
    var p =
        new MapperQuestionAnswer(
            null,
            null,
            null,
            null,
            null,
            null,
            work -> {
              entered.set(true);
              return work.get();
            },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertFalse(entered.get());
    var e =
        assertThrows(NullPointerException.class, () -> new QuestionAnswerServiceImpl().answer(p));
    assertTrue(entered.get());
    assertTrue(e.getMessage().contains("Identity.tenant()"));
    assertThrows(NullPointerException.class, p::questionVersion);
    assertThrows(NullPointerException.class, p::expectedRevision);
  }

  @Test
  void originalIntegerNullableSingleResultThenPrimitiveUnboxing() {
    assertSame(Integer.valueOf(7), MapperQuestionAnswer.single(List.of(7)));
    assertNull(MapperQuestionAnswer.single(Collections.singletonList(null)));
    assertThrows(
        EmptyResultDataAccessException.class, () -> MapperQuestionAnswer.single(List.of()));
    assertThrows(
        IncorrectResultSizeDataAccessException.class,
        () -> MapperQuestionAnswer.single(List.of(1, 2)));
    assertThrows(
        NullPointerException.class,
        () -> {
          int value = MapperQuestionAnswer.single(Collections.singletonList(null));
          assertEquals(0, value);
        });
  }

  @Test
  void allFiveLoadedSqlKeepExactProjectionBindingsAndNullableOrExplicitContext() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var c = session.getConfiguration();
    assertFalse(c.isCacheEnabled());
    assertEquals(LocalCacheScope.STATEMENT, c.getLocalCacheScope());
    var expected =
        Map.of(
            "lockQuestion",
                "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
            "previous",
                "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND owner_actor_id=? AND answer_key=?",
            "reload", "SELECT * FROM user_question WHERE tenant_id=? AND id=?",
            "sequence",
                "SELECT COALESCE(MAX(accepted_seq),0)+1 FROM user_question WHERE tenant_id=? AND task_id=?",
            "answer",
                "UPDATE user_question SET phase='ANSWERED',revision=revision+1,answer_id=?,answered_actor_id=?,answered_at=?,accepted_seq=?,answer_snapshot=?,answer_digest=?,answer_key=?,answer_request_digest=?,answer_source_revision=?,receipt_snapshot=?,delivery_state=? WHERE tenant_id=? AND id=? AND revision=? AND answer_id IS NULL");
    var keys =
        Map.of(
            "lockQuestion",
            List.of("tenant", "task", "request"),
            "previous",
            List.of("tenant", "task", "actor", "key"),
            "reload",
            List.of("tenant", "request"),
            "sequence",
            List.of("tenant", "task"),
            "answer",
            List.of(
                "answerId",
                "actor",
                "answeredAt",
                "seq",
                "answer",
                "answerDigest",
                "key",
                "requestDigest",
                "sourceRevision",
                "receipt",
                "delivery",
                "tenant",
                "request",
                "revision"));
    for (Object context :
        Arrays.asList(
            null,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, null))) {
      for (String name : expected.keySet()) {
        var args = new HashMap<String, Object>();
        args.put("query", context);
        for (String key : keys.get(name)) args.put(key, null);
        var statement = c.getMappedStatement(QuestionAnswerMapper.class.getName() + "." + name);
        var sql = statement.getBoundSql(args);
        assertEquals(expected.get(name), sql.getSql());
        assertEquals(
            keys.get(name), sql.getParameterMappings().stream().map(v -> v.getProperty()).toList());
        if (name.equals("sequence"))
          assertEquals(Integer.class, statement.getResultMaps().getFirst().getType());
      }
    }
    var method =
        Arrays.stream(QuestionAnswerMapper.class.getMethods())
            .filter(m -> m.getName().equals("answer"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(
            String.class,
            String.class,
            Timestamp.class,
            int.class,
            String.class,
            String.class,
            String.class,
            String.class,
            int.class,
            String.class,
            String.class,
            String.class,
            String.class,
            long.class),
        Arrays.asList(method.getParameterTypes()).subList(1, 15));
    var p =
        new MapperQuestionAnswer(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null);
    var time = Instant.parse("2026-10-26T01:02:03.123456Z");
    assertEquals(time, p.timestamp(time).toInstant());
  }
}
