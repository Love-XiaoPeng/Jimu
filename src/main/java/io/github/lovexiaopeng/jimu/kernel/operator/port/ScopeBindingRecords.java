package io.github.lovexiaopeng.jimu.kernel.operator.port;

import java.util.List;
import java.util.Map;

/** 同一短事务内的全键读写；账本快照保留原列名、类型、NULL与顺序。 */
public interface ScopeBindingRecords {
  record Run(
      String id,
      String planSnapshot,
      String planDigest,
      String state,
      long maxRequests,
      long maxCost,
      long requestsUsed,
      long costHeld) {}

  record Receipt(
      Integer version,
      String issueId,
      String instanceUuid,
      String runId,
      String approvalSnapshot,
      String approvalDigest,
      String planDigest,
      String materialsDigest) {}

  record Budget(long costHeld, long modelUsed) {}

  /** 原列名readback只用于原样输出；规则通过类型化字段读取。 */
  record Binding(String snapshot, String digest, Map<String, Object> readback) {}

  Run lockRun(String tenant, String run);

  List<Receipt> receipts(String tenant);

  String serverUuid();

  String schema();

  Binding binding(String tenant, String run);

  Budget lockBudget(String tenant);

  long dispatchCount(String tenant, String run);

  long accountedCost(String tenant, String run);

  long invalidTasks(String tenant, String run);

  long tenantCost(String tenant);

  long tenantModels(String tenant);

  long invalidReservations(String tenant, String run);

  void insert(String tenant, String run, String snapshot, String digest, String boundBy);

  Map<String, Object> ledger(String tenant, String run);
}
