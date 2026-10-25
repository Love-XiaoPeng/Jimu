package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionInput;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionInputService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Preserve short circuits and the independent snapshot read after pin resolution. */
public final class QuestionInputServiceImpl implements QuestionInputService {
  public <P, R> R input(QuestionInput<P, R> port) {
    if (port.recordVersion() != 1
        || port.questionVersion() != 1
        || !port.bindingDigest().equals(port.taskBindingDigest())
        || !port.hash(port.requestSnapshot()).equals(port.requestDigest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return port.input(port.version(port.pin()), port.requestSnapshot());
  }
}
