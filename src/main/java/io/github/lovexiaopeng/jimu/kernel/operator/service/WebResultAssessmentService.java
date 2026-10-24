package io.github.lovexiaopeng.jimu.kernel.operator.service;

import java.util.Map;

/** 受信运维只读评估，无结果导入、结算或效果重放能力。 */
public interface WebResultAssessmentService {
  Map<String, Object> assess(String tenant, String task, String invocation);
}
