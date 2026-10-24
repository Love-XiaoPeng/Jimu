package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationPlansTest {
  private final io.github.lovexiaopeng.jimu.kernel.model.service.EvaluationBindingService bindings =
      new io.github.lovexiaopeng.jimu.kernel.model.service.impl.EvaluationBindingServiceImpl(
          new io.github.lovexiaopeng.jimu.adapters.runtime.model.RuntimeEvaluationPlanCodec());

  @Test
  void exactCapabilityAndPricesCannotBeReplacedByAnUnprovenSmallContext() {
    var plan = EvaluationPlans.plan("qwen-main-v1");
    String model = "qwen3.8-max-0902";
    var snapshot =
        new ModelSnapshot(
            2,
            "closed",
            1,
            "openai-chat-v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model,
            model,
            "none",
            null,
            0,
            "cloud-https-v1",
            2000,
            20000,
            1048576,
            false,
            4096,
            "max_completion_tokens",
            10,
            new ModelSnapshot.Capabilities(
                true, true, true, true, true, true, false, 1000000, "modelstudio-nonthinking-v1"),
            Map.of("enable_thinking", false, "enable_search", false),
            true,
            new ModelSnapshot.Price(
                "reviewed-20261016",
                "CNY",
                12000000,
                36000000,
                0,
                1000000,
                "reviewed-full-context"),
            null);
    assertDoesNotThrow(() -> EvaluationPlans.validateModel(plan, snapshot, false));
    String original = Payloads.json(snapshot);
    for (var edit :
        List.of(
            new String[] {"4096", "4097"},
            new String[] {"1000000", "8192"},
            new String[] {"12000000", "6000000"},
            new String[] {"max_completion_tokens", "max_tokens"},
            new String[] {"\"outputMarginTokens\":10", "\"outputMarginTokens\":0"},
            new String[] {"dashscope.aliyuncs.com", "another.example"},
            new String[] {"qwen3.8-max-0902", "deepseek-v4-pro-0813"},
            new String[] {"\"enable_search\":false", "\"enable_search\":true"})) {
      var invalid = Payloads.read(original.replace(edit[0], edit[1]), ModelSnapshot.class);
      assertThrows(
          IllegalArgumentException.class,
          () -> EvaluationPlans.validateModel(plan, invalid, false));
    }
  }

  @Test
  void bothClosedMatricesReserveEachDispatchBeforeMultiplication() {
    for (String id : List.of("qwen-main-v1", "qwen-pro-optional-v1")) {
      var p = EvaluationPlans.plan(id);
      assertFalse(p.enabled());
      assertFalse(p.paidDispatchAuthorized());
      assertEquals(p.maxRequests() * 1004096L, p.tenantTokenLimit());
      assertEquals(7028672, p.taskTokenLimit());
      var costs =
          p.candidates().values().stream()
              .map(
                  rate ->
                      ModelCosts.amount(
                          new ModelSnapshot.Price(
                              "proof",
                              "CNY",
                              rate.inputMicrosPerMillion(),
                              rate.outputMicrosPerMillion(),
                              0,
                              1000000,
                              "proof"),
                          1000000,
                          4096))
              .sorted()
              .toList();
      long a = costs.stream().mapToLong(c -> 13 * c).sum();
      long b = 12 * (costs.getLast() + costs.get(costs.size() - 2));
      assertEquals(id.equals("qwen-main-v1") ? 471999593 : 626584745, a + b);
    }
    assertThrows(IllegalArgumentException.class, () -> EvaluationPlans.plan("unbounded"));
    assertThrows(
        IllegalArgumentException.class, () -> EvaluationPlans.selection("qwen-main-v1", "A", "W4"));
  }

  @Test
  void qualityUnlockRejectsBelowThresholdHardFailuresAndThirdFinalist() {
    var p = EvaluationPlans.plan("qwen-main-v1");
    var score = new EvaluationPlan.Score(16, 27, 27, 15, false);
    var grades = new HashMap<String, EvaluationPlan.Assessment>();
    for (String model : p.candidates().keySet())
      grades.put(
          model, new EvaluationPlan.Assessment(true, true, 18, score, score, "a".repeat(64)));
    var finalists = p.candidates().keySet().stream().sorted().limit(2).toList();
    var q =
        new EvaluationPlan.Qualification(
            EvaluationPlans.digest(p),
            p.materialsDigest(),
            grades,
            finalists,
            true,
            "c".repeat(40),
            "d".repeat(64),
            "synthetic-reviewer",
            "2026-10-16T00:00:00Z",
            "test-run");
    String json = Payloads.json(q);
    assertEquals(q, bindings.qualify(p, json, Payloads.hash(json)));
    assertThrows(TaskFailure.class, () -> bindings.qualify(p, json, "0".repeat(64)));
    for (var bad :
        List.of(
            new EvaluationPlan.Score(15, 27, 27, 15, false),
            new EvaluationPlan.Score(20, 26, 30, 20, false),
            new EvaluationPlan.Score(20, 30, 26, 20, false),
            new EvaluationPlan.Score(20, 30, 30, 20, true))) {
      grades.put(
          finalists.getFirst(),
          new EvaluationPlan.Assessment(true, true, 18, bad, score, "a".repeat(64)));
      String invalid =
          Payloads.json(
              new EvaluationPlan.Qualification(
                  q.planDigest(),
                  q.materialsDigest(),
                  grades,
                  finalists,
                  true,
                  "c".repeat(40),
                  "d".repeat(64),
                  "synthetic-reviewer",
                  "2026-10-16T00:00:00Z",
                  "test-run"));
      assertThrows(TaskFailure.class, () -> bindings.qualify(p, invalid, Payloads.hash(invalid)));
    }
    String third =
        Payloads.json(
            new EvaluationPlan.Qualification(
                q.planDigest(),
                q.materialsDigest(),
                grades,
                p.candidates().keySet().stream().toList(),
                true,
                "c".repeat(40),
                "d".repeat(64),
                "synthetic-reviewer",
                "2026-10-16T00:00:00Z",
                "test-run"));
    assertThrows(TaskFailure.class, () -> bindings.qualify(p, third, Payloads.hash(third)));
  }
}
