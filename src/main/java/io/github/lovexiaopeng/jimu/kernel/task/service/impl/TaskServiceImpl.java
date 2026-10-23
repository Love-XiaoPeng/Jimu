package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.domain.TaskExecutionLimitsV1;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 普通受理策略所有者；B1 期间事务账本仍经现有 Tasks 端口，禁止双写。 */
public final class TaskServiceImpl implements TaskService {
  private final Tasks tasks;

  public TaskServiceImpl(Tasks tasks) {
    this.tasks = tasks;
  }

  @Override
  public Tasks.View submit(Tasks.Identity identity, String key, TaskSubmission request) {
    if (request != null && request.images() != null && request.images().isEmpty())
      throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    if (request == null
        || key == null
        || !key.matches("[!-~]{1,128}")
        || request.goal() == null
        || request.goal().isBlank()
        || request.goal().getBytes(StandardCharsets.UTF_8).length
            > TaskExecutionLimitsV1.GOAL_UTF8_BYTES) throw new TaskFailure(400, "INVALID_REQUEST");
    if (request.capabilityIds() != null
        && request.capabilityIds().stream().anyMatch(java.util.Objects::isNull))
      throw new TaskFailure(400, "INVALID_CAPABILITIES");
    if (request.capabilityIds() != null
        && (request.capabilityIds().contains("session_event_search")
            || request.capabilityIds().contains("session_event_trace"))
        && !(request.capabilityIds().contains("session_event_trace")
            ? request
                .capabilityIds()
                .equals(
                    List.of("session_event_read", "session_event_search", "session_event_trace"))
            : request
                .capabilityIds()
                .equals(List.of("session_event_read", "session_event_search"))))
      throw new TaskFailure(400, "INVALID_CAPABILITIES");
    var capabilities =
        request.capabilityIds() == null
            ? List.of("web_fetch")
            : request.capabilityIds().stream().distinct().toList();
    if (capabilities.isEmpty()
        || capabilities.size() > TaskExecutionLimitsV1.CAPABILITIES
        || !List.of(
                "web_fetch",
                "read",
                "read_image",
                "write",
                "present",
                "web_search",
                "compose_report",
                "edit",
                "glob",
                "grep",
                "list_mcp_resource_templates",
                "list_mcp_resources",
                "read_mcp_resource",
                "ask_user_question",
                "todo_write",
                "working_directory",
                "session_event_read",
                "session_event_search",
                "session_event_trace")
            .containsAll(capabilities)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    // Resources remain operator-bound; no user-supplied connection or credential handles.
    if (request.resourceIds() != null && !request.resourceIds().isEmpty())
      throw new TaskFailure(403, "RESOURCE_NOT_AUTHORIZED");
    var b = request.budget();
    if (b != null && b.maxCostMicros() != null && b.maxCostMicros() < 1)
      throw new TaskFailure(400, "INVALID_BUDGET");
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
    if (value < 1 || value > maximum) throw new TaskFailure(400, "INVALID_BUDGET");
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
