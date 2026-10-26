package io.github.lovexiaopeng.jimu.api.task.dto;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.regex.Pattern;

/** Last-Event-ID value parsing, after the controller has authorized the canonical task ID. */
public final class TaskEventCursor {
  private TaskEventCursor() {}

  public static long parse(String id, String last) {
    if (last == null) return 0;
    if (!last.matches(Pattern.quote(id) + ":[1-9][0-9]{0,18}"))
      throw new TaskFailure(400, TaskErrorCode.INVALID_CURSOR);
    try {
      return Long.parseLong(last.substring(id.length() + 1));
    } catch (NumberFormatException e) {
      throw new TaskFailure(400, TaskErrorCode.INVALID_CURSOR);
    }
  }
}
