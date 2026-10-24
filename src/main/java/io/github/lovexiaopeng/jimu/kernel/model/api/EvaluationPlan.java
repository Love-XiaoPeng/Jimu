package io.github.lovexiaopeng.jimu.kernel.model.api;

import java.util.List;
import java.util.Map;

/** 关闭评估计划及其冻结绑定值；字段与既有快照保持一致，不授予执行权限。 */
public final class EvaluationPlan {
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

  public static long taskCost(Plan plan, String model, String sample) {
    var rate = plan.candidates().get(model);
    long cost =
        (plan.contextTokens() * rate.inputMicrosPerMillion()
                + plan.maxOutputTokens() * rate.outputMicrosPerMillion()
                + 999999)
            / 1000000;
    return cost * (sample.equals("W1") ? 7 : 6);
  }

  private EvaluationPlan() {}
}
