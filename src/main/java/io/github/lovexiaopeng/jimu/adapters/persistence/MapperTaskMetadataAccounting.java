package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting;
import java.util.List;
import java.util.Map;

/** 同 DS/UoW 的固定 Mapper 单次扫描；每次到达才重读 task 参数。 */
final class MapperTaskMetadataAccounting implements TaskMetadataAccounting {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperTaskMetadataAccounting(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public List<Map<String, Object>> rows(Table table) {
    String tenant = JdbcLedger.s(task, "tenant_id");
    String id = JdbcLedger.s(task, "id");
    return switch (table) {
      case TASK ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case TASK_DIRECTORY ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                      .TaskDirectoryReadMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case INVOCATION_PATH_BINDING ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                      .DirectoryPathReadMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case TASK_TODO_PROJECTION ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper
                      .TodoProjectionReadMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case TASK_ATTEMPT ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper
                      .TaskAttemptMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case STEP ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper
                      .class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case INVOCATION ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper
                      .InvocationMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case MODEL_DISPATCH ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.ModelDispatchMapper
                      .class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case RESERVATION ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper
                      .class)
              .metadataRows(ledger.queryContext(), tenant, id);
      case EVENT ->
          ledger
              .mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper.EventMapper.class)
              .metadataRows(ledger.queryContext(), tenant, id);
    };
  }

  public int bytes(String value) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(value);
  }
}
