package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskCapabilities;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskCapabilitiesService;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.util.List;

/** 保留逐候选过滤、实时开关及拒绝顺序；事务特殊提交仍由原事务端口执行。 */
public final class TaskCapabilitiesServiceImpl implements TaskCapabilitiesService {
  public List<String> discover(TaskCapabilities records) {
    return records.transaction(
        () -> {
          records.identity();
          return records.installed().stream()
              .filter(name -> records.mcpEnabled() || !McpLedger.NAMES.contains(name))
              .filter(
                  name ->
                      records.questionsEnabled() || !name.equals(ToolCapability.ASK_USER_QUESTION))
              .filter(
                  name ->
                      !name.equals(ToolCapability.WORKING_DIRECTORY)
                          || records.directoryAvailable())
              .filter(name -> !name.equals(ToolCapability.TODO_WRITE) || records.todoAvailable())
              .filter(
                  name ->
                      !name.equals(ToolCapability.SESSION_EVENT_READ)
                          || records.sessionAvailable(false))
              .filter(
                  name ->
                      !name.equals(ToolCapability.SESSION_EVENT_SEARCH)
                          || records.sessionAvailable(true))
              .filter(
                  name ->
                      !name.equals(ToolCapability.SESSION_EVENT_TRACE) || records.traceAvailable())
              .filter(
                  name ->
                      !name.equals(ToolCapability.SESSION_SEARCH)
                          || records.priorAvailable()
                          || records.lineageAvailable())
              .filter(
                  name -> !name.equals(ToolCapability.SESSION_TRACE) || records.lineageAvailable())
              .filter(
                  name ->
                      records.searchAvailable()
                          || !List.of(ToolCapability.GLOB, ToolCapability.GREP).contains(name))
              .filter(
                  name ->
                      !name.equals(ToolCapability.READ_IMAGE)
                          || records.imagesAvailable()
                              && records.imageModelEnabled(records.selected()))
              .toList();
        });
  }
}
