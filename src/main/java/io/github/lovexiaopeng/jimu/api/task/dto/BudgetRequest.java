package io.github.lovexiaopeng.jimu.api.task.dto;

public record BudgetRequest(
    Integer deadlineSeconds,
    Integer maxModelRequests,
    Integer maxToolInvocations,
    Long maxCostMicros) {}
