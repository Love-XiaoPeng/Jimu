package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.domain.TaskExecutionLimitsV1;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** HTTP受理规范化与任务查询入口；持久受理由同一 TaskAdmissionService 负责，禁止双写。 */
public final class TaskServiceImpl implements TaskService {
  private final Tasks tasks;

  public TaskServiceImpl(Tasks tasks) {
    this.tasks = tasks;
  }

  @Override
  public Tasks.View submit(Tasks.Identity identity, String key, TaskSubmission request) {
    if (request != null && request.images() != null && request.images().isEmpty())
      throw new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
    if (request == null
        || key == null
        || !key.matches("[!-~]{1,128}")
        || request.goal() == null
        || request.goal().isBlank()
        || request.goal().getBytes(StandardCharsets.UTF_8).length
            > TaskExecutionLimitsV1.GOAL_UTF8_BYTES)
      throw new TaskFailure(400, CommonErrorCode.INVALID_REQUEST);
    if (request.capabilityIds() != null
        && request.capabilityIds().stream().anyMatch(java.util.Objects::isNull))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    if (request.capabilityIds() != null
        && (request.capabilityIds().contains(ToolCapability.SESSION_EVENT_SEARCH)
            || request.capabilityIds().contains(ToolCapability.SESSION_EVENT_TRACE))
        && !(request.capabilityIds().contains(ToolCapability.SESSION_EVENT_TRACE)
            ? request
                .capabilityIds()
                .equals(
                    List.of(
                        ToolCapability.SESSION_EVENT_READ,
                        ToolCapability.SESSION_EVENT_SEARCH,
                        ToolCapability.SESSION_EVENT_TRACE))
            : request
                .capabilityIds()
                .equals(
                    List.of(
                        ToolCapability.SESSION_EVENT_READ, ToolCapability.SESSION_EVENT_SEARCH))))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    var capabilities =
        request.capabilityIds() == null
            ? List.of(ToolCapability.WEB_FETCH)
            : request.capabilityIds().stream().distinct().toList();
    if (capabilities.isEmpty()
        || capabilities.size() > TaskExecutionLimitsV1.CAPABILITIES
        || !List.of(
                ToolCapability.WEB_FETCH,
                ToolCapability.READ,
                ToolCapability.READ_IMAGE,
                ToolCapability.WRITE,
                ToolCapability.PRESENT,
                ToolCapability.WEB_SEARCH,
                ToolCapability.COMPOSE_REPORT,
                ToolCapability.EDIT,
                ToolCapability.GLOB,
                ToolCapability.GREP,
                "list_mcp_resource_templates",
                "list_mcp_resources",
                "read_mcp_resource",
                ToolCapability.ASK_USER_QUESTION,
                ToolCapability.TODO_WRITE,
                ToolCapability.WORKING_DIRECTORY,
                ToolCapability.SESSION_EVENT_READ,
                ToolCapability.SESSION_EVENT_SEARCH,
                ToolCapability.SESSION_EVENT_TRACE)
            .containsAll(capabilities))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CAPABILITIES);
    // Resources remain operator-bound; no user-supplied connection or credential handles.
    if (request.resourceIds() != null && !request.resourceIds().isEmpty())
      throw new TaskFailure(403, CommonErrorCode.RESOURCE_NOT_AUTHORIZED);
    var b = request.budget();
    if (b != null && b.maxCostMicros() != null && b.maxCostMicros() < 1)
      throw new TaskFailure(400, TaskErrorCode.INVALID_BUDGET);
    var budget =
        new Tasks.Budget(
            bound(b == null ? null : b.deadlineSeconds(), TaskExecutionLimitsV1.DEADLINE_SECONDS),
            bound(b == null ? null : b.maxModelRequests(), TaskExecutionLimitsV1.MODEL_REQUESTS),
            bound(
                b == null ? null : b.maxToolInvocations(), TaskExecutionLimitsV1.TOOL_INVOCATIONS),
            b == null ? null : b.maxCostMicros());
    return tasks.submit(
        identity,
        key,
        new Tasks.Request(request.goal(), capabilities, List.of(), budget),
        request.images() == null ? List.of() : request.images());
  }

  private static int bound(Integer value, int maximum) {
    if (value == null) return maximum;
    if (value < 1 || value > maximum) throw new TaskFailure(400, TaskErrorCode.INVALID_BUDGET);
    return value;
  }

  @Override
  public Tasks.View get(Tasks.Identity identity, String taskId) {
    return tasks.get(identity, taskId);
  }

  @Override
  public Tasks.View cancel(Tasks.Identity identity, String taskId) {
    return tasks.cancel(identity, taskId);
  }

  @Override
  public List<String> capabilities(Tasks.Identity identity) {
    return tasks.capabilities(identity);
  }
}
