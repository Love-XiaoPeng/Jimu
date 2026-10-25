package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage;

public interface SessionHistoryStorageService {
  void create(SessionHistoryStorage storage);

  void body(SessionHistoryStorage storage, long additional);
}
