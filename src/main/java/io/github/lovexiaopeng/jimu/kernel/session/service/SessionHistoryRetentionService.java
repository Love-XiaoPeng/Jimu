package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions.LockedHistory;

/** 到期扫描与已有scope失效事务复用同一正文清理、账目和事件用例。 */
public interface SessionHistoryRetentionService {
  void cleanup();

  void purge(LockedHistory task);
}
