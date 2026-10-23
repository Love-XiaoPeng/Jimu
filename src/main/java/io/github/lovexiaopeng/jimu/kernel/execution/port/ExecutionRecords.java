package io.github.lovexiaopeng.jimu.kernel.execution.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface ExecutionRecords {
  interface Task {
    boolean hasEvaluationRun();

    boolean cancellationRequested();

    boolean pendingEffect();

    void stopEvaluationRun();

    void terminal(String state, String code);
  }

  Task lockSettlement(Lease lease);
}
