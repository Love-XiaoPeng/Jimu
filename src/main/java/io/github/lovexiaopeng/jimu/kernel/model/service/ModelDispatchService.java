package io.github.lovexiaopeng.jimu.kernel.model.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;

public interface ModelDispatchService {
  void failed(Lease lease, String dispatch, String code, ModelUsage usage);
}
