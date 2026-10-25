package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskVisibilityEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskVisibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionVisibility;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 构造只存引用；owner三键先求值，再取同UoW会话／query，锁行不复用tenant。 */
final class MapperQuestionVisibility implements QuestionVisibility<Map<String, Object>> {
  private final JdbcLedger ledger;
  private final Identity identity;
  private final String taskId;

  MapperQuestionVisibility(JdbcLedger ledger, Identity identity, String taskId) {
    this.ledger = ledger;
    this.identity = identity;
    this.taskId = taskId;
  }

  public void identity() {
    ledger.identity(identity);
  }

  public String tenant() {
    return identity.tenant();
  }

  public String taskId() {
    return taskId;
  }

  public String actor() {
    return identity.actor();
  }

  public List<Map<String, Object>> owned(String tenant, String task, String actor) {
    var wrapper = ownerQuery(tenant, task, actor);
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .selectList(TaskVisibilityMapper.class.getName() + ".selectMaps", parameters);
  }

  static QueryWrapper<TaskVisibilityEntity> ownerQuery(String tenant, String task, String actor) {
    return new QueryWrapper<TaskVisibilityEntity>()
        .select("id")
        .eq("tenant_id", tenant)
        .eq("id", task)
        .eq("owner_actor_id", actor);
  }

  public Map<String, Object> locked(String tenant, String task) {
    return ledger.locked(tenant, task);
  }

  public void access(Map<String, Object> task) {
    ledger.access(task);
  }
}
