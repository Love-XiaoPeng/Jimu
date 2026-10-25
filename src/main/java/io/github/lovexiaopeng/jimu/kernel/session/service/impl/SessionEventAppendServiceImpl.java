package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventAppend;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionEventAppendService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Preserve the history lock, idempotent early return and atomic body/event/cursor order. */
public final class SessionEventAppendServiceImpl implements SessionEventAppendService {
  public long append(SessionEventAppend append) {
    boolean terminal = append.terminal();
    var history = append.history(!terminal);
    Long prior = append.prior();
    if (prior != null) return prior;
    long seq = history.next();
    if (seq >= (terminal ? 128 : 126)) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    var event = append.event(seq);
    append.validate(event);
    append.body(event.bytes());
    append.insert(seq, event);
    append.advance();
    return seq;
  }
}
