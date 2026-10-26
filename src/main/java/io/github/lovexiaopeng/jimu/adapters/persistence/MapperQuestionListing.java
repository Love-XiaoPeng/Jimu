package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionListingMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionListing;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Question;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Construction stores only references; each call reaches the original operation lazily. */
final class MapperQuestionListing implements QuestionListing<List<Question>, JsonNode> {
  private final JdbcLedger ledger;
  private final Identity identity;
  private final String taskId;
  private final Function<Supplier<Map<String, Object>>, Map<String, Object>> transaction;
  private final Supplier<Map<String, Object>> visible;
  private final Supplier<Instant> now;
  private final BiFunction<Map<String, Object>, Map<String, Object>, List<Question>> questions;

  MapperQuestionListing(
      JdbcLedger ledger,
      Identity identity,
      String taskId,
      Function<Supplier<Map<String, Object>>, Map<String, Object>> transaction,
      Supplier<Map<String, Object>> visible,
      Supplier<Instant> now,
      BiFunction<Map<String, Object>, Map<String, Object>, List<Question>> questions) {
    this.ledger = ledger;
    this.identity = identity;
    this.taskId = taskId;
    this.transaction = transaction;
    this.visible = visible;
    this.now = now;
    this.questions = questions;
  }

  public Map<String, Object> transaction(Supplier<Map<String, Object>> work) {
    return transaction.apply(work);
  }

  public String tenant() {
    return identity.tenant();
  }

  public String taskId() {
    return taskId;
  }

  public void bucket(String tenant) {
    ledger.bucket(tenant);
  }

  public Map<String, Object> visible() {
    return visible.get();
  }

  public Instant now() {
    return now.get();
  }

  public List<Map<String, Object>> rows(String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionListingMapper.class)
        .list(ledger.queryContext(), tenant, task);
  }

  public List<Question> questions(Map<String, Object> q, Map<String, Object> task) {
    return questions.apply(q, task);
  }

  public boolean enabled() {
    return ledger.questionsEnabled;
  }

  public View view(Map<String, Object> task) {
    return ledger.view(task);
  }

  public JsonNode readAnswer(String raw) {
    return Payloads.read(raw, JsonNode.class);
  }

  public String encode(Map<String, Object> result) {
    return Payloads.json(result);
  }
}
