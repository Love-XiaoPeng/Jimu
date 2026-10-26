package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionOwners;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionOwnerService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;

public final class SessionOwnerServiceImpl implements SessionOwnerService {
  public <R> R owner(Identity identity, String taskId, SessionOwners<R> port) {
    if (!taskId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    port.bucket(identity.tenant());
    port.lockScope(identity.tenant(), taskId);
    var found = port.find(identity.tenant(), taskId, identity.actor());
    if (found.isEmpty()) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var task = found.getFirst();
    if (!port.sessionTask(task)) port.identity(identity);
    port.access(task);
    return task;
  }
}
