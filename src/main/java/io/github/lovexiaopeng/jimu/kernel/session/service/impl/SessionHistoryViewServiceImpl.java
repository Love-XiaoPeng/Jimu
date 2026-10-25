package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryViews;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryViewService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.LinkedHashMap;
import java.util.List;

/** 保留可读性短路、字段求值顺序、scope 核权位置及 upper 的原算术边界。 */
public final class SessionHistoryViewServiceImpl implements SessionHistoryViewService {
  public String owner(SessionHistoryViews.Owner records) {
    return records.required(() -> metadata(records.lock()));
  }

  public String metadata(SessionHistoryViews.Projection records) {
    var history = records.history();
    long upper = history.nextSeq() - 1;
    boolean readable = "PRESENT".equals(history.contentState()) && history.retained() == 1;
    var value = new LinkedHashMap<String, Object>();
    value.put("version", records.version());
    value.put("session_id", records.sessionId());
    value.put("content_state", history.contentState());
    value.put("upper_seq", upper);
    value.put("recent", readable ? records.recent(upper) : List.of());
    value.put("user_message_seq", 0);
    value.put("as_of_sse_seq", records.asOfSseSeq());
    value.put("retain_until", history.retainUntil());
    value.put("readable", readable);
    if (records.scopeTask()) {
      var scope = records.requireScope();
      value.put("scope_sha256", scope.digest());
      value.put("scope_state", scope.state());
    }
    String raw = records.encode(value);
    records.validateOwner(raw);
    return raw;
  }

  public SessionHistoryViews.RecentRows recent(
      SessionHistoryViews.RecentQuery records, long upper) {
    var result = records.read(upper);
    if (result.size() != Math.min(8, upper + 1))
      throw new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
    return result;
  }
}
