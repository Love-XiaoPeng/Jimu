package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionAnswerMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAnswer;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Command;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.AnswerBatch;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Question;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.support.DataAccessUtils;

/** Only lazy single calls; all qualification, catches and branch ordering belong to the Service. */
final class MapperQuestionAnswer
    implements QuestionAnswer<List<Question>, List<Answer>, AnswerBatch, Timestamp> {
  @FunctionalInterface
  interface Complete {
    void apply(Map<String, Object> task, Map<String, Object> question, String output);
  }

  private final JdbcLedger ledger;
  private final Identity identity;
  private final String taskId, requestId, key;
  private final Command command;
  private final Function<Supplier<Outcome>, Outcome> transaction;
  private final Supplier<Map<String, Object>> visible;
  private final Supplier<Instant> now;
  private final BiFunction<Map<String, Object>, Map<String, Object>, List<Question>> input;
  private final BiFunction<String, List<Answer>, String> digest;
  private final Function<Map<String, Object>, Map<String, Object>> receipt;
  private final BiConsumer<Map<String, Object>, String> close, resume;
  private final BiConsumer<Map<String, Object>, Map<String, Object>> continuation;
  private final Complete complete;

  MapperQuestionAnswer(
      JdbcLedger ledger,
      Identity identity,
      String taskId,
      String requestId,
      String key,
      Command command,
      Function<Supplier<Outcome>, Outcome> transaction,
      Supplier<Map<String, Object>> visible,
      Supplier<Instant> now,
      BiFunction<Map<String, Object>, Map<String, Object>, List<Question>> input,
      BiFunction<String, List<Answer>, String> digest,
      Function<Map<String, Object>, Map<String, Object>> receipt,
      BiConsumer<Map<String, Object>, String> close,
      BiConsumer<Map<String, Object>, Map<String, Object>> continuation,
      Complete complete,
      BiConsumer<Map<String, Object>, String> resume) {
    this.ledger = ledger;
    this.identity = identity;
    this.taskId = taskId;
    this.requestId = requestId;
    this.key = key;
    this.command = command;
    this.transaction = transaction;
    this.visible = visible;
    this.now = now;
    this.input = input;
    this.digest = digest;
    this.receipt = receipt;
    this.close = close;
    this.continuation = continuation;
    this.complete = complete;
    this.resume = resume;
  }

  public Outcome transaction(Supplier<Outcome> work) {
    return transaction.apply(work);
  }

  public String tenant() {
    return identity.tenant();
  }

  public String actor() {
    return identity.actor();
  }

  public String taskId() {
    return taskId;
  }

  public String requestId() {
    return requestId;
  }

  public String key() {
    return key;
  }

  public int questionVersion() {
    return command.questionVersion();
  }

  public int expectedRevision() {
    return command.expectedRevision();
  }

  public void bucket(String tenant) {
    ledger.bucket(tenant);
  }

  public Map<String, Object> visible() {
    return visible.get();
  }

  public List<Map<String, Object>> locked(String tenant, String task, String request) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionAnswerMapper.class)
        .lockQuestion(ledger.queryContext(), tenant, task, request);
  }

  public List<Map<String, Object>> previous(String tenant, String task, String actor, String key) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionAnswerMapper.class)
        .previous(ledger.queryContext(), tenant, task, actor, key);
  }

  public List<Map<String, Object>> reload(String tenant, String request) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionAnswerMapper.class)
        .reload(ledger.queryContext(), tenant, request);
  }

  public List<Question> inputQuestions(Map<String, Object> q, Map<String, Object> task) {
    return input.apply(q, task);
  }

  public List<Answer> canonical(List<Question> questions) {
    return ledger.questions.canonicalAnswers(questions, command);
  }

  public String digest(String request, List<Answer> answers) {
    return digest.apply(request, answers);
  }

  public Map<String, Object> receipt(Map<String, Object> old) {
    return receipt.apply(old);
  }

  public Instant now() {
    return now.get();
  }

  public void close(Map<String, Object> task, String code) {
    close.accept(task, code);
  }

  public View view(Map<String, Object> task) {
    return ledger.view(task);
  }

  public void terminal(Map<String, Object> task, String state, String code) {
    ledger.terminal(task, state, code);
  }

  public boolean enabled() {
    return ledger.questionsEnabled;
  }

  public void continueQuestion(Map<String, Object> task, Map<String, Object> q) {
    continuation.accept(task, q);
  }

  public String encode(Object value) {
    return Payloads.json(value);
  }

  public String id() {
    return JdbcLedger.id();
  }

  public Integer sequence(String tenant, String task) {
    return single(
        ledger
            .mappers
            .session()
            .getMapper(QuestionAnswerMapper.class)
            .sequence(ledger.queryContext(), tenant, task));
  }

  static Integer single(List<Integer> rows) {
    return DataAccessUtils.nullableSingleResult(rows);
  }

  public void storage(Map<String, Object> task, long bytes) {
    ledger.questionStorage(task, bytes);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public Timestamp timestamp(Instant now) {
    return Timestamp.from(now);
  }

  public int update(
      String answerId,
      String actor,
      Timestamp answeredAt,
      int seq,
      String answer,
      String answerDigest,
      String key,
      String requestDigest,
      int sourceRevision,
      String receipt,
      String delivery,
      String tenant,
      String request,
      long revision) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionAnswerMapper.class)
        .answer(
            ledger.queryContext(),
            answerId,
            actor,
            answeredAt,
            seq,
            answer,
            answerDigest,
            key,
            requestDigest,
            sourceRevision,
            receipt,
            delivery,
            tenant,
            request,
            revision);
  }

  public AnswerBatch batch(List<Answer> answers) {
    return new AnswerBatch(answers, null, null, null);
  }

  public void complete(Map<String, Object> task, Map<String, Object> question, String output) {
    complete.apply(task, question, output);
  }

  public void resume(Map<String, Object> task, String request) {
    resume.accept(task, request);
  }

  public void event(Map<String, Object> task, String fact, String type, String code) {
    ledger.event(task, fact, type, code);
  }
}
