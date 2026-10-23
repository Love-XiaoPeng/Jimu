package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.List;

/** 普通受理命令；保留边界传入的缺省值，由用例按旧顺序核验。 */
public record TaskSubmission(
    String goal,
    List<String> capabilityIds,
    List<String> resourceIds,
    BudgetInput budget,
    List<TaskImages.Input> images) {
  public record BudgetInput(
      Integer deadlineSeconds,
      Integer maxModelRequests,
      Integer maxToolInvocations,
      Long maxCostMicros) {}
}
