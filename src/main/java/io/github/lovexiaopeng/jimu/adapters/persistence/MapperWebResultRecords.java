package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.OperatorEventEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.OperatorInvocationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.OperatorStepEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.OperatorTaskEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.OperatorEventMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.OperatorInvocationMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.OperatorStepMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.OperatorTaskMapper;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;

/** 同一只读事务、同一绝对截止上下文的真实MP读取，无JDBC或写入端口。 */
final class MapperWebResultRecords implements WebResultRecords {
  private final SqlSessionTemplate session;
  private final SessionQueryContext query;

  MapperWebResultRecords(SqlSessionTemplate session, SessionQueryContext query) {
    this.session = session;
    this.query = query;
  }

  public List<TaskProof> tasks(String tenant, String task) {
    var wrapper =
        new LambdaQueryWrapper<OperatorTaskEntity>()
            .eq(OperatorTaskEntity::getTenantId, tenant)
            .eq(OperatorTaskEntity::getRowId, task);
    List<Map<String, Object>> rows =
        session.selectList(
            OperatorTaskMapper.class.getName() + ".selectMaps",
            Map.of("ew", wrapper, "query", query));
    return rows.stream()
        .map(
            r ->
                new TaskProof(
                    (String) r.get("tenant_id"),
                    (String) r.get("id"),
                    (String) r.get("request_snapshot"),
                    (String) r.get("request_digest"),
                    (String) r.get("model_snapshot"),
                    (String) r.get("model_digest"),
                    (String) r.get("tools_snapshot"),
                    (String) r.get("tools_digest")))
        .toList();
  }

  public List<StepProof> steps(String tenant, String task, String id) {
    var wrapper =
        new LambdaQueryWrapper<OperatorStepEntity>()
            .eq(OperatorStepEntity::getTenantId, tenant)
            .eq(OperatorStepEntity::getTaskId, task)
            .eq(OperatorStepEntity::getRowId, id);
    List<Map<String, Object>> rows =
        session.selectList(
            OperatorStepMapper.class.getName() + ".selectMaps",
            Map.of("ew", wrapper, "query", query));
    return rows.stream()
        .map(
            r ->
                new StepProof(
                    (String) r.get("tenant_id"),
                    (String) r.get("id"),
                    (String) r.get("task_id"),
                    (String) r.get("request_snapshot"),
                    (String) r.get("request_digest"),
                    (String) r.get("response_snapshot"),
                    (String) r.get("response_digest")))
        .toList();
  }

  public List<InvocationProof> invocations(String tenant, String task, String id) {
    var wrapper =
        new LambdaQueryWrapper<OperatorInvocationEntity>()
            .eq(OperatorInvocationEntity::getTenantId, tenant)
            .eq(OperatorInvocationEntity::getTaskId, task)
            .eq(OperatorInvocationEntity::getRowId, id);
    List<Map<String, Object>> rows =
        session.selectList(
            OperatorInvocationMapper.class.getName() + ".selectMaps",
            Map.of("ew", wrapper, "query", query));
    return rows.stream()
        .map(
            r ->
                new InvocationProof(
                    (String) r.get("tenant_id"),
                    (String) r.get("id"),
                    (String) r.get("task_id"),
                    (String) r.get("step_id"),
                    (String) r.get("attempt_id"),
                    (String) r.get("effect_intent_id"),
                    (String) r.get("slot"),
                    (String) r.get("provider_id"),
                    (String) r.get("tool"),
                    (String) r.get("input_snapshot"),
                    (String) r.get("input_digest"),
                    (String) r.get("plugin_version"),
                    (String) r.get("plugin_digest"),
                    ((Number) r.get("installation_epoch")).longValue(),
                    (String) r.get("output_snapshot"),
                    (String) r.get("output_digest"),
                    (String) r.get("state")))
        .toList();
  }

  public List<String> proofs(String tenant, String task, String id) {
    var wrapper =
        new LambdaQueryWrapper<OperatorEventEntity>()
            .select(OperatorEventEntity::getCode)
            .eq(OperatorEventEntity::getTenantId, tenant)
            .eq(OperatorEventEntity::getTaskId, task)
            .eq(OperatorEventEntity::getFactId, id)
            .eq(OperatorEventEntity::getType, "web.result_preserved");
    List<Map<String, Object>> rows =
        session.selectList(
            OperatorEventMapper.class.getName() + ".selectMaps",
            Map.of("ew", wrapper, "query", query));
    return rows.stream().map(r -> (String) r.get("code")).toList();
  }
}
