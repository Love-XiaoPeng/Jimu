package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;

public interface ModelDispatchRecords {
  interface Dispatch {
    boolean paid();

    boolean session();

    void settleCost(ModelUsage usage);

    void markFailed(String code);

    long reservedTokens();

    void settleTokens(long actual, long released);

    void holdUnknown();

    void accountSession();
  }

  Dispatch lockSettlement(Lease lease, String dispatch);
}
