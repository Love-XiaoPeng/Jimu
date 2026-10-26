package io.github.lovexiaopeng.jimu.api;

import static io.github.lovexiaopeng.jimu.api.session.vo.SessionHistoryHttpResponse.response;

import io.github.lovexiaopeng.jimu.api.session.dto.SessionEventRequest;
import io.github.lovexiaopeng.jimu.api.session.dto.SessionHistoryHttpRequest;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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

  @PostMapping("/api/tasks/{taskId}/session-history/search")
  public DeferredResult<ResponseEntity<String>> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      HttpServletRequest request) {
    var identity = authentication.authenticate(authorization);
    long deadline = System.nanoTime() + 2_000_000_000L;
    history.authorizeSessionSearch(identity, taskId, deadline);
    SessionHistoryHttpRequest.validateJson(request, 2048);
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

  @PostMapping("/api/tasks/{taskId}/session-history/trace")
  public DeferredResult<ResponseEntity<String>> trace(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      HttpServletRequest request) {
    var identity = authentication.authenticate(authorization);
    long deadline = System.nanoTime() + 2_000_000_000L;
    history.authorizeSessionTrace(identity, taskId, deadline);
    SessionHistoryHttpRequest.validateJson(request, 1024);
    long millis = Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000);
    var result = new DeferredResult<ResponseEntity<String>>(millis);
    result.onTimeout(() -> result.setErrorResult(new TaskFailure(504, "SESSION_QUERY_TIMEOUT")));
    request.setAttribute(
        "jimu.session.search.request",
        new SessionEventLedger.SearchRequest(
            deadline,
            body ->
                result.setResult(
                    response(history.sessionTrace(identity, taskId, end -> body, deadline))),
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
    SessionHistoryHttpRequest.validateMetadata(request);
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
    var range = SessionEventRequest.parse(seq, request);
    return response(
        history.sessionEvent(identity, taskId, range.target(), range.before(), range.after()));
  }
}
