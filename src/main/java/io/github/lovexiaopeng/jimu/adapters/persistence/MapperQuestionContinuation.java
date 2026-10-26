package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.entity.QuestionInvocationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionContinuationMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionInvocationReadMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionContinuation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Construction stores references; each original read is reached only by service order. */
final class MapperQuestionContinuation
    implements QuestionContinuation<Map<String, Object>, PluginPin> {
  private final JdbcLedger ledger;
  private final Map<String, Object> task, question;
  private final Consumer<String> complete, resume;

  MapperQuestionContinuation(
      JdbcLedger ledger,
      Map<String, Object> task,
      Map<String, Object> question,
      Consumer<String> complete,
      Consumer<String> resume) {
    this.ledger = ledger;
    this.task = task;
    this.question = question;
    this.complete = complete;
    this.resume = resume;
  }

  public String tenant() {
    return s(task, "tenant_id");
  }

  public String invocationId() {
    return s(question, "invocation_id");
  }

  public String taskId() {
    return s(task, "id");
  }

  public List<Map<String, Object>> invocations(String tenant, String invocation, String task) {
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", providerQuery(tenant, invocation, task));
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .selectList(QuestionInvocationReadMapper.class.getName() + ".selectMaps", parameters);
  }

  static QueryWrapper<QuestionInvocationReadEntity> providerQuery(
      String tenant, String invocation, String task) {
    return new QueryWrapper<QuestionInvocationReadEntity>()
        .select("provider_id")
        .eq("tenant_id", tenant)
        .eq("id", invocation)
        .eq("task_id", task);
  }

  public PluginPin pin() {
    return ledger.tools(task).pins().get("ask_user_question");
  }

  public String version(PluginPin pin) {
    return pin.version();
  }

  public String provider(Map<String, Object> invocation) {
    return s(invocation, "provider_id");
  }

  public String pending(String version, String provider) {
    return ledger.questions.pending(version, provider);
  }

  public void completeOutput(String output) {
    complete.accept(output);
  }

  public String questionId() {
    return s(question, "id");
  }

  public long revision() {
    return n(question, "revision");
  }

  public int continuePhase(String tenant, String question, long revision) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionContinuationMapper.class)
        .continuePhase(ledger.queryContext(), tenant, question, revision);
  }

  public void resume(String requestId) {
    resume.accept(requestId);
  }

  public void event(String fact, String type, String code) {
    ledger.event(task, fact, type, code);
  }
}
