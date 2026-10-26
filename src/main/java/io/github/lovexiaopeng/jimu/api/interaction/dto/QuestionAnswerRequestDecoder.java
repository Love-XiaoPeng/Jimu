package io.github.lovexiaopeng.jimu.api.interaction.dto;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** 回答请求的严格HTTP解码；核权由调用者完成，先校验key再有界读取正文。 */
public final class QuestionAnswerRequestDecoder {
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

  private QuestionAnswerRequestDecoder() {}

  /** 保留传输IOException；仅将JSON解码失败转换为回答协议错误。 */
  public static UserQuestionLedger.Command decode(String key, HttpServletRequest request)
      throws IOException {
    if (key == null || !key.matches("[!-~]{1,128}"))
      throw new TaskFailure(400, CommonErrorCode.INVALID_REQUEST);
    byte[] bytes = request.getInputStream().readNBytes(QuestionPolicyV1.ANSWER_HTTP_BYTES + 1);
    if (bytes.length > QuestionPolicyV1.ANSWER_HTTP_BYTES)
      throw new TaskFailure(413, CommonErrorCode.REQUEST_TOO_LARGE);
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
      throw new TaskFailure(400, QuestionErrorCode.QUESTION_ANSWER_INVALID);
    }
    return command;
  }
}
