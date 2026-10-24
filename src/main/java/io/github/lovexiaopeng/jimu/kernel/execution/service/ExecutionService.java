package io.github.lovexiaopeng.jimu.kernel.execution.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Step;
import java.util.Optional;

/** checkpoint、prepare、finish和停止的业务决策与原子边界。 */
public interface ExecutionService {
  Optional<Step> checkpoint(Lease lease);

  Step prepare(Lease lease, String request);

  boolean finish(Lease lease, String summary);

  void stop(Lease lease, String code);
}
