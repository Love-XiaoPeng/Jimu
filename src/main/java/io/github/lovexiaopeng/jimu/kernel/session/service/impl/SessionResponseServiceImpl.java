package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionResponsePreflight;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionResponseService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留预留、解码、逐项资格、公开文本与完整 preview 的原短路和异常顺序。 */
public final class SessionResponseServiceImpl implements SessionResponseService {
  public void validate(SessionResponsePreflight storage, String response, List<Call> calls) {
    // 容量拒绝仍由外层已知 usage 路径结算，不能推迟到槽位绑定之后。
    storage.metadata(2048L + calls.size() * 4096L);
    if (storage.bytes(response) > SessionHistoryPolicyV1.RESPONSE_BYTES
        || calls.size() > SessionHistoryPolicyV1.RESPONSE_CALLS)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    var parsed = storage.decode(response);
    if (parsed.version() != 2 || parsed.callCount() != calls.size())
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    for (int index = 0; index < calls.size(); index++) {
      var call = calls.get(index);
      var wire = parsed.call(index);
      if (!new Call(index + ":" + wire.id(), wire.id(), wire.name(), wire.arguments()).equals(call)
          || !"function".equals(wire.type())
          || !(ToolCapability.SESSION_EVENT_READ.equals(call.tool())
              || storage.scopeTask() && call.tool().equals(ToolCapability.SESSION_SEARCH)
              || storage.searchTask() && call.tool().equals(ToolCapability.SESSION_EVENT_SEARCH)
              || storage.traceTask() && call.tool().equals(ToolCapability.SESSION_EVENT_TRACE)))
        throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
      storage.callId(call.providerId());
      storage.validateInput(call);
    }
    var text = storage.publicText(parsed.content());
    var preview = new LinkedHashMap<String, Object>();
    preview.put("version", storage.version());
    preview.put("session_id", storage.sessionId());
    preview.put("seq", SessionHistoryPolicyV1.LAST_PUBLIC_SEQ);
    preview.put("type", "model.response");
    preview.put("time", "2026-10-23T00:00:00.000000Z");
    preview.put("source", storage.source("model", "00000000-0000-4000-8000-000000000000", null));
    preview.put("content_policy", "public-v1");
    var projected = parsed.project();
    preview.put("redacted", text.redacted() || projected.redacted());
    preview.put("data", Map.of("content", text.content(), "calls", projected.calls()));
    if (storage.traceTask()) preview.put("source_event_seqs", List.of());
    if (storage.scopeTask()) preview.put("source_event_refs", List.of());
    storage.event(preview);
  }
}
