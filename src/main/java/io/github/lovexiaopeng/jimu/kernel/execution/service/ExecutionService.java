package io.github.lovexiaopeng.jimu.kernel.execution.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface ExecutionService {
  void stop(Lease lease, String code);
}
