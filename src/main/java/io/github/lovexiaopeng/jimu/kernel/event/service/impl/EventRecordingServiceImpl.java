package io.github.lovexiaopeng.jimu.kernel.event.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.event.api.ReliableEventPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.event.port.EventRecords;
import io.github.lovexiaopeng.jimu.kernel.event.service.EventRecordingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;

/** 保留普通254/保留256名额、先插事件再session/directory/todo计账的原顺序。 */
public final class EventRecordingServiceImpl implements EventRecordingService {
  public void record(EventRecords records, String fact, String type, String code) {
    if (records.exists(fact)) return;
    if (records.todo() || records.directory() || records.session()) {
      long seq = records.sequence();
      boolean reserved =
          type.equals("session_history.content_deleted")
              || type.equals("working_directory.content_deleted")
              || type.equals("todo.content_deleted")
              || type.equals("task.completed")
              || type.equals("task.failed")
              || type.equals("task.cancelled")
              || type.equals("task.needs_reconciliation");
      if (seq
          >= (reserved
              ? ReliableEventPolicyV1.RESERVED_EVENTS
              : ReliableEventPolicyV1.ORDINARY_EVENTS))
        throw new TaskFailure(
            429,
            records.session()
                ? SessionErrorCode.SESSION_HISTORY_LIMIT
                : records.directory()
                    ? DirectoryErrorCode.DIRECTORY_EXECUTION_LIMIT
                    : TodoErrorCode.TODO_EXECUTION_LIMIT);
    }
    records.advance();
    long seq = records.sequence();
    records.insert(
        seq,
        fact,
        type,
        code,
        "artifact.created".equals(type) ? fact.substring("published:".length()) : null);
    records.sessionFact(fact);
    if (records.session())
      records.sessionAccount(
          fact.equals("terminal") || type.equals("session_history.content_deleted"));
    if (records.directory())
      records.directoryAccount(
          type.equals("working_directory.content_deleted") || fact.equals("terminal"));
    if (records.todo())
      records.todoAccount(type.equals("todo.content_deleted") || fact.equals("terminal"));
  }
}
