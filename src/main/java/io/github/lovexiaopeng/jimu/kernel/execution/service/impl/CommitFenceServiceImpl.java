package io.github.lovexiaopeng.jimu.kernel.execution.service.impl;

import io.github.lovexiaopeng.jimu.kernel.execution.port.CommitFence;
import io.github.lovexiaopeng.jimu.kernel.execution.service.CommitFenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Original shared commit fence: only exactly one updated row succeeds. */
public final class CommitFenceServiceImpl implements CommitFenceService {
  public void require(CommitFence port) {
    if (port.update() != 1) throw new TaskFailure(409, "FENCE_REJECTED");
  }
}
