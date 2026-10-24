package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs;

/** 历史可用/版本/保留及Checkpoint上下文绑定的共享用例。 */
public interface SessionHistoryProofService {
  void history(SessionHistoryProofs.History proofs, boolean readable);

  void validateStep(SessionHistoryProofs.Step proofs);
}
