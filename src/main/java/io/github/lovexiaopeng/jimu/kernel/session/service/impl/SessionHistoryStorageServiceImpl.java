package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;

/** Preserve lazy validation, INSERT SELECT and subsequent conditional body accounting. */
public final class SessionHistoryStorageServiceImpl implements SessionHistoryStorageService {
  public void create(SessionHistoryStorage storage) {
    var binding = storage.binding();
    storage.validateGoal();
    storage.insert(binding);
    body(storage, storage.initialBytes());
  }

  public void body(SessionHistoryStorage storage, long additional) {
    if (!storage.session()) return;
    if (storage.add(additional) != 1)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
  }
}
