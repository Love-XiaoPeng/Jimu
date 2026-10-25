package io.github.lovexiaopeng.jimu.api.task.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import java.io.IOException;
import java.io.InputStream;

/** Strict HTTP decoding only; callers authenticate before obtaining the body stream. */
public final class TaskRequestDecoder {
  private static final int MAX_BODY_BYTES = 6291456;
  private static final ObjectMapper STRICT =
      new ObjectMapper()
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private TaskRequestDecoder() {}

  public static TaskSubmission decode(InputStream input) throws IOException {
    byte[] body = input.readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) throw new TaskFailure(413, "REQUEST_TOO_LARGE");
    SubmitRequest request;
    try {
      request = STRICT.readValue(body, SubmitRequest.class);
    } catch (JsonProcessingException invalid) {
      boolean image =
          invalid instanceof JsonMappingException mapping
              && mapping.getPath().stream()
                  .anyMatch(reference -> "images".equals(reference.getFieldName()));
      throw new TaskFailure(400, image ? "INVALID_IMAGE_INPUT" : "INVALID_REQUEST");
    }
    return request == null ? null : request.toSubmission();
  }
}
