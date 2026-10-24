package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadRefs;

/** 事件写入和专用scope证明共用的refs完整性决策。 */
public interface SessionScopeReadRefsService {
  void verify(SessionScopeReadRefs refs);
}
