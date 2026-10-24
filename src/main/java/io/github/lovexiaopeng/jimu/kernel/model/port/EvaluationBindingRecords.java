package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Selection;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;

/** 已锁定 run 的事实访问；沿调用者同 DS/UoW 与显式查询上下文，按需读取不提前加锁。 */
public interface EvaluationBindingRecords {
  record Run(
      String id,
      String snapshot,
      String digest,
      long policyVersion,
      long maxRequests,
      long maxCost,
      long requestsUsed,
      long costHeld,
      String state,
      String qualificationSnapshot,
      String qualificationDigest) {}

  record Room(long cost, long tokens, long models) {}

  record Screening(String model, String sample, String state) {}

  Run run();

  Selection selection();

  void validateModel(Plan plan, ModelSnapshot model);

  boolean conflictingBinding();

  boolean hasTask();

  boolean hasUnknown();

  List<Screening> screening();

  boolean hasPhaseBTask();

  Room lockTenantRoom();
}
