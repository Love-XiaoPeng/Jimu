package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Complete serialized input boundary. No provider tokenizer/template proof is assumed. */
public final class ModelInputBounds {
  private ModelInputBounds() {}

  public static void check(ModelSnapshot model, String body, boolean testMode) {
    var limit = model.inputLimit();
    if (limit == null) return; // Legacy full-context monetary reservation remains unchanged.
    // This is a synthetic billing contract, NOT an upper bound for Qwen or OpenAI tokens.
    // Its server bills exactly the complete HTTP request body UTF-8 octets, no hidden framing.
    // No real provider proof is registered; naming a proof in configuration cannot invent one.
    if (!testMode
        || !"test-whole-request-utf8-v1".equals(limit.proof())
        || !"trusted-local-v1".equals(model.transportPolicy())
        || !List.of("127.0.0.1", "[::1]").contains(URI.create(model.baseUrl()).getHost())
        || !"local-byte-accounted-v1".equals(model.model())
        || !"local-byte-accounted-v1".equals(model.revision()))
      throw new TaskFailure(503, ModelErrorCode.MODEL_INPUT_BOUND_UNAVAILABLE);
    if (body.getBytes(StandardCharsets.UTF_8).length > limit.maxTokens())
      throw new TaskFailure(429, ModelErrorCode.MODEL_INPUT_LIMIT_EXCEEDED);
  }
}
