package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionResumeServiceImpl;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestionResumePortTest {
  @Test
  void constructionReadsNothingAndNullTaskFailsOnlyAtOriginalFirstField() {
    var poison =
        new AbstractMap<String, Object>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError();
          }

          public Object get(Object key) {
            throw new AssertionError("read " + key);
          }
        };
    var p = new MapperQuestionResume(null, poison);
    assertThrows(AssertionError.class, p::tenant);
    var nullTask = new MapperQuestionResume(null, null);
    assertThrows(NullPointerException.class, nullTask::tenant);
  }

  @Test
  void originalCastsNullsAndTenantThenTaskPrecedeQueryAcquisition() {
    var calls = new ArrayList<String>();
    var task =
        new AbstractMap<String, Object>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError();
          }

          public Object get(Object key) {
            calls.add(key.toString());
            return null;
          }
        };
    assertThrows(
        NullPointerException.class,
        () -> new QuestionResumeServiceImpl().resume(new MapperQuestionResume(null, task), "r"));
    assertEquals(List.of("tenant_id", "id"), calls);
    assertThrows(
        ClassCastException.class,
        () ->
            new QuestionResumeServiceImpl()
                .resume(new MapperQuestionResume(null, Map.of("tenant_id", 1)), "r"));
    var p = new MapperQuestionResume(null, Map.of("tenant_id", "t", "id", 2));
    assertEquals("t", p.tenant());
    assertThrows(ClassCastException.class, p::taskId);
  }

  @Test
  void loadedConditionalUpdateAndThreeOrderedStringParametersAreOriginal() throws Exception {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var statement =
        session
            .getConfiguration()
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionResumeMapper.resume");
    var bound = statement.getBoundSql(Map.of("tenant", "t", "task", "q", "request", "r"));
    assertEquals(
        "UPDATE task SET state='RESUME_READY',waiting_question_id=NULL WHERE tenant_id=? AND id=? AND state='WAITING_USER' AND waiting_question_id=?",
        bound.getSql());
    assertEquals(
        List.of("tenant", "task", "request"),
        bound.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    var nulls = new java.util.HashMap<String, Object>();
    nulls.put("tenant", null);
    nulls.put("task", null);
    nulls.put("request", null);
    assertEquals(bound.getSql(), statement.getBoundSql(nulls).getSql());
    assertEquals(org.apache.ibatis.mapping.SqlCommandType.UPDATE, statement.getSqlCommandType());
  }
}
