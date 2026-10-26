package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoProjectionReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoProjectionReadMapper;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoProjections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 同DS/UoW/query的完整行MP读取；构造和行字段均不提前求值。 */
final class MapperTodoProjections implements TodoProjections<MapperTodoProjections.Row> {
  // Frozen SELECT * column order from V17/schema22, matching the original JDBC row map.
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "task_id",
          "record_version",
          "todo_version",
          "binding_snapshot",
          "binding_digest",
          "revision",
          "content_state",
          "source_invocation_id",
          "source_step_id",
          "committed_attempt_id",
          "committed_fence",
          "updated_seq",
          "pending_count",
          "in_progress_count",
          "completed_count",
          "output_digest",
          "updated_at",
          "retain_until",
          "reserved_bytes",
          "retained_digests");
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperTodoProjections(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public Row projection() {
    var wrapper = new QueryWrapper<TodoProjectionReadEntity>();
    wrapper
        .select(Schema22Columns.TASK_TODO_PROJECTION)
        .eq("tenant_id", s(task, "tenant_id"))
        .eq("task_id", s(task, "id"));
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    List<Map<String, Object>> rows =
        ledger
            .mappers
            .session()
            .selectList(TodoProjectionReadMapper.class.getName() + ".selectMaps", parameters);
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    return new Row(originalColumns(rows.getFirst()));
  }

  static Map<String, Object> originalColumns(Map<String, Object> values) {
    var row = new LinkedCaseInsensitiveMap<Object>();
    for (String column : COLUMNS)
      if (values.containsKey(column)) row.put(column, values.get(column));
    values.forEach(
        (column, value) -> {
          if (!row.containsKey(column)) row.put(column, value);
        });
    return row;
  }

  public String hash(String snapshot) {
    return Payloads.hash(snapshot);
  }

  public Binding decode(String snapshot) {
    var binding = Payloads.read(snapshot, com.fasterxml.jackson.databind.JsonNode.class);
    return new Binding() {
      public int version() {
        return binding.path("version").asInt();
      }

      public int todoVersion() {
        return binding.path("todoVersion").asInt();
      }
    };
  }

  public String expectedBinding() {
    return ledger.todos.binding();
  }

  static final class Row implements Projection {
    private final Map<String, Object> values;

    Row(Map<String, Object> values) {
      this.values = values;
    }

    public long recordVersion() {
      return n(values, "record_version");
    }

    public long todoVersion() {
      return n(values, "todo_version");
    }

    public String snapshot() {
      return s(values, "binding_snapshot");
    }

    public String digest() {
      return s(values, "binding_digest");
    }

    Map<String, Object> values() {
      return values;
    }
  }
}
