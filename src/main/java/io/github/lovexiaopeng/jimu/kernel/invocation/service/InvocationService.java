package io.github.lovexiaopeng.jimu.kernel.invocation.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

public interface InvocationService {
  Invocation admit(Lease lease, String step, Call call, String version, String digest);

  void succeeded(Lease lease, String invocation, String output);

  void commit(Lease lease, String invocation, String output, boolean mcpCommit);

  void failed(Lease lease, String invocation, String code);

  void uncertain(Lease lease, String invocation);
}
