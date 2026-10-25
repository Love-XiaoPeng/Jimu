package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryViews;

/** 原 owner 历史快照、有界 recent 完整性及冻结字段投影。 */
public interface SessionHistoryViewService {
  String owner(SessionHistoryViews.Owner records);

  String metadata(SessionHistoryViews.Projection records);

  SessionHistoryViews.RecentRows recent(SessionHistoryViews.RecentQuery records, long upper);
}
