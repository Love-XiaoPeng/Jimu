package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionPauseServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionPausePortTest {
  @Test
  void constructorIsLazyAndNullLeaseFailsAtFirstOriginalMember() {
    var port = new MapperQuestionPause(null, null);
    assertThrows(NullPointerException.class, port::tenant);
    assertThrows(
        NullPointerException.class, () -> new QuestionPauseServiceImpl().pause(port, "s", null));
  }

  @Test
  void originalNullableLeaseMembersAndFenceAreNotValidatedOrNormalized() {
    var lease = new Lease(null, null, null, null, Long.MIN_VALUE, null, null, null, List.of(), 0);
    var port = new MapperQuestionPause(null, lease);
    assertEquals(null, port.tenant());
    assertEquals(null, port.attemptId());
    assertEquals(null, port.taskId());
    assertEquals(Long.MIN_VALUE, port.fence());
  }

  @Test
  void loadedOriginalStatementsRetainThreePlusFiveBindingsAndTypes() throws Exception {
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var configuration = session.getConfiguration();
    String namespace =
        "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionPauseMapper.";
    var parameters =
        new HashMap<String, Object>(
            Map.of(
                "tenant",
                "t",
                "task",
                "task",
                "attempt",
                "attempt",
                "fence",
                1L,
                "state",
                "WAITING_USER"));
    parameters.put("continuation", null);
    var first =
        configuration.getMappedStatement(namespace + "pauseAttempt").getBoundSql(parameters);
    var second = configuration.getMappedStatement(namespace + "pauseTask").getBoundSql(parameters);
    assertEquals(
        "UPDATE task_attempt SET state='PAUSED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND fence=? AND state='ACTIVE'",
        first.getSql());
    assertEquals(
        "UPDATE task SET state=?,question_continuation_step_id=?,fence=fence+1,current_attempt_id=NULL,worker_id=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND fence=?",
        second.getSql());
    assertEquals(
        List.of("tenant", "attempt", "fence"),
        first.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    assertEquals(
        List.of("state", "continuation", "tenant", "task", "fence"),
        second.getParameterMappings().stream().map(v -> v.getProperty()).toList());
    var mapper =
        io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionPauseMapper.class;
    var query = io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.class;
    assertEquals(
        int.class,
        mapper
            .getMethod("pauseAttempt", query, String.class, String.class, long.class)
            .getReturnType());
    assertEquals(
        int.class,
        mapper
            .getMethod(
                "pauseTask",
                query,
                String.class,
                String.class,
                String.class,
                String.class,
                long.class)
            .getReturnType());
    parameters.put("state", null);
    assertEquals(
        second.getSql(),
        configuration.getMappedStatement(namespace + "pauseTask").getBoundSql(parameters).getSql());
  }
}
