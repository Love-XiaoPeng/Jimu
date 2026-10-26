package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionContentState;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryProofService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;

/** 保留原短路和异常边界；history的FOR UPDATE仅在原调用位置执行。 */
public final class SessionHistoryProofServiceImpl implements SessionHistoryProofService {
  public void history(SessionHistoryProofs.History p, boolean readable) {
    var h = p.load();
    if (h == null) throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_UNAVAILABLE);
    if (h.recordVersion() != 1
        || h.version() != p.version()
        || !p.binding().equals(h.snapshot())
        || !p.hash(p.binding()).equals(h.digest()))
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_VERSION_UNSUPPORTED);
    if (readable && (!SessionContentState.PRESENT.equals(h.state()) || h.retained() != 1))
      throw new TaskFailure(410, SessionErrorCode.SESSION_HISTORY_EXPIRED);
  }

  public void validateStep(SessionHistoryProofs.Step p) {
    var st = p.step();
    if (st.version() != 1 || !p.task().equals(st.task()) || st.upper() == null)
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_VERSION_UNSUPPORTED);
    String request = st.request();
    if (!p.hash(request).equals(st.requestDigest())) throw corrupt();
    if (p.checkpointVersion(request) != 2 || p.messagesEmpty()) throw corrupt();
    String context = p.lastContent();
    p.validateContext(context);
    var value = p.context(context);
    if (!p.lastRole().equals("user")
        || !p.task().equals(value.session())
        || !p.hash(context).equals(st.contextDigest())
        || st.upper() != value.upper()
        || !p.historyBinding().equals(st.bindingDigest())) throw corrupt();
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }
}
