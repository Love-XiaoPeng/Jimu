package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionLineageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/** v5 owner入口；认证/路径核权先于异步正文读取，业务在发布用例内完成。 */
@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class SessionLineageController {
  private final Authenticator authentication;
  private final SessionLineageLedger lineages;

  public SessionLineageController(Authenticator authentication, SessionLineageLedger lineages) {
    this.authentication = authentication;
    this.lineages = lineages;
  }

  private static void json(HttpServletRequest request, int limit) {
    try {
      if (request.getQueryString() != null
          || request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType()))
          || request.getContentLengthLong() > limit)
        throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    } catch (org.springframework.http.InvalidMediaTypeException failure) {
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  private static <T> DeferredResult<ResponseEntity<T>> result(long deadline) {
    var result =
        new DeferredResult<ResponseEntity<T>>(
            Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000));
    result.onTimeout(
        () -> result.setErrorResult(new TaskFailure(504, SessionErrorCode.SESSION_QUERY_TIMEOUT)));
    return result;
  }

  @PostMapping("/api/session-trace/tasks")
  public DeferredResult<ResponseEntity<Tasks.View>> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    long deadline = System.nanoTime() + SessionHistoryPolicyV1.QUERY_NANOS;
    var identity = authentication.authenticate(authorization);
    json(request, 16384);
    DeferredResult<ResponseEntity<Tasks.View>> result = result(deadline);
    request.setAttribute(
        "jimu.session.search.request",
        new SessionEventLedger.SearchRequest(
            deadline,
            body -> {
              var view = lineages.submitSessionLineage(identity, key, end -> body, deadline);
              result.setResult(
                  ResponseEntity.accepted()
                      .location(URI.create("/api/tasks/" + view.taskId()))
                      .header("Cache-Control", "no-store")
                      .body(view));
            },
            failure -> result.setErrorResult(failure)));
    return result;
  }

  @PostMapping("/api/tasks/{caller}/session-history/session-trace")
  public DeferredResult<ResponseEntity<String>> trace(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String caller,
      HttpServletRequest request) {
    long deadline = System.nanoTime() + SessionHistoryPolicyV1.QUERY_NANOS;
    var identity = authentication.authenticate(authorization);
    var resources = lineages.authorizeSessionLineage(identity, caller, deadline);
    json(request, 1024);
    DeferredResult<ResponseEntity<String>> result = result(deadline);
    request.setAttribute(
        "jimu.session.search.request",
        new SessionEventLedger.SearchRequest(
            deadline,
            body ->
                result.setResult(
                    ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Cache-Control", "no-store")
                        .body(
                            lineages.sessionLineage(
                                identity, caller, end -> body, deadline, resources))),
            failure -> result.setErrorResult(failure)));
    return result;
  }

  @DeleteMapping("/api/tasks/{caller}/session-trace-scope")
  public ResponseEntity<Void> revoke(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String caller,
      HttpServletRequest request) {
    long deadline = System.nanoTime() + SessionHistoryPolicyV1.QUERY_NANOS;
    var identity = authentication.authenticate(authorization);
    boolean valid =
        request.getQueryString() == null
            && request.getContentLengthLong() <= 0
            && request.getHeader("Transfer-Encoding") == null;
    lineages.revokeSessionLineage(identity, caller, valid, deadline);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }
}
