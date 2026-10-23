package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Closed, versioned operator plans. These bounds never grant permission to dispatch. */
public final class EvaluationPlans {
  public record Rate(long inputMicrosPerMillion, long outputMicrosPerMillion) {}

  public record Plan(
      String id,
      int version,
      boolean enabled,
      boolean paidDispatchAuthorized,
      int maxRequests,
      long maxCostMicros,
      long phaseACostMicros,
      long phaseBCostMicros,
      int maxOutputTokens,
      int outputMarginTokens,
      long contextTokens,
      long tenantTokenLimit,
      long taskTokenLimit,
      String sourcePlanSha256,
      String materialsDigest,
      long matrixWorstCostMicros,
      Map<String, Rate> candidates) {
    public Plan {
      candidates = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(candidates));
    }
  }

  public record Selection(Plan plan, String phase, String sample) {}

  public record Binding(
      String planId,
      String planDigest,
      String phase,
      String sample,
      String model,
      String qualificationDigest,
      long runMaxRequests,
      long runMaxCostMicros) {}

  public record Score(int chinese, int tools, int sources, int report, boolean hardFailure) {
    public boolean passes() {
      return !hardFailure
          && chinese >= 0
          && chinese <= 20
          && tools >= 27
          && tools <= 30
          && sources >= 27
          && sources <= 30
          && report >= 0
          && report <= 20
          && chinese + tools + sources + report >= 85;
    }
  }

  /**
   * Grades are signed off through the existing trusted DB provisioning channel, never model JSON.
   */
  public record Assessment(
      boolean completed,
      boolean c1Passed,
      int c1Chinese,
      Score w1,
      Score w2,
      String evidenceSha256) {
    public boolean qualifies() {
      return completed
          && c1Passed
          && c1Chinese >= 18
          && c1Chinese <= 20
          && w1 != null
          && w1.passes()
          && w2 != null
          && w2.passes();
    }
  }

  public record Qualification(
      String planDigest,
      String materialsDigest,
      Map<String, Assessment> assessments,
      List<String> finalists,
      boolean adjudicated,
      String acceptedFinalCodeSha,
      String evidenceManifestDigest,
      String actor,
      String timestamp,
      String originalRunId) {}

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
    var rate = plan.candidates().get(model);
    long cost =
        (plan.contextTokens() * rate.inputMicrosPerMillion()
                + plan.maxOutputTokens() * rate.outputMicrosPerMillion()
                + 999999)
            / 1000000;
    return cost * (sample.equals("W1") ? 7 : 6);
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

  public static Qualification qualify(Plan plan, String json, String digest) {
    if (json == null || !Payloads.hash(json).equals(digest))
      throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
    var q = Payloads.read(json, Qualification.class);
    if (!digest(plan).equals(q.planDigest())
        || !plan.materialsDigest().equals(q.materialsDigest())
        || q.assessments() == null
        || !q.assessments().keySet().equals(plan.candidates().keySet())
        || q.finalists() == null
        || q.finalists().isEmpty()
        || q.finalists().size() > 2
        || q.finalists().stream().distinct().count() != q.finalists().size())
      throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
    if (!q.adjudicated()
        || q.acceptedFinalCodeSha() == null
        || !q.acceptedFinalCodeSha().matches("[0-9a-f]{40}")
        || q.evidenceManifestDigest() == null
        || !q.evidenceManifestDigest().matches("[0-9a-f]{64}")
        || q.actor() == null
        || q.actor().isBlank()
        || q.timestamp() == null
        || q.originalRunId() == null
        || q.originalRunId().isBlank()) throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
    java.time.Instant.parse(q.timestamp());
    for (var assessment : q.assessments().values())
      if (assessment == null
          || !assessment.completed()
          || assessment.evidenceSha256() == null
          || !assessment.evidenceSha256().matches("[0-9a-f]{64}"))
        throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
    for (String finalist : q.finalists())
      if (!q.assessments().containsKey(finalist) || !q.assessments().get(finalist).qualifies())
        throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
    return q;
  }
}
