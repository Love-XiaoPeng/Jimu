package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class SessionHistoryController {
  private final Authenticator authentication;
  private final SessionEventLedger history;

  public SessionHistoryController(Authenticator authentication, SessionEventLedger history) {
    this.authentication = authentication;
    this.history = history;
  }

  private static ResponseEntity<String> response(String raw) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body(raw);
  }

  @PostMapping("/api/tasks/{taskId}/session-history/search")
  public DeferredResult<ResponseEntity<String>> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      HttpServletRequest request) {
    var identity = authentication.authenticate(authorization);
    long deadline = System.nanoTime() + 2_000_000_000L;
    history.authorizeSessionSearch(identity, taskId, deadline);
    try {
      if (request.getQueryString() != null
          || request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType()))
          || request.getContentLengthLong() > 2048)
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    } catch (org.springframework.http.InvalidMediaTypeException invalid) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
    long millis = Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000);
    var result = new DeferredResult<ResponseEntity<String>>(millis);
    result.onTimeout(() -> result.setErrorResult(new TaskFailure(504, "SESSION_QUERY_TIMEOUT")));
    request.setAttribute(
        "jimu.session.search.request",
        new SessionEventLedger.SearchRequest(
            deadline,
            body ->
                result.setResult(
                    response(history.sessionSearch(identity, taskId, end -> body, deadline))),
            failure -> result.setErrorResult(failure)));
    return result;
  }

  @GetMapping("/api/tasks/{taskId}/session-history")
  public ResponseEntity<String> metadata(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      HttpServletRequest request)
      throws IOException {
    var identity = authentication.authenticate(authorization);
    String metadata = history.sessionHistory(identity, taskId);
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null)
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    return response(metadata);
  }

  @GetMapping("/api/tasks/{taskId}/session-history/{seq}")
  public ResponseEntity<String> event(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      @PathVariable String seq,
      HttpServletRequest request)
      throws IOException {
    var identity = authentication.authenticate(authorization);
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
      // Pass an invalid range to the ledger so it authorizes before reporting input errors.
      // The actual read and its authorization share one bounded transaction.
      counts.clear();
    }
    return response(
        history.sessionEvent(
            identity,
            taskId,
            target,
            counts.getOrDefault("before", 0L).intValue(),
            counts.getOrDefault("after", 0L).intValue()));
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
