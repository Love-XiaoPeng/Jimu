package io.github.lovexiaopeng.jimu.kernel.worker.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.WorkerProcess;
import java.util.List;
import java.util.Optional;

public interface WorkerService {
  boolean renew(Lease lease);

  void register(
      String worker,
      String host,
      long pid,
      long startTicks,
      String bootId,
      String observerPidNamespace);

  List<WorkerProcess> processes(String host);

  Optional<Lease> claim(String worker);

  void quiescent(Lease lease);

  void processStopped(String worker);
}
