package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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

@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class SessionSearchController {
  private final Authenticator authentication;
  private final SessionSearchScopeLedger scopes;

  public SessionSearchController(Authenticator authentication, SessionSearchScopeLedger scopes) {
    this.authentication = authentication;
    this.scopes = scopes;
  }

  private static void json(HttpServletRequest request, int limit) {
    try {
      if (request.getQueryString() != null
          || request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType()))
          || request.getContentLengthLong() > limit)
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    } catch (org.springframework.http.InvalidMediaTypeException failure) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  @PostMapping("/api/session-search/tasks")
  public DeferredResult<ResponseEntity<io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View>>
      submit(
          @RequestHeader(value = "Authorization", required = false) String authorization,
          @RequestHeader(value = "Idempotency-Key", required = false) String key,
          HttpServletRequest request) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    var identity = authentication.authenticate(authorization);
    json(request, 16384);
    var result =
        new DeferredResult<ResponseEntity<io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View>>(
            Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000));
    result.onTimeout(() -> result.setErrorResult(new TaskFailure(504, "SESSION_QUERY_TIMEOUT")));
    request.setAttribute(
        "jimu.session.search.request",
        new SessionEventLedger.SearchRequest(
            deadline,
            body -> {
              var view = scopes.submitSessionSearch(identity, key, end -> body, deadline);
              result.setResult(
                  ResponseEntity.accepted()
                      .location(URI.create("/api/tasks/" + view.taskId()))
                      .header("Cache-Control", "no-store")
                      .body(view));
            },
            failure -> result.setErrorResult(failure)));
    return result;
  }

  @PostMapping("/api/tasks/{caller}/session-history/session-search")
  public DeferredResult<ResponseEntity<String>> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String caller,
      HttpServletRequest request) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    var identity = authentication.authenticate(authorization);
    var resources = scopes.authorizePriorSessionSearch(identity, caller, deadline);
    json(request, 4096);
    var result =
        new DeferredResult<ResponseEntity<String>>(
            Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000));
    result.onTimeout(() -> result.setErrorResult(new TaskFailure(504, "SESSION_QUERY_TIMEOUT")));
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
                            scopes.priorSessionSearch(
                                identity, caller, end -> body, deadline, resources))),
            failure -> result.setErrorResult(failure)));
    return result;
  }

  @DeleteMapping("/api/tasks/{caller}/session-search-scope")
  public ResponseEntity<Void> revoke(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String caller,
      HttpServletRequest request) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    var identity = authentication.authenticate(authorization);
    boolean validRequest =
        request.getQueryString() == null
            && request.getContentLengthLong() <= 0
            && request.getHeader("Transfer-Encoding") == null;
    scopes.revokeSessionSearchScope(identity, caller, validRequest, deadline);
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }
}
