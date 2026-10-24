package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionInputEligibility;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionInputService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 保留scope优先、原codec拒绝和目标短路；self/default read不加载资格。 */
public final class SessionInputServiceImpl implements SessionInputService {
  public void validate(SessionInputEligibility p) {
    if (p.scopeTask()) {
      if (p.tool().equals("session_search")) {
        var targets = p.scopeSearchTargets();
        var scope = p.requireScope();
        for (String id : targets) if (!scope.contains(id)) throw unavailable();
      } else if (p.tool().equals("session_event_read")) {
        String target = p.scopeReadTarget();
        if (target != null && !target.equals(p.callerId()) && !p.requireScope().contains(target))
          throw unavailable();
      } else throw invalid();
    } else if (p.tool().equals("session_event_trace") && p.traceTask()) {
      String target = p.traceTarget();
      if (target != null && !target.equals(p.callerId())) throw missing();
    } else if (p.tool().equals("session_event_search") && p.searchTask()) {
      String target = p.searchTarget();
      if (p.searchTask() && target != null && !target.equals(p.callerId())) throw missing();
    } else if (p.tool().equals("session_event_read")) {
      String target = p.nativeReadTarget();
      if (p.searchTask() && target != null && !target.equals(p.callerId())) throw missing();
    } else throw invalid();
  }

  private static TaskFailure unavailable() {
    return new TaskFailure(404, "SESSION_SCOPE_UNAVAILABLE");
  }

  private static TaskFailure missing() {
    return new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
  }

  private static TaskFailure invalid() {
    return new TaskFailure(400, "TOOL_INPUT_INVALID");
  }
}
