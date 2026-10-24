package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultProofCodec;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.InvocationProof;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.StepProof;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.TaskProof;
import java.util.HashMap;
import java.util.Map;

/** 复用原web结果证明与冻结插件Schema，只适配类型化记录到原列名。 */
final class OperatorWebResultCodec implements WebResultProofCodec {
  public String hash(String value) {
    return Payloads.hash(value);
  }

  public String receipt(TaskProof task, StepProof step, InvocationProof invocation, String output) {
    return WebFetchReceipts.digest(row(task), row(step), row(invocation), output);
  }

  private static Map<String, Object> row(TaskProof value) {
    var row = new HashMap<String, Object>();
    row.put("tenant_id", value.tenantId());
    row.put("id", value.id());
    row.put("request_snapshot", value.requestSnapshot());
    row.put("request_digest", value.requestDigest());
    row.put("model_snapshot", value.modelSnapshot());
    row.put("model_digest", value.modelDigest());
    row.put("tools_snapshot", value.toolsSnapshot());
    row.put("tools_digest", value.toolsDigest());
    return row;
  }

  private static Map<String, Object> row(StepProof value) {
    var row = new HashMap<String, Object>();
    row.put("tenant_id", value.tenantId());
    row.put("id", value.id());
    row.put("task_id", value.taskId());
    row.put("request_snapshot", value.requestSnapshot());
    row.put("request_digest", value.requestDigest());
    row.put("response_snapshot", value.responseSnapshot());
    row.put("response_digest", value.responseDigest());
    return row;
  }

  private static Map<String, Object> row(InvocationProof value) {
    var row = new HashMap<String, Object>();
    row.put("tenant_id", value.tenantId());
    row.put("id", value.id());
    row.put("task_id", value.taskId());
    row.put("step_id", value.stepId());
    row.put("attempt_id", value.attemptId());
    row.put("effect_intent_id", value.effectIntentId());
    row.put("slot", value.slot());
    row.put("provider_id", value.providerId());
    row.put("tool", value.tool());
    row.put("input_snapshot", value.inputSnapshot());
    row.put("input_digest", value.inputDigest());
    row.put("plugin_version", value.pluginVersion());
    row.put("plugin_digest", value.pluginDigest());
    row.put("installation_epoch", value.installationEpoch());
    row.put("output_snapshot", value.outputSnapshot());
    row.put("output_digest", value.outputDigest());
    row.put("state", value.state());
    return row;
  }
}
