package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Explicit v3 capability; model names and current documentation never upgrade an old pin. */
public final class ModelImages {
  public static final String OPENAI = "openai-chat-images-v1";
  public static final String DEEPSEEK = "deepseek-nonthinking-images-v1";

  private ModelImages() {}

  public static boolean enabled(ModelSnapshot p) {
    try {
      require(p);
      return true;
    } catch (TaskFailure unsupported) {
      return false;
    }
  }

  public static void require(ModelSnapshot p) {
    var c = p.imageInput();
    if (p.version() != 3
        || !List.of(OPENAI, DEEPSEEK).contains(p.adapter())
        || c == null
        || c.version() != 1
        || !c.enabled()
        || !List.of("image/png").equals(c.mediaTypes())
        || c.maxImages() < 1
        || c.maxImages() > 4
        || c.maxImageBytes() < 1
        || c.maxImageBytes() > 1048576
        || c.maxTotalImageBytes() < c.maxImageBytes()
        || c.maxTotalImageBytes() > 4194304
        || c.maxRequestBytes() < 1
        || c.maxRequestBytes() > 8388608
        || c.evidence() == null
        || c.evidence().isBlank()) throw new TaskFailure(503, "MODEL_CAPABILITY_UNSUPPORTED");
  }

  public static void body(ModelSnapshot p, String body) {
    require(p);
    if (body.getBytes(StandardCharsets.UTF_8).length > p.imageInput().maxRequestBytes())
      throw new TaskFailure(409, "MODEL_MEDIA_LIMIT_EXCEEDED");
    // No multimodal billing proof is registered. UTF-8 text proofs cannot authorize paid vision.
    if (p.paid()) throw new TaskFailure(503, "MODEL_INPUT_BOUND_UNAVAILABLE");
  }

  public static boolean deepseek(String adapter) {
    return BusinessPolicies.STRATEGY.equals(adapter) || DEEPSEEK.equals(adapter);
  }
}
