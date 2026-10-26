package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoEligibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoEligibility;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;
import java.util.Map;

/** 构造不取task值或会话；原工具、描述符和投影回调按调用点执行。 */
final class MapperTodoEligibility implements TodoEligibility {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperTodoEligibility(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public List<String> capabilities() {
    return ledger.caps(task);
  }

  public PluginPin pin() {
    return ledger.tools(task).pins().get(ToolCapability.TODO_WRITE);
  }

  public Installation installation() {
    var rows =
        ledger
            .mappers
            .session()
            .getMapper(TodoEligibilityMapper.class)
            .lockInstallation(
                ledger.queryContext(), s(task, "tenant_id"), ToolCapability.TODO_WRITE);
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    return new Row(rows.getFirst());
  }

  public ToolDescriptor descriptor() {
    return ledger.todos.descriptor();
  }

  public void projection() {
    ledger.todoProjection(task);
  }

  static final class Row implements Installation {
    private final Map<String, Object> row;

    Row(Map<String, Object> row) {
      this.row = row;
    }

    public String version() {
      return s(row, "plugin_version");
    }

    public String digest() {
      return s(row, "plugin_digest");
    }

    public long epoch() {
      return n(row, "epoch");
    }
  }
}
