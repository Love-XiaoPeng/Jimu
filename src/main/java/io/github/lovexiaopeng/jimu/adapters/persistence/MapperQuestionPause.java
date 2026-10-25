package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionPauseMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionPause;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** Stores references only. Each statement acquires its session after its own arguments. */
final class MapperQuestionPause implements QuestionPause {
  private final JdbcLedger ledger;
  private final Lease lease;

  MapperQuestionPause(JdbcLedger ledger, Lease lease) {
    this.ledger = ledger;
    this.lease = lease;
  }

  public String tenant() {
    return lease.tenant();
  }

  public String attemptId() {
    return lease.attemptId();
  }

  public long fence() {
    return lease.fence();
  }

  public String taskId() {
    return lease.taskId();
  }

  public int pauseAttempt(String tenant, String attempt, long fence) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionPauseMapper.class)
        .pauseAttempt(ledger.queryContext(), tenant, attempt, fence);
  }

  public int pauseTask(String state, String continuation, String tenant, String task, long fence) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionPauseMapper.class)
        .pauseTask(ledger.queryContext(), state, continuation, tenant, task, fence);
  }
}
