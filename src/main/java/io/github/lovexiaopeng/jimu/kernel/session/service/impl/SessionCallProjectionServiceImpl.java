package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.PriorSessionPolicyV4;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionEventSearchPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallProjections;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionCallProjectionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;

/** 仅原生/scope search的query脱敏；保留短路、解析/编码上限和逐项失败顺序。 */
public final class SessionCallProjectionServiceImpl implements SessionCallProjectionService {
  public void project(SessionCallProjections p) {
    boolean redacted = false;
    for (var call : p.calls()) {
      String arguments = call.arguments();
      if (p.searchTask() && call.name().equals(ToolCapability.SESSION_EVENT_SEARCH)
          || p.scopeTask() && call.name().equals(ToolCapability.SESSION_SEARCH)) {
        call.parseObject(
            arguments,
            p.scopeTask()
                ? PriorSessionPolicyV4.INPUT_BYTES
                : SessionEventSearchPolicyV1.INPUT_BYTES);
        var text = call.publicQuery();
        redacted |= text.redacted();
        arguments = call.replaceQuery(text.content());
        if (p.bytes(arguments)
            > (p.scopeTask()
                ? PriorSessionPolicyV4.INPUT_BYTES
                : SessionEventSearchPolicyV1.INPUT_BYTES))
          throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
      }
      call.append(arguments);
    }
    p.complete(redacted);
  }
}
