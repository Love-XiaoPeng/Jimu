package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.List;

/** 严格协议边界解析后的显式受理意图；来源按UUID排序，parent可省略。 */
public record TaskLineageSubmission(
    String goal, List<String> historySessionIds, String parentSessionId) {
  public TaskLineageSubmission {
    historySessionIds = List.copyOf(historySessionIds);
  }
}
