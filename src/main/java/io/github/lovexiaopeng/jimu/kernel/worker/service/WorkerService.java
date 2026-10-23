package io.github.lovexiaopeng.jimu.kernel.worker.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface WorkerService {
  java.util.Optional<Lease> claim(String worker);

  void quiescent(Lease lease);

  void processStopped(String worker);
}
