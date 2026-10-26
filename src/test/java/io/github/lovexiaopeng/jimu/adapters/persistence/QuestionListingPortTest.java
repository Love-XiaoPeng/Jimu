package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuestionListingPortTest {
  @Test
  void constructionIsLazyIncludingNullIdentityAndOriginalCallbacks() {
    var calls = new AtomicInteger();
    var p =
        new MapperQuestionListing(
            null,
            null,
            "original",
            work -> {
              calls.incrementAndGet();
              return work.get();
            },
            () -> {
              throw new AssertionError("visible");
            },
            () -> {
              throw new AssertionError("now");
            },
            (q, t) -> {
              throw new AssertionError("input");
            });
    assertEquals(0, calls.get());
    assertEquals("original", p.taskId());
    assertThrows(NullPointerException.class, p::tenant);
    assertEquals("visible", assertThrows(AssertionError.class, p::visible).getMessage());
    assertEquals("now", assertThrows(AssertionError.class, p::now).getMessage());
    var result = Map.<String, Object>of("ok", 1);
    assertSame(result, p.transaction(() -> result));
    assertEquals(1, calls.get());
    assertEquals(
        "input", assertThrows(AssertionError.class, () -> p.questions(null, null)).getMessage());
  }

  @Test
  void originalJsonAndEncodingStaySingleDelegations() {
    var p =
        new MapperQuestionListing(
            null,
            new io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity("t", "a"),
            null,
            null,
            null,
            null,
            null);
    assertEquals("t", p.tenant());
    assertEquals(1, p.readAnswer("{\"a\":1}").path("a").asInt());
    assertThrows(RuntimeException.class, () -> p.readAnswer("bad"));
    assertEquals("{\"a\":1}", p.encode(Map.of("a", 1)));
  }

  @Test
  void loadedExactFullJoinHasTwoStringsAndOriginalNullMapPolicy() {
    var factory =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var statement =
        factory
            .getConfiguration()
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionListingMapper.list");
    var bound = statement.getBoundSql(Map.of("tenant", "t", "task", "q"));
    assertEquals(
        "SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? ORDER BY q.ordinal",
        bound.getSql());
    assertEquals(
        List.of("tenant", "task"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    assertEquals(java.util.LinkedHashMap.class, statement.getResultMaps().getFirst().getType());
    assertTrue(factory.getConfiguration().isCallSettersOnNulls());
    assertEquals(
        org.apache.ibatis.session.LocalCacheScope.STATEMENT,
        factory.getConfiguration().getLocalCacheScope());
    assertTrue(!factory.getConfiguration().isCacheEnabled());
    assertNotNull(factory.getConfiguration().getMapperRegistry());
  }
}
