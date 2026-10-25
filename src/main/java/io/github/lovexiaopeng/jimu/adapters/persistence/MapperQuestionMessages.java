package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionMessagesMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMessages;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Question;
import java.util.List;
import java.util.Map;

/** Construction stores references; arguments precede query/session acquisition. */
final class MapperQuestionMessages
    implements QuestionMessages<Map<String, Object>, List<Question>, JsonNode> {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;
  private final java.util.function.Function<Map<String, Object>, List<Question>> inputQuestions;

  MapperQuestionMessages(
      JdbcLedger ledger,
      Map<String, Object> task,
      java.util.function.Function<Map<String, Object>, List<Question>> inputQuestions) {
    this.ledger = ledger;
    this.task = task;
    this.inputQuestions = inputQuestions;
  }

  public boolean questionTask() {
    return ledger.questionTask(task);
  }

  public String tenant() {
    return s(task, "tenant_id");
  }

  public String taskId() {
    return s(task, "id");
  }

  public List<Map<String, Object>> pending(String tenant, String taskId) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionMessagesMapper.class)
        .pending(ledger.queryContext(), tenant, taskId);
  }

  public String answer(Map<String, Object> row) {
    return s(row, "answer_snapshot");
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public String answerDigest(Map<String, Object> row) {
    return s(row, "answer_digest");
  }

  public String id(Map<String, Object> row) {
    return s(row, "id");
  }

  public String provider(Map<String, Object> row) {
    return s(row, "provider_id");
  }

  public List<Question> questions(Map<String, Object> row) {
    return inputQuestions.apply(row);
  }

  public JsonNode read(String raw) {
    return Payloads.read(raw, JsonNode.class);
  }

  public String encode(
      int version,
      String kind,
      String tool,
      String requestId,
      String callId,
      List<Question> questions,
      JsonNode answers) {
    return ledger.encodeQuestionReply(version, kind, tool, requestId, callId, questions, answers);
  }
}
