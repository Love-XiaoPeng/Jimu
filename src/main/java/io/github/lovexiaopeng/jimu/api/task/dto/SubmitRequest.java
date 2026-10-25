package io.github.lovexiaopeng.jimu.api.task.dto;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import java.util.List;

/** HTTP fields stay nullable and unnormalized until admission in the task service. */
public record SubmitRequest(
    String goal,
    List<String> capabilityIds,
    List<String> resourceIds,
    BudgetRequest budget,
    List<TaskImages.Input> images) {
  public TaskSubmission toSubmission() {
    return new TaskSubmission(
        goal,
        capabilityIds,
        resourceIds,
        budget == null
            ? null
            : new TaskSubmission.BudgetInput(
                budget.deadlineSeconds(),
                budget.maxModelRequests(),
                budget.maxToolInvocations(),
                budget.maxCostMicros()),
        images);
  }
}
