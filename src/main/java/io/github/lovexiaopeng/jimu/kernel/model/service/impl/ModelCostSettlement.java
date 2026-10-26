package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.CostQuality;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ModelCost;
import io.github.lovexiaopeng.jimu.kernel.model.api.DispatchState;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.math.BigInteger;

/** 历史价格绑定、UNKNOWN 与越界实际成本结算；不调用 provider，不重放效果。 */
final class ModelCostSettlement {
  private ModelCostSettlement() {}

  static ModelSnapshot prove(ModelCostRecords row) {
    if (!DispatchState.DISPATCHED.equals(row.state()))
      throw new TaskFailure(409, ModelErrorCode.DISPATCH_REJECTED);
    try {
      var proof = row.proof();
      var model = proof.model();
      if (!proof.taskDigest().equals(proof.dispatchDigest())
          || !proof.canonicalDigest().equals(proof.dispatchDigest())
          || model.paid()
              && (model.price() == null
                  || !model.price().version().equals(proof.priceVersion())
                  || !model.price().currency().equals(proof.currency())
                  || ModelCost.amount(
                          model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                      != proof.reservedCost()))
        throw new IllegalArgumentException("Historical price binding mismatch");
      return model;
    } catch (TaskFailure | IllegalArgumentException corrupt) {
      // 原有特殊拒绝提交边界保留：先持久化 UNKNOWN/停止 run，再交 UoW 提交拒绝。
      row.invalidateProof();
      throw new TaskFailure(409, ModelErrorCode.MODEL_SETTLEMENT_PROOF_INVALID);
    }
  }

  static boolean settle(ModelCostRecords row, ModelUsage usage) {
    var model = prove(row);
    row.accountUsage(usage);
    Long actual = null;
    boolean exceeded = false;
    boolean unknown = model.paid() && (usage.inputTokens() == null || usage.outputTokens() == null);
    if (model.paid()) {
      exceeded =
          usage.inputTokens() != null && usage.inputTokens() > model.price().inputTokenUpper()
              || usage.outputTokens() != null && usage.outputTokens() > model.maxOutputTokens()
              || usage.totalTokens() != null
                  && BigInteger.valueOf(usage.totalTokens())
                          .compareTo(
                              BigInteger.valueOf(model.price().inputTokenUpper())
                                  .add(BigInteger.valueOf(model.maxOutputTokens())))
                      > 0;
      if (usage.inputTokens() != null && usage.outputTokens() != null) {
        try {
          actual = ModelCost.amount(model.price(), usage.inputTokens(), usage.outputTokens());
        } catch (ArithmeticException invalidProviderCost) {
          exceeded = true;
        }
      }
      long reserved = row.reservedCost();
      long delta = actual == null ? 0 : actual - reserved;
      exceeded = exceeded || actual != null && actual > reserved;
      row.adjustCost(delta, exceeded || unknown);
      if (actual != null) row.settleActualCost(actual);
      else row.holdCostUnknown();
    }
    row.recordUsage(
        usage,
        exceeded
            ? DispatchState.BOUND_EXCEEDED
            : unknown ? DispatchState.USAGE_UNKNOWN : DispatchState.RESPONDED,
        exceeded
            ? ModelErrorCode.MODEL_COST_BOUND_EXCEEDED
            : unknown ? ModelErrorCode.MODEL_USAGE_UNKNOWN : null,
        actual,
        actual == null ? CostQuality.UNKNOWN : CostQuality.KNOWN);
    if (exceeded) row.stopFurtherDispatch();
    return exceeded || unknown;
  }
}
