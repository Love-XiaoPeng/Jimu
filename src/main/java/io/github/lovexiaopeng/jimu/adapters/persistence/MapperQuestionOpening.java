package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionOpeningMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionOpening;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.OpenResult;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.AnswerBatch;
import io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin.Input;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.support.DataAccessUtils;

/** 构造仅保存引用；每个外部操作在用例到达时才执行。 */
final class MapperQuestionOpening
    implements QuestionOpening<Input, PluginPin, AnswerBatch, OpenResult, Timestamp> {
  private final JdbcLedger ledger;
  private final Lease lease;
  private final String step, version, digest;
  private final Call call;

  MapperQuestionOpening(
      JdbcLedger ledger, Lease lease, String step, Call call, String version, String digest) {
    this.ledger = ledger;
    this.lease = lease;
    this.step = step;
    this.call = call;
    this.version = version;
    this.digest = digest;
  }

  public OpenResult transaction(Supplier<OpenResult> work) {
    return ledger.leaseTx(lease, work);
  }

  public Map<String, Object> fenced() {
    return ledger.fenced(lease, true);
  }

  public Map<String, Object> bucket(String tenant) {
    return ledger.bucket(tenant);
  }

  public String tenant() {
    return lease.tenant();
  }

  public String taskId() {
    return lease.taskId();
  }

  public String actor() {
    return lease.actor();
  }

  public String step() {
    return step;
  }

  public String slot() {
    return call.slot();
  }

  public String tool() {
    return call.tool();
  }

  public String providerId() {
    return call.providerId();
  }

  public String rawInput() {
    return call.input();
  }

  public String version() {
    return version;
  }

  public String digest() {
    return digest;
  }

  public boolean enabled() {
    return ledger.questionsEnabled;
  }

  public Input input(String version, String raw) {
    return ledger.questions.input(version, raw);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public PluginPin pin(Map<String, Object> task) {
    return ledger.tools(task).pins().get("ask_user_question");
  }

  public String pinVersion(PluginPin pin) {
    return pin.version();
  }

  public String pinDigest(PluginPin pin) {
    return pin.digest();
  }

  public long pinEpoch(PluginPin pin) {
    return pin.installationEpoch();
  }

  public void output(String version, String raw) {
    ledger.questions.output(version, raw);
  }

  public AnswerBatch readOutput(String raw) {
    return Payloads.read(raw, AnswerBatch.class);
  }

  public OpenResult result(boolean suspended, AnswerBatch batch) {
    return new OpenResult(suspended, batch);
  }

  static Integer single(List<Integer> rows) {
    return DataAccessUtils.nullableSingleResult(rows);
  }

  public void storage(Map<String, Object> task, long bytes) {
    ledger.questionStorage(task, bytes);
  }

  public String id() {
    return JdbcLedger.id();
  }

  public Integer timeout(Input input) {
    return input.timeout();
  }

  public String mode(String version) {
    return ledger.questions.mode(version);
  }

  public Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  public void reserve(
      String tenant,
      String task,
      String id,
      String dispatch,
      String category,
      long amount,
      String state,
      String quality) {
    ledger.reserve(tenant, task, id, dispatch, category, amount, state, quality);
  }

  public void pause(Map<String, Object> task, String state, String continuation) {
    ledger.questionPause(task, lease, state, continuation);
  }

  public void event(Map<String, Object> task, String fact, String type, String code) {
    ledger.event(task, fact, type, code);
  }

  public List<Map<String, Object>> invocation(
      String tenant, String task, String step, String slot) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionOpeningMapper.class)
        .invocation(ledger.queryContext(), tenant, task, step, slot);
  }

  public Integer ordinal(String tenant, String task) {
    return single(
        ledger
            .mappers
            .session()
            .getMapper(QuestionOpeningMapper.class)
            .ordinal(ledger.queryContext(), tenant, task));
  }

  public int insert(
      String tenant,
      String request,
      String task,
      String actor,
      String invocation,
      int ordinal,
      String binding,
      String mode,
      Integer timeout,
      String raw,
      String digest,
      Integer dueNull,
      Integer dueUnlimited,
      Integer dueSeconds,
      Object deadline,
      Timestamp retain) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionOpeningMapper.class)
        .insert(
            ledger.queryContext(),
            tenant,
            request,
            task,
            actor,
            invocation,
            ordinal,
            binding,
            mode,
            timeout,
            raw,
            digest,
            dueNull,
            dueUnlimited,
            dueSeconds,
            deadline,
            retain);
  }

  public int waiting(String request, String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionOpeningMapper.class)
        .waiting(ledger.queryContext(), request, tenant, task);
  }

  public int budget(String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionOpeningMapper.class)
        .budget(ledger.queryContext(), tenant);
  }

  public int waitingInvocation(String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionOpeningMapper.class)
        .waitingInvocation(ledger.queryContext(), tenant, invocation);
  }
}
