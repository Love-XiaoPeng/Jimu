package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface ModelDispatchRecords {
  interface Dispatch {
    ModelCostRecords costs();

    boolean session();

    void markFailed(String code);

    long reservedTokens();

    void settleTokens(long actual, long released);

    void holdUnknown();

    void accountSession();
  }

  Dispatch lockSettlement(Lease lease, String dispatch);
}
