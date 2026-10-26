package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.entity.QuestionBodyReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionBodyReadMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAuthorization;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Constructor stores references only; query/session lookup follows all three arguments. */
final class MapperQuestionAuthorization implements QuestionAuthorization {
  private final JdbcLedger ledger;
  private final Identity identity;
  private final String taskId;
  private final String requestId;
  private final Function<Supplier<Void>, Void> transaction;
  private final Runnable visible;

  MapperQuestionAuthorization(
      JdbcLedger ledger,
      Identity identity,
      String taskId,
      String requestId,
      Function<Supplier<Void>, Void> transaction,
      Runnable visible) {
    this.ledger = ledger;
    this.identity = identity;
    this.taskId = taskId;
    this.requestId = requestId;
    this.transaction = transaction;
    this.visible = visible;
  }

  public Void transaction(Supplier<Void> work) {
    return transaction.apply(work);
  }

  public String tenant() {
    return identity.tenant();
  }

  public String taskId() {
    return taskId;
  }

  public String requestId() {
    return requestId;
  }

  public void bucket(String tenant) {
    ledger.bucket(tenant);
  }

  public void visible() {
    visible.run();
  }

  public List<Map<String, Object>> rows(String tenant, String task, String request) {
    var wrapper = existenceQuery(tenant, task, request);
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .selectList(QuestionBodyReadMapper.class.getName() + ".selectMaps", parameters);
  }

  static QueryWrapper<QuestionBodyReadEntity> existenceQuery(
      String tenant, String task, String request) {
    return new QueryWrapper<QuestionBodyReadEntity>()
        .select("id")
        .eq("tenant_id", tenant)
        .eq("task_id", task)
        .eq("id", request);
  }
}
