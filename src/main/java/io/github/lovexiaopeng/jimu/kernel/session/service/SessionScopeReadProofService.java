package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Proof;

/** 核原scope读取的收据与直接来源，返回原输入摘要；不递归展开旧read。 */
public interface SessionScopeReadProofService {
  String verify(Proof proof);
}
