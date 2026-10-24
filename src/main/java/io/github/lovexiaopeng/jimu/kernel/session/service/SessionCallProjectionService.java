package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallProjections;

/** response预检与事件append共用的公开调用投影规则。 */
public interface SessionCallProjectionService {
  void project(SessionCallProjections calls);
}
