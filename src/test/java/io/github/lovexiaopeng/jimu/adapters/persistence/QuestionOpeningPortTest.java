package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionOpeningMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionOpeningPortTest {
  @Test
  void constructorDoesNotReadAnyReference() {
    var p = new MapperQuestionOpening(null, null, null, null, null, null);
    assertNull(p.version());
    assertNull(p.digest());
    assertNull(p.step());
    assertThrows(NullPointerException.class, p::tenant);
    assertThrows(NullPointerException.class, p::slot);
  }

  @Test
  void exactNullableIntegerSingleResult() {
    assertSame(Integer.valueOf(8), MapperQuestionOpening.single(List.of(8)));
    assertNull(MapperQuestionOpening.single(Collections.singletonList(null)));
    assertThrows(
        EmptyResultDataAccessException.class, () -> MapperQuestionOpening.single(List.of()));
    assertThrows(
        IncorrectResultSizeDataAccessException.class,
        () -> MapperQuestionOpening.single(List.of(1, 2)));
    assertThrows(
        NullPointerException.class,
        () -> {
          int n = MapperQuestionOpening.single(Collections.singletonList(null));
          assertEquals(0, n);
        });
  }

  @Test
  void sixActualLoadedStatementsAndAllBindingsStayExactEvenNull() {
    var c =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertFalse(c.isCacheEnabled());
    assertEquals(LocalCacheScope.STATEMENT, c.getLocalCacheScope());
    var expected =
        Map.of(
            "invocation",
            "SELECT "
                + QuestionProjectionTest.columns("invocation")
                + " FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=? FOR UPDATE",
            "ordinal",
            "SELECT COUNT(*)+1 FROM user_question WHERE tenant_id=? AND task_id=?",
            "insert",
            "INSERT INTO user_question(tenant_id,id,task_id,owner_actor_id,invocation_id,ordinal,binding_digest,mode,effective_timeout,phase,request_snapshot,request_digest,foreground_due_at,answer_expires_at,retain_until) VALUES(?,?,?,?,?,?,?,?,?,'OPEN',?,?,IF(? IS NULL OR ?=-1,NULL,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))),?,?)",
            "waiting",
            "UPDATE task SET tools_used=tools_used+1,waiting_question_id=? WHERE tenant_id=? AND id=?",
            "budget",
            "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?",
            "waitingInvocation",
            "UPDATE invocation SET state='WAITING_USER' WHERE tenant_id=? AND id=? AND state='PENDING'");
    var keys =
        Map.of(
            "invocation",
            List.of("tenant", "task", "step", "slot"),
            "ordinal",
            List.of("tenant", "task"),
            "insert",
            List.of(
                "tenant",
                "request",
                "task",
                "actor",
                "invocation",
                "ordinal",
                "binding",
                "mode",
                "timeout",
                "raw",
                "digest",
                "dueNull",
                "dueUnlimited",
                "dueSeconds",
                "deadline",
                "retain"),
            "waiting",
            List.of("request", "tenant", "task"),
            "budget",
            List.of("tenant"),
            "waitingInvocation",
            List.of("tenant", "invocation"));
    int total = 0;
    for (Object context :
        Arrays.asList(
            null,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, null))) {
      for (String name : expected.keySet()) {
        var args = new HashMap<String, Object>();
        args.put("query", context);
        for (String key : keys.get(name)) args.put(key, null);
        var st = c.getMappedStatement(QuestionOpeningMapper.class.getName() + "." + name);
        var sql = st.getBoundSql(args);
        assertEquals(expected.get(name), sql.getSql());
        assertEquals(
            keys.get(name), sql.getParameterMappings().stream().map(v -> v.getProperty()).toList());
        if (name.equals("ordinal"))
          assertEquals(Integer.class, st.getResultMaps().getFirst().getType());
        total += sql.getParameterMappings().size();
      }
    }
    assertEquals(56, total);
    var method =
        Arrays.stream(QuestionOpeningMapper.class.getMethods())
            .filter(m -> m.getName().equals("insert"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            int.class,
            String.class,
            String.class,
            Integer.class,
            String.class,
            String.class,
            Integer.class,
            Integer.class,
            Integer.class,
            Object.class,
            java.sql.Timestamp.class),
        Arrays.asList(method.getParameterTypes()).subList(1, 17));
  }
}
