package io.github.lovexiaopeng.jimu.kernel.invocation.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface InvocationRecords {
  interface Invocation {
    boolean session();

    int failRunning();

    void settleUnit();

    void failureEvent(String code);

    void markUnknown();

    void holdUnknown();

    void reconciliationRequired();
  }

  void requireOrdinaryCommit(Lease lease, String invocation);

  Invocation lockSettlement(Lease lease, String invocation);
}
