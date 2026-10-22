package io.github.lovexiaopeng.jimu.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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
  private static final JsonMapper STRICT =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(16).build())
                  .build())
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();
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
    if (key == null || !key.matches("[!-~]{1,128}")) throw new TaskFailure(400, "INVALID_REQUEST");
    byte[] bytes = request.getInputStream().readNBytes(32769);
    if (bytes.length > 32768) throw new TaskFailure(413, "REQUEST_TOO_LARGE");
    UserQuestionLedger.Command command;
    try {
      var node = STRICT.readTree(bytes);
      if (node == null
          || !node.path("questionVersion").isIntegralNumber()
          || !node.path("expectedRevision").isIntegralNumber())
        throw new IllegalArgumentException("Integer required");
      command = STRICT.treeToValue(node, UserQuestionLedger.Command.class);
      for (var answer : node.path("answers"))
        if (answer.has("custom") && answer.get("custom").isNull())
          throw new IllegalArgumentException("Custom must be a string when present");
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "QUESTION_ANSWER_INVALID");
    }
    var result = questions.answer(identity, taskId, requestId, key, command);
    return ResponseEntity.status(result.replay() ? 200 : 202).body(result.receipt());
  }
}
