package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionBodyReadMapper;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionAuthorizationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionAuthorizationPortTest {
  @Test
  void constructionIsLazyAndNullIdentityFailsOnlyInsideOriginalTransaction() {
    var entered = new AtomicBoolean();
    var port =
        new MapperQuestionAuthorization(
            null,
            null,
            null,
            null,
            work -> {
              entered.set(true);
              return work.get();
            },
            () -> {
              throw new AssertionError("visible");
            });
    assertFalse(entered.get());
    assertNull(port.taskId());
    assertNull(port.requestId());
    var error =
        assertThrows(
            NullPointerException.class,
            () -> new QuestionAuthorizationServiceImpl().authorize(port));
    assertTrue(entered.get());
    assertTrue(error.getMessage().contains("Identity.tenant()"));
  }

  @Test
  void visibleDoesNotConsumeOriginalReturnedMapAndTransactionNullIsPreserved() {
    var unread =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            throw new AssertionError("original map consumed");
          }
        };
    var calls = new ArrayList<String>();
    var identity = new Identity(null, null);
    var port =
        new MapperQuestionAuthorization(
            null,
            identity,
            null,
            null,
            work -> {
              calls.add("tx");
              return work.get();
            },
            () -> {
              calls.add("visible");
              originalVisible(unread);
            });
    assertNull(port.tenant());
    port.visible();
    assertEquals(List.of("visible"), calls);
    assertNull(port.transaction(() -> null));
    assertSame(unread, originalVisible(unread));
  }

  private static Object originalVisible(Object map) {
    return map;
  }

  @Test
  void realMpSingleColumnThreeStringKeysIncludingNullNeverDropPredicates() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    assertFalse(session.getConfiguration().isCacheEnabled());
    assertEquals(LocalCacheScope.STATEMENT, session.getConfiguration().getLocalCacheScope());
    for (var args :
        List.of(
            Arrays.asList("tenant", "task", "request"), Arrays.<String>asList(null, null, null))) {
      var params = new HashMap<String, Object>();
      params.put(
          "ew", MapperQuestionAuthorization.existenceQuery(args.get(0), args.get(1), args.get(2)));
      params.put("query", null);
      var sql =
          session
              .getConfiguration()
              .getMappedStatement(QuestionBodyReadMapper.class.getName() + ".selectMaps")
              .getBoundSql(params);
      assertEquals(
          "SELECT id FROM user_question WHERE (tenant_id = ? AND task_id = ? AND id = ?)",
          sql.getSql().replaceAll("\\s+", " ").trim());
      assertEquals(
          args,
          sql.getParameterMappings().stream()
              .map(v -> SystemMetaObject.forObject(params).getValue(v.getProperty()))
              .toList());
      assertEquals(3, sql.getParameterMappings().size());
    }
  }
}
