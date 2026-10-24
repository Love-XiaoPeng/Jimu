package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Proof;

/** 在调用者原事务和query内证明公共历史事件，既不写入也不重放。 */
public interface SessionEventProofService {
  void verify(Proof proof);
}
