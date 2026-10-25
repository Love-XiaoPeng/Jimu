package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionToolResultMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionToolResult;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import java.util.Map;

/** Stores original references only; no field, codec or session access in construction. */
final class MapperQuestionToolResult implements QuestionToolResult<PluginPin> {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;
  private final Map<String, Object> question;

  MapperQuestionToolResult(
      JdbcLedger ledger, Map<String, Object> task, Map<String, Object> question) {
    this.ledger = ledger;
    this.task = task;
    this.question = question;
  }

  public String tenant() {
    return s(task, "tenant_id");
  }

  public String invocation() {
    return s(question, "invocation_id");
  }

  public PluginPin pin() {
    return ledger.tools(task).pins().get("ask_user_question");
  }

  public String version(PluginPin pin) {
    return pin.version();
  }

  public void output(String version, String raw) {
    ledger.questions.output(version, raw);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public String taskId() {
    return s(task, "id");
  }

  public int completeInvocation(
      String output, String digest, String tenant, String task, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionToolResultMapper.class)
        .completeInvocation(ledger.queryContext(), output, digest, tenant, task, invocation);
  }

  public int settleReservation(String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionToolResultMapper.class)
        .settleReservation(ledger.queryContext(), tenant, invocation);
  }

  public void event(String fact, String type, String code) {
    ledger.event(task, fact, type, code);
  }
}
