package io.github.lovexiaopeng.jimu.kernel.invocation.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface InvocationService {
  void failed(Lease lease, String invocation, String code);

  void uncertain(Lease lease, String invocation);
}
