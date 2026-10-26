package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class WorkingDirectoryController {
  private final Authenticator authentication;
  private final WorkingDirectoryLedger directories;

  public WorkingDirectoryController(
      Authenticator authentication, WorkingDirectoryLedger directories) {
    this.authentication = authentication;
    this.directories = directories;
  }

  @GetMapping("/api/tasks/{taskId}/working-directory")
  public ResponseEntity<Map<String, Object>> get(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId,
      jakarta.servlet.http.HttpServletRequest request)
      throws java.io.IOException {
    var identity = authentication.authenticate(authorization);
    if (request.getQueryString() != null || request.getInputStream().read() != -1)
      throw new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
          400, CommonErrorCode.INVALID_REQUEST);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(directories.getWorkingDirectory(identity, taskId));
  }
}
