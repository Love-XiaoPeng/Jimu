package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionInputEligibility;

/** 模型受理与response共用的工具/版本/目标资格规则。 */
public interface SessionInputService {
  void validate(SessionInputEligibility input);
}
