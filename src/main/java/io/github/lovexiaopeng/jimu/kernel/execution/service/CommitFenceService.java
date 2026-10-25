package io.github.lovexiaopeng.jimu.kernel.execution.service;

import io.github.lovexiaopeng.jimu.kernel.execution.port.CommitFence;

public interface CommitFenceService {
  void require(CommitFence port);
}
