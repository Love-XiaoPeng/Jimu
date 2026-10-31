package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallBindings;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallBindingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;

/** Binding decisions retain original history lock, branch, refusal and accounting order. */
public final class SessionCallBindingServiceImpl implements SessionCallBindingService {
  public void bind(SessionCallBindings b) {
    b.lockHistory();
    b.loadStep();
    if (!b.hasUpper() || !b.historyBinding().equals(b.stepBinding())) throw corrupt();
    for (var call : b.invocations()) {
      if (b.scopeTask()) {
        call.requireScope();
        boolean lineage = ToolCapability.SESSION_TRACE.equals(call.tool()) && b.lineageTask();
        if (lineage) call.normalizeLineage();
        else call.normalizeScope();
        call.loadCut();
        if (!call.authority().equals(call.cutAuthority())
            || !call.stepContext().equals(call.cutContext())) throw corrupt();
        if (lineage) call.insertLineage();
        else call.insertScope();
        call.chargeScope();
        continue;
      }
      if (call.tool().equals(ToolCapability.SESSION_EVENT_TRACE)) {
        if (!b.traceTask()) throw corrupt();
        call.parseTrace();
        call.insertTrace();
        continue;
      }
      if (call.tool().equals(ToolCapability.SESSION_EVENT_SEARCH)) {
        if (!b.searchTask()) throw corrupt();
        call.normalizeSearch();
        call.chargeSearch();
        call.insertSearch();
        continue;
      }
      call.parseRead();
      call.prepareRead();
      call.insertRead(b.traceTask() ? 1 : 0);
    }
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }
}
