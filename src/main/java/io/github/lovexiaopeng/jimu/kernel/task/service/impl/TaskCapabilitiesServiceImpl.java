package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskCapabilities;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskCapabilitiesService;
import java.util.List;

/** 保留逐候选过滤、实时开关及拒绝顺序；事务特殊提交仍由原事务端口执行。 */
public final class TaskCapabilitiesServiceImpl implements TaskCapabilitiesService {
  public List<String> discover(TaskCapabilities records) {
    return records.transaction(
        () -> {
          records.identity();
          return records.installed().stream()
              .filter(name -> records.mcpEnabled() || !McpLedger.NAMES.contains(name))
              .filter(name -> records.questionsEnabled() || !name.equals("ask_user_question"))
              .filter(name -> !name.equals("working_directory") || records.directoryAvailable())
              .filter(name -> !name.equals("todo_write") || records.todoAvailable())
              .filter(name -> !name.equals("session_event_read") || records.sessionAvailable(false))
              .filter(
                  name -> !name.equals("session_event_search") || records.sessionAvailable(true))
              .filter(name -> !name.equals("session_event_trace") || records.traceAvailable())
              .filter(name -> !name.equals("session_search") || records.priorAvailable())
              .filter(name -> records.searchAvailable() || !List.of("glob", "grep").contains(name))
              .filter(
                  name ->
                      !name.equals("read_image")
                          || records.imagesAvailable()
                              && records.imageModelEnabled(records.selected()))
              .toList();
        });
  }
}
