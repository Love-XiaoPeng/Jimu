package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadRefs;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeReadRefsService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;

/** 保留READ/终态、冻结契约、字节/摘要、原节点提取的拒绝顺序。 */
public final class SessionScopeReadRefsServiceImpl implements SessionScopeReadRefsService {
  public void verify(SessionScopeReadRefs refs) {
    var b = refs.binding();
    if (!"READ".equals(b.operation()) || !List.of("SUCCEEDED", "FAILED").contains(b.outcome()))
      throw corrupt();
    String raw = b.raw();
    refs.validateRefs(raw);
    if (refs.bytes(raw) != b.bytes() || !refs.hash(raw).equals(b.digest())) throw corrupt();
    refs.retainNodes(raw);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }
}
