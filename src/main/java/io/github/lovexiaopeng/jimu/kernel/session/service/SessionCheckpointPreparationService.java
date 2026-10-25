package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCheckpointPreparation;

/** 校验并绑定原检查点，正文账目沿调用者的工作单元完成。 */
public interface SessionCheckpointPreparationService {
  void prepare(SessionCheckpointPreparation storage, String request);
}
