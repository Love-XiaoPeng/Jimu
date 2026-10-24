package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts.Task;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** worker读取与checkpoint校验使用同一上下文快照规则。 */
public interface SessionContextService {
  String context(Lease lease);

  String snapshot(Task task);
}
