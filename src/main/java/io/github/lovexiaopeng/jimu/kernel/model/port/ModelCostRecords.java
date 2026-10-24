package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;

/** 同一模型事务内的历史价格凭证与具名账目写入；结算规则由 Service 决定。 */
public interface ModelCostRecords {
  record Proof(
      ModelSnapshot model,
      String taskDigest,
      String dispatchDigest,
      String canonicalDigest,
      String priceVersion,
      String currency,
      long reservedCost) {}

  String state();

  Proof proof();

  void invalidateProof();

  void accountUsage(ModelUsage usage);

  long reservedCost();

  void adjustCost(long delta, boolean stopRun);

  void settleActualCost(long actual);

  void holdCostUnknown();

  void recordUsage(ModelUsage usage, String state, String code, Long actual, String quality);

  void stopFurtherDispatch();
}
