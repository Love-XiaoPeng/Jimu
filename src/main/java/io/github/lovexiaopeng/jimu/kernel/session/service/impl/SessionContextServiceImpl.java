package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts.Context;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts.Task;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionContextService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;

/** 先锁定当前资格，再读取有界快照；验证完成后才计账，拒绝保留原事务回滚。 */
public final class SessionContextServiceImpl implements SessionContextService {
  private final SessionContexts records;

  public SessionContextServiceImpl(SessionContexts records) {
    this.records = records;
  }

  public String context(Lease lease) {
    return records.required(
        () -> {
          var task = records.lock(lease);
          String raw = snapshot(task);
          task.account();
          return raw;
        });
  }

  public String snapshot(Task task) {
    long upper = task.upper();
    var recent = task.recent(upper);
    if (recent.size() != Math.min(8, upper + 1))
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    return task.encode(new Context(task.version(), task.id(), upper, recent, task.scope()));
  }
}
