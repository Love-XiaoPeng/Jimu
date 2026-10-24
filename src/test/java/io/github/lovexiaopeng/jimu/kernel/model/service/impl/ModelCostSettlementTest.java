package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.budget.api.ModelCost;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords.PlanAllowance;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords.PlanUsage;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 结算与评估规则的数值/持久拒绝契约；SQL 原子性另由真实 MySQL 验证。 */
class ModelCostSettlementTest {
  private static final class Row implements ModelCostRecords {
    final List<String> actions = new ArrayList<>();
    String digest = "same", state = "DISPATCHED", recordedState, recordedCode, quality;
    boolean stopped;
    long delta;
    Long actual;
    final ModelSnapshot.Price price =
        new ModelSnapshot.Price("p", "CNY", 1_000_000, 2_000_000, 100, 8192, "test");
    final ModelSnapshot model =
        new ModelSnapshot(
            2,
            "fixture",
            1,
            "adapter",
            "http://localhost",
            "model",
            "revision",
            "none",
            null,
            0,
            "local",
            100,
            100,
            100,
            false,
            512,
            "max",
            0,
            null,
            Map.of(),
            true,
            price,
            null);

    public String state() {
      return state;
    }

    public Proof proof() {
      return new Proof(model, "same", digest, "same", "p", "CNY", reservedCost());
    }

    public void invalidateProof() {
      actions.add("invalid");
    }

    public void accountUsage(ModelUsage usage) {
      actions.add("bytes");
    }

    public long reservedCost() {
      return ModelCost.amount(price, 8192, 512);
    }

    public void adjustCost(long value, boolean stop) {
      delta = value;
      stopped = stop;
      actions.add("adjust");
    }

    public void settleActualCost(long value) {
      actual = value;
      actions.add("settle");
    }

    public void holdCostUnknown() {
      actions.add("unknown");
    }

    public void recordUsage(ModelUsage usage, String next, String code, Long value, String q) {
      recordedState = next;
      recordedCode = code;
      quality = q;
      actual = value;
      actions.add("record");
    }

    public void stopFurtherDispatch() {
      actions.add("cap");
    }
  }

  @Test
  void knownUsageReleasesOnlyDifferenceAndRetainsExactActual() {
    var row = new Row();
    assertEquals(
        false,
        ModelCostSettlement.settle(
            row, new ModelUsage(100L, 50L, 150L, null, null, null, null, null)));
    assertEquals(300, row.actual.longValue());
    assertEquals(300 - row.reservedCost(), row.delta);
    assertEquals("RESPONDED", row.recordedState);
    assertNull(row.recordedCode);
    assertEquals("KNOWN", row.quality);
    assertEquals(List.of("bytes", "adjust", "settle", "record"), row.actions);
  }

  @Test
  void partialUsageStopsRunWithoutInventingActualOrRefund() {
    var row = new Row();
    assertTrue(
        ModelCostSettlement.settle(
            row, new ModelUsage(null, 1L, null, null, null, null, null, null)));
    assertNull(row.actual);
    assertEquals(0, row.delta);
    assertTrue(row.stopped);
    assertEquals("USAGE_UNKNOWN", row.recordedState);
    assertEquals("UNKNOWN", row.quality);
    assertEquals(List.of("bytes", "adjust", "unknown", "record"), row.actions);
  }

  @Test
  void overflowingCostRetainsRawUnknownAndBoundsWithoutClamping() {
    var row = new Row();
    assertTrue(
        ModelCostSettlement.settle(
            row,
            new ModelUsage(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, null, null, null, null, null)));
    assertNull(row.actual);
    assertEquals(0, row.delta);
    assertTrue(row.stopped);
    assertEquals("BOUND_EXCEEDED", row.recordedState);
    assertEquals("MODEL_COST_BOUND_EXCEEDED", row.recordedCode);
    assertEquals(List.of("bytes", "adjust", "unknown", "record", "cap"), row.actions);
  }

  @Test
  void invalidBindingCommitsIntegrityHoldButDuplicateStateDoesNotMutate() {
    var row = new Row();
    row.digest = "different";
    assertEquals(
        "MODEL_SETTLEMENT_PROOF_INVALID",
        assertThrows(TaskFailure.class, () -> ModelCostSettlement.prove(row)).code());
    assertEquals(List.of("invalid"), row.actions);
    row.actions.clear();
    row.state = "RESPONDED";
    assertEquals(
        "DISPATCH_REJECTED",
        assertThrows(TaskFailure.class, () -> ModelCostSettlement.prove(row)).code());
    assertTrue(row.actions.isEmpty());
  }

  @Test
  void planSeparatesPhaseModelAndSampleCapsAtOriginalBoundaries() {
    ModelEvaluationAdmission.check(null, 100);
    ModelEvaluationAdmission.check(
        new PlanAllowance("A", "W1", "m", 2, 10000, List.of(new PlanUsage("A", "W1", "m", 6, 600))),
        100);
    assertEquals(
        "MODEL_BUDGET_EXCEEDED",
        assertThrows(
                TaskFailure.class,
                () ->
                    ModelEvaluationAdmission.check(
                        new PlanAllowance(
                            "A",
                            "W1",
                            "m",
                            2,
                            10000,
                            List.of(new PlanUsage("A", "W1", "m", 7, 700))),
                        100))
            .code());
    assertThrows(
        TaskFailure.class,
        () ->
            ModelEvaluationAdmission.check(
                new PlanAllowance(
                    "B", "W3", "m", 2, 10000, List.of(new PlanUsage("B", "W3", "m", 6, 600))),
                100));
    assertThrows(
        TaskFailure.class,
        () ->
            ModelEvaluationAdmission.check(
                new PlanAllowance(
                    "A",
                    "W2",
                    "m",
                    2,
                    10000,
                    List.of(
                        new PlanUsage("B", "W3", "m", 20, 2000),
                        new PlanUsage("A", "W2", "m", 5, 500))),
                100));
  }
}
