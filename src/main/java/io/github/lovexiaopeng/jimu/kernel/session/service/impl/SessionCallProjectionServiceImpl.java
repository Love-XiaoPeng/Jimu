package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallProjections;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallProjectionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 仅原生/scope search的query脱敏；保留短路、解析/编码上限和逐项失败顺序。 */
public final class SessionCallProjectionServiceImpl implements SessionCallProjectionService {
  public void project(SessionCallProjections p) {
    boolean redacted = false;
    for (var call : p.calls()) {
      String arguments = call.arguments();
      if (p.searchTask() && call.name().equals("session_event_search")
          || p.scopeTask() && call.name().equals("session_search")) {
        call.parseObject(arguments, p.scopeTask() ? 4096 : 2048);
        var text = call.publicQuery();
        redacted |= text.redacted();
        arguments = call.replaceQuery(text.content());
        if (p.bytes(arguments) > (p.scopeTask() ? 4096 : 2048))
          throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
      }
      call.append(arguments);
    }
    p.complete(redacted);
  }
}
