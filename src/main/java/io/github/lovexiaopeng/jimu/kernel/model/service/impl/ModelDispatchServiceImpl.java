package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.model.service.ModelDispatchService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;

public final class ModelDispatchServiceImpl implements ModelDispatchService {
  private final ModelDispatchRecords records;
  private final LedgerUnitOfWork work;

  public ModelDispatchServiceImpl(ModelDispatchRecords records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public void failed(Lease lease, String dispatch, String code, ModelUsage usage) {
    work.required(
        () -> {
          var row = records.lockSettlement(lease, dispatch);
          if (usage != null) row.settleCost(usage);
          else if (row.paid())
            row.settleCost(new ModelUsage(null, null, null, null, null, null, null, null));
          row.markFailed(code);
          Long tokens = usage == null ? null : usage.totalTokens();
          long reserved = row.reservedTokens();
          if (row.session() && tokens != null && tokens <= reserved)
            row.settleTokens(tokens, reserved - tokens);
          else row.holdUnknown();
          if (row.session()) row.accountSession();
          return null;
        });
  }
}
