package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.api.interaction.dto.QuestionAnswerRequestDecoder;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class UserQuestionController {
  private final Authenticator authentication;
  private final UserQuestionLedger questions;

  public UserQuestionController(Authenticator authentication, UserQuestionLedger questions) {
    this.authentication = authentication;
    this.questions = questions;
  }

  @GetMapping("/api/tasks/{taskId}/questions")
  public Map<String, Object> list(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String taskId) {
    return questions.listQuestions(authentication.authenticate(authorization), taskId);
  }

  @PostMapping("/api/tasks/{taskId}/questions/{requestId}/answers")
  public ResponseEntity<Map<String, Object>> answer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String taskId,
      @PathVariable String requestId,
      jakarta.servlet.http.HttpServletRequest request)
      throws IOException {
    var identity = authentication.authenticate(authorization);
    questions.authorizeAnswer(identity, taskId, requestId);
    var command = QuestionAnswerRequestDecoder.decode(key, request);
    var result = questions.answer(identity, taskId, requestId, key, command);
    return ResponseEntity.status(result.replay() ? 200 : 202).body(result.receipt());
  }
}
