package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionResumeMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionResume;
import java.util.Map;

/** Construction stores references only; update arguments precede query/session acquisition. */
final class MapperQuestionResume implements QuestionResume {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperQuestionResume(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public String tenant() {
    return s(task, "tenant_id");
  }

  public String taskId() {
    return s(task, "id");
  }

  public int update(String tenant, String taskId, String requestId) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionResumeMapper.class)
        .resume(ledger.queryContext(), tenant, taskId, requestId);
  }

  public void event(String fact, String type, String code) {
    ledger.event(task, fact, type, code);
  }
}
