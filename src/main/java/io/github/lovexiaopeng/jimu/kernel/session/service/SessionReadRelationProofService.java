package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReadRelationProofs;
import java.util.List;

/** 验证来源快照及read原始执行关系，供事件写入与回放共用。 */
public interface SessionReadRelationProofService {
  List<SessionReadRelationProofs.Source> sources(SessionReadRelationProofs.Sources source);

  void verify(SessionReadRelationProofs.Proof proof);
}
