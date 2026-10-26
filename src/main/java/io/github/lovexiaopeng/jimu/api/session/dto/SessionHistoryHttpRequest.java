package io.github.lovexiaopeng.jimu.api.session.dto;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/** 历史接口的局部HTTP边界校验；调用方必须先完成原路径核权。 */
public final class SessionHistoryHttpRequest {
  private SessionHistoryHttpRequest() {}

  public static void validateJson(HttpServletRequest request, long maxLength) {
    try {
      if (request.getQueryString() != null
          || request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType()))
          || request.getContentLengthLong() > maxLength)
        throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    } catch (InvalidMediaTypeException invalid) {
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  public static void validateMetadata(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null)
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
  }
}
