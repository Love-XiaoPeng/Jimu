package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.time;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoDispatchReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoInvocationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoProjectionReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoStepReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoDispatchReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoProjectionReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoStepReadMapper;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRetentions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 同原DS/UoW/query；仅事实转换、codec与具名持久化，清理／绑定规则归Service。 */
final class MapperTodoRetentions implements TodoRetentions {
  private final JdbcLedger ledger;
  private final TodoRetentionMapper sql;

  MapperTodoRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(TodoRetentionMapper.class);
  }

  private List<Map<String, Object>> select(Class<?> mapper, Object wrapper) {
    var params = new java.util.HashMap<String, Object>();
    params.put("ew", wrapper);
    params.put("query", ledger.queryContext());
    return ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", params);
  }

  private static List<Body> bodies(Map<String, Object> row, String... fields) {
    return java.util.Arrays.stream(fields).map(f -> new Body(f, s(row, f))).toList();
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "task_id")))
        .toList();
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public Instant now() {
    return sql.now(ledger.queryContext()).toInstant(ZoneOffset.UTC);
  }

  public String expectedBinding() {
    return ledger.todos.binding();
  }

  public BindingVersions bindingVersions(String value) {
    var binding = Payloads.read(value, com.fasterxml.jackson.databind.JsonNode.class);
    return new BindingVersions(
        binding.path("version").asInt(), binding.path("todoVersion").asInt());
  }

  public String metadata(List<RetainedBody> bodies) {
    var retained = new java.util.LinkedHashMap<String, Object>();
    for (var body : bodies)
      retained.put(body.key(), Map.of("sha256", body.sha256(), "bytes", body.bytes()));
    return Payloads.json(retained);
  }

  public LockedTask lockTask(Candidate candidate) {
    var row = ledger.locked(candidate.tenant(), candidate.taskId());
    return new LockedTask() {
      public Projection projection() {
        var wrapper = new LambdaQueryWrapper<TodoProjectionReadEntity>();
        wrapper
            .eq(TodoProjectionReadEntity::getTenantId, candidate.tenant())
            .eq(TodoProjectionReadEntity::getTaskId, candidate.taskId());
        var rows = select(TodoProjectionReadMapper.class, wrapper);
        if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
        var p = rows.getFirst();
        return new Projection(
            n(p, "record_version"),
            n(p, "todo_version"),
            s(p, "binding_snapshot"),
            s(p, "binding_digest"),
            s(p, "content_state"),
            time(p, "retain_until"));
      }

      public String state() {
        return s(row, "state");
      }

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(row);
      }

      public boolean unsafeReservations() {
        return !sql.unsafeReservations(
                ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean activeInvocations() {
        return !sql.activeInvocations(ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean dispatchedModels() {
        return !sql.dispatchedModels(ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public List<Body> bodies() {
        return MapperTodoRetentions.bodies(row, "request_snapshot", "goal", "summary");
      }

      public List<Content> steps() {
        var wrapper = new LambdaQueryWrapper<TodoStepReadEntity>();
        wrapper
            .eq(TodoStepReadEntity::getTenantId, candidate.tenant())
            .eq(TodoStepReadEntity::getTaskId, candidate.taskId());
        return select(TodoStepReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperTodoRetentions.bodies(r, "request_snapshot", "response_snapshot")))
            .toList();
      }

      public List<Content> invocations() {
        var wrapper = new LambdaQueryWrapper<TodoInvocationReadEntity>();
        wrapper
            .eq(TodoInvocationReadEntity::getTenantId, candidate.tenant())
            .eq(TodoInvocationReadEntity::getTaskId, candidate.taskId());
        return select(TodoInvocationReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperTodoRetentions.bodies(r, "input_snapshot", "output_snapshot")))
            .toList();
      }

      public List<Content> dispatches() {
        var wrapper = new LambdaQueryWrapper<TodoDispatchReadEntity>();
        wrapper
            .eq(TodoDispatchReadEntity::getTenantId, candidate.tenant())
            .eq(TodoDispatchReadEntity::getTaskId, candidate.taskId());
        return select(TodoDispatchReadMapper.class, wrapper).stream()
            .map(r -> new Content(s(r, "rowId"), MapperTodoRetentions.bodies(r, "usage_snapshot")))
            .toList();
      }

      public void purgeStep(String id, String value) {
        sql.purgeStep(ledger.queryContext(), value, candidate.tenant(), id);
      }

      public void purgeInvocation(String id, String value) {
        sql.purgeInvocation(ledger.queryContext(), value, candidate.tenant(), id);
      }

      public void purgeDispatch(String id, String value) {
        sql.purgeDispatch(ledger.queryContext(), value, candidate.tenant(), id);
      }

      public void purgeTask(String value) {
        sql.purgeTask(ledger.queryContext(), value, candidate.tenant(), candidate.taskId());
      }

      public void markPurged(String retained) {
        sql.markPurged(ledger.queryContext(), retained, candidate.tenant(), candidate.taskId());
      }

      public void event() {
        ledger.event(row, "todo-purged", "todo.content_deleted", null);
      }

      public int releaseHold() {
        return sql.releaseHold(
            ledger.queryContext(), candidate.tenant(), candidate.taskId() + ":todo-storage");
      }

      public void releaseBudget() {
        sql.releaseBudget(ledger.queryContext(), candidate.tenant());
      }
    };
  }
}
