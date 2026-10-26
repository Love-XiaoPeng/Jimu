package io.github.lovexiaopeng.jimu.api.session.dto;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/** 历史事件的严格十进制请求；拒绝值交账本先核权，不在解析阶段暴露输入错误。 */
public record SessionEventRequest(long target, int before, int after) {
  public static SessionEventRequest parse(String seq, HttpServletRequest request) {
    Map<String, Long> counts = new HashMap<>();
    long target = -1;
    try {
      if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
      if (request.getQueryString() != null) {
        for (String part : request.getQueryString().split("&", -1)) {
          String[] pair = part.split("=", -1);
          if (pair.length != 2
              || !java.util.List.of("before", "after").contains(pair[0])
              || counts.containsKey(pair[0])) throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          long value = decimal(pair[1]);
          if (value > 4) throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          counts.put(pair[0], value);
        }
      }
      target = decimal(seq);
    } catch (TaskFailure invalid) {
      // 实际读取及授权仍由账本在同一有界事务内完成。
      counts.clear();
    }
    return new SessionEventRequest(
        target,
        counts.getOrDefault("before", 0L).intValue(),
        counts.getOrDefault("after", 0L).intValue());
  }

  private static long decimal(String value) {
    if (!value.matches("0|[1-9][0-9]{0,15}")) throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    try {
      long number = Long.parseLong(value);
      if (number > 9007199254740991L) throw new NumberFormatException();
      return number;
    } catch (NumberFormatException invalid) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }
}
