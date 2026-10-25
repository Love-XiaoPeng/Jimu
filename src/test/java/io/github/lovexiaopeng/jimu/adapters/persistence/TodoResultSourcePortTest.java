package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.execution.entity.TaskAttemptReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TodoResultSourcePortTest {
  @Test
  void constructionReadsNothing() {
    new MapperTodoResultSources(null, null, null);
  }

  @Test
  void appliedPresenceAndLongAreIndependentReads() {
    var reads = new ArrayList<String>();
    var m =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add(k.toString());
            return super.get(k);
          }
        };
    m.put("todo_applied_revision", new BigDecimal("1.99"));
    var p = new MapperTodoResultSources(null, null, m);
    assertTrue(p.appliedRevisionPresent());
    assertEquals(1, p.appliedRevision());
    assertEquals(List.of("todo_applied_revision", "todo_applied_revision"), reads);
    m.put("todo_applied_revision", "1");
    assertTrue(p.appliedRevisionPresent());
    assertThrows(ClassCastException.class, p::appliedRevision);
    m.put("todo_applied_revision", null);
    assertThrows(NullPointerException.class, p::appliedRevision);
  }

  @Test
  void existenceChecksDoNotDecodeObjects() {
    var m =
        Map.<String, Object>of(
            "todo_committed_attempt_id",
            17,
            "todo_committed_fence",
            "x",
            "todo_event_seq",
            new Object(),
            "todo_committed_at",
            new Object());
    var p = new MapperTodoResultSources(null, null, m);
    assertTrue(p.committedAttemptPresent());
    assertTrue(p.committedFencePresent());
    assertTrue(p.eventSequencePresent());
    assertTrue(p.committedAtPresent());
  }

  @Test
  void stringCastsAndHashRemainOriginal() {
    var m = new HashMap<String, Object>();
    var p = new MapperTodoResultSources(null, null, m);
    assertNull(p.state());
    assertNull(p.contentState());
    assertNull(p.outputDigest());
    for (String k : List.of("state", "tool", "content_state", "output_snapshot", "output_digest"))
      m.put(k, 17);
    assertThrows(ClassCastException.class, p::state);
    assertThrows(ClassCastException.class, p::tool);
    assertThrows(ClassCastException.class, p::contentState);
    assertThrows(ClassCastException.class, p::outputSnapshot);
    assertThrows(ClassCastException.class, p::outputDigest);
    assertThrows(NullPointerException.class, () -> p.hash(null));
  }

  @Test
  void fourArgumentsReadAndCastBeforeSessionInOriginalOrder() {
    for (int bad = 0; bad < 4; bad++) {
      var reads = new ArrayList<String>();
      final int fail = bad;
      var task =
          new HashMap<String, Object>() {
            public Object get(Object k) {
              reads.add(k.toString());
              return reads.size() - 1 == fail ? 17 : "value";
            }
          };
      var i =
          new HashMap<String, Object>() {
            public Object get(Object k) {
              reads.add(k.toString());
              return reads.size() - 1 == fail
                  ? (k.equals("todo_committed_fence") ? "bad" : 17)
                  : k.equals("todo_committed_fence") ? 7L : "value";
            }
          };
      assertThrows(
          ClassCastException.class, () -> new MapperTodoResultSources(null, task, i).attempts());
      assertEquals(
          List.of("tenant_id", "id", "todo_committed_attempt_id", "todo_committed_fence")
              .subList(0, bad + 1),
          reads);
    }
  }

  @Test
  void nullFenceUnboxingOccursAfterThreeStrings() {
    var i = new HashMap<String, Object>();
    i.put("todo_committed_attempt_id", "attempt");
    assertThrows(
        NullPointerException.class,
        () ->
            new MapperTodoResultSources(null, Map.of("tenant_id", "tenant", "id", "task"), i)
                .attempts());
  }

  @Test
  void actualMpSqlIsSingleColumnFourKeysWithoutExtraClauses() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var wrapper = new QueryWrapper<TaskAttemptReadEntity>();
    wrapper
        .select("id")
        .eq("tenant_id", "tenant")
        .eq("task_id", "task")
        .eq("id", "attempt")
        .eq("fence", Long.MIN_VALUE);
    var bound =
        session
            .getConfiguration()
            .getMappedStatement(TaskAttemptReadMapper.class.getName() + ".selectMaps")
            .getBoundSql(Map.of("ew", wrapper));
    assertEquals(
        "SELECT id FROM task_attempt WHERE (tenant_id = ? AND task_id = ? AND id = ? AND fence = ?)",
        bound.getSql().replaceAll("\\s+", " ").trim());
    assertEquals(
        List.of("tenant", "task", "attempt", Long.MIN_VALUE),
        bound.getParameterMappings().stream()
            .map(
                p ->
                    org.apache.ibatis.reflection.SystemMetaObject.forObject(Map.of("ew", wrapper))
                        .getValue(p.getProperty()))
            .toList());
  }
}
