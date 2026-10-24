package io.github.lovexiaopeng.jimu.kernel.operator.port;

import java.util.List;

/** 已保留结果的只读快照；所有查询显式包含tenant与所属task键。 */
public interface WebResultRecords {
  record TaskProof(
      String tenantId,
      String id,
      String requestSnapshot,
      String requestDigest,
      String modelSnapshot,
      String modelDigest,
      String toolsSnapshot,
      String toolsDigest) {}

  record StepProof(
      String tenantId,
      String id,
      String taskId,
      String requestSnapshot,
      String requestDigest,
      String responseSnapshot,
      String responseDigest) {}

  record InvocationProof(
      String tenantId,
      String id,
      String taskId,
      String stepId,
      String attemptId,
      String effectIntentId,
      String slot,
      String providerId,
      String tool,
      String inputSnapshot,
      String inputDigest,
      String pluginVersion,
      String pluginDigest,
      long installationEpoch,
      String outputSnapshot,
      String outputDigest,
      String state) {}

  List<TaskProof> tasks(String tenant, String task);

  List<InvocationProof> invocations(String tenant, String task, String invocation);

  List<String> proofs(String tenant, String task, String fact);

  List<StepProof> steps(String tenant, String task, String step);
}
