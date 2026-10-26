package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionOwners;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;

public interface SessionOwnerService {
  <R> R owner(Identity identity, String taskId, SessionOwners<R> port);
}
