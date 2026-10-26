package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionMessagesMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionDeliveries;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.Map;

/** Reference-only adapter; all operations join the original prepare transaction. */
final class MapperQuestionDeliveries implements QuestionDeliveries<Checkpoint, WireMessage> {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;
  private final Lease lease;

  MapperQuestionDeliveries(JdbcLedger ledger, Map<String, Object> task, Lease lease) {
    this.ledger = ledger;
    this.task = task;
    this.lease = lease;
  }

  public boolean questionTask() {
    return ledger.questionTask(task);
  }

  public Checkpoint decode(String request) {
    return Payloads.read(request, Checkpoint.class);
  }

  public Map<String, String> pending() {
    return ledger.questionMessages(task);
  }

  public List<WireMessage> messages(Checkpoint wire) {
    return wire.messages();
  }

  public String role(WireMessage message) {
    return message.role();
  }

  public String content(WireMessage message) {
    return message.content();
  }

  public String tenant() {
    return lease.tenant();
  }

  public int checkpoint(String step, String tenant, String question) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionMessagesMapper.class)
        .checkpoint(ledger.queryContext(), step, tenant, question);
  }

  public void clearContinuation() {
    ledger
        .mappers
        .tasks()
        .clearQuestionContinuation(ledger.queryContext(), lease.tenant(), lease.taskId());
  }
}
