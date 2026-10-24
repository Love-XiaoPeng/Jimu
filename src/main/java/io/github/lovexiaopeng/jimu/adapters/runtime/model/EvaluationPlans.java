package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Selection;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Closed, versioned operator plans. These bounds never grant permission to dispatch. */
public final class EvaluationPlans {
  private EvaluationPlans() {}

  public static Plan plan(String id) {
    if (!List.of("qwen-main-v1", "qwen-pro-optional-v1").contains(id))
      throw new IllegalArgumentException("Unknown closed evaluation plan");
    try (var stream = EvaluationPlans.class.getResourceAsStream("/evaluation/" + id + ".json")) {
      if (stream == null) throw new IllegalStateException("Evaluation plan unavailable");
      var plan =
          Payloads.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Plan.class);
      try (var source =
          EvaluationPlans.class.getResourceAsStream("/evaluation/" + id + ".source.json")) {
        if (source == null || !Payloads.hash(source.readAllBytes()).equals(plan.sourcePlanSha256()))
          throw new IllegalStateException("Frozen source plan mismatch");
      }
      return plan;
    } catch (IOException e) {
      throw new IllegalStateException("Evaluation plan unavailable", e);
    }
  }

  public static Selection selection(String id, String phase, String sample) {
    var plan = plan(id);
    if (!("A".equals(phase) && List.of("W1", "W2").contains(sample)
        || "B".equals(phase) && List.of("W3", "W4").contains(sample)))
      throw new IllegalArgumentException("Invalid evaluation stage/sample");
    return new Selection(plan, phase, sample);
  }

  public static long taskCost(Plan plan, String model, String sample) {
    return io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.taskCost(
        plan, model, sample);
  }

  public static String digest(Plan plan) {
    return Payloads.hash(Payloads.json(plan));
  }

  public static void validateModel(Plan plan, ModelSnapshot model, boolean testMode) {
    var rate = plan.candidates().get(model.model());
    var price = model.price();
    var c = model.capabilities();
    var uri = URI.create(model.baseUrl());
    boolean local =
        testMode
            && "trusted-local-v1".equals(model.transportPolicy())
            && List.of("127.0.0.1", "[::1]").contains(uri.getHost());
    if (rate == null
        || !model.paid()
        || price == null
        || c == null
        || !model.model().equals(model.revision())
        || !(local
            || "cloud-https-v1".equals(model.transportPolicy())
                && "https://dashscope.aliyuncs.com/compatible-mode/v1".equals(model.baseUrl()))
        || model.maxOutputTokens() != plan.maxOutputTokens()
        || model.outputMarginTokens() != plan.outputMarginTokens()
        || !"max_completion_tokens".equals(model.outputLimitField())
        || model.inputLimit() != null
        || c.contextLimit() != plan.contextTokens()
        || !c.call()
        || !c.tools()
        || !c.streamText()
        || !c.streamTools()
        || !c.usageCall()
        || !c.usageStream()
        || c.parallelToolField()
        || !"modelstudio-nonthinking-v1".equals(c.evidence())
        || !model.providerOptions().equals(Map.of("enable_thinking", false, "enable_search", false))
        || !"CNY".equals(price.currency())
        || price.fixedMicros() != 0
        || price.inputTokenUpper() != plan.contextTokens()
        || price.inputMicrosPerMillion() != rate.inputMicrosPerMillion()
        || price.outputMicrosPerMillion() != rate.outputMicrosPerMillion())
      throw new IllegalArgumentException(
          "Model does not match bounded evaluation capability/price");
  }
}
