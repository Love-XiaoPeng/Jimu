package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage;

public interface SessionHistoryStorageService {
  /** 执行用例复用的惰性存储事实；不包含业务决定。 */
  interface Facts extends SessionHistoryStorage {}

  default void body(Facts facts, long additional) {
    body((SessionHistoryStorage) facts, additional);
  }

  void create(SessionHistoryStorage storage);

  void body(SessionHistoryStorage storage, long additional);
}
