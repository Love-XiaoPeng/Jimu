package io.github.lovexiaopeng.jimu.kernel.invocation.service.impl;

import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.service.InvocationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;

public final class InvocationServiceImpl implements InvocationService {
  private final InvocationRecords records;
  private final LedgerUnitOfWork work;

  public InvocationServiceImpl(InvocationRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public void failed(Lease lease, String invocation, String code) {
    work.required(
        () -> {
          var row = records.lockSettlement(lease, invocation);
          if (row.session()) {
            records.requireOrdinaryCommit(lease, invocation);
            throw new TaskFailure(409, "SESSION_READ_COMMIT_REQUIRED");
          }
          if (row.failRunning() != 1) throw new TaskFailure(409, "INVOCATION_STATE");
          row.settleUnit();
          row.failureEvent(code);
          return null;
        });
  }

  @Override
  public void uncertain(Lease lease, String invocation) {
    records.requireOrdinaryCommit(lease, invocation);
    work.required(
        () -> {
          var row = records.lockSettlement(lease, invocation);
          row.markUnknown();
          row.holdUnknown();
          row.reconciliationRequired();
          return null;
        });
  }
}
