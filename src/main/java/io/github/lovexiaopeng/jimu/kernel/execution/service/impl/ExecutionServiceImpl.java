package io.github.lovexiaopeng.jimu.kernel.execution.service.impl;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.execution.service.ExecutionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;

public final class ExecutionServiceImpl implements ExecutionService {
  private final ExecutionRecords records;
  private final LedgerUnitOfWork work;

  public ExecutionServiceImpl(ExecutionRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public void stop(Lease lease, String code) {
    work.required(
        () -> {
          var task = records.lockSettlement(lease);
          if (code.equals("EVALUATION_MATERIAL_MISMATCH") && task.hasEvaluationRun())
            task.stopEvaluationRun();
          if (task.pendingEffect()
              || List.of("SNAPSHOT_CORRUPT", "MODEL_SETTLEMENT_PROOF_INVALID").contains(code))
            task.terminal("NEEDS_RECONCILIATION", code);
          else task.terminal(task.cancellationRequested() ? "CANCELLED" : "FAILED", code);
          return null;
        });
  }
}
