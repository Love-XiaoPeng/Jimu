package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.TodoLedger;
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
public final class TodoController {
  private final Authenticator authentication;
  private final TodoLedger todos;

  public TodoController(Authenticator authentication, TodoLedger todos) {
    this.authentication = authentication;
    this.todos = todos;
  }

  @GetMapping("/api/tasks/{taskId}/todos")
  public ResponseEntity<Map<String, Object>> get(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(todos.getTodos(authentication.authenticate(authorization), taskId));
  }
}
