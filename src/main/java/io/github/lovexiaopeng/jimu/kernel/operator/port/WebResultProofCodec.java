package io.github.lovexiaopeng.jimu.kernel.operator.port;

import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.InvocationProof;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.StepProof;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords.TaskProof;

/** 原插件/快照/结果证明校验器；不读取存储、不恢复或结算。 */
public interface WebResultProofCodec {
  String hash(String value);

  String receipt(TaskProof task, StepProof step, InvocationProof invocation, String output);
}
