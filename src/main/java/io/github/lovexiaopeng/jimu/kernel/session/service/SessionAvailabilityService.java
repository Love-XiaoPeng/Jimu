package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionAvailability;

/** 原会话发现资格、递归回退与拒绝边界。 */
public interface SessionAvailabilityService {
  boolean trace(SessionAvailability records);

  boolean prior(SessionAvailability records);

  boolean session(SessionAvailability records, boolean search);
}
