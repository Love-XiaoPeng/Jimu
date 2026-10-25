package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEligibility;

public interface SessionEligibilityService {
  void access(SessionEligibility.Access records);

  void admission(SessionEligibility.Admission records);
}
