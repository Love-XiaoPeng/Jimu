package io.github.lovexiaopeng.jimu.kernel.operator.service.impl;

import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Requirement;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Scope;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingCodec;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingRecords;
import io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** 维持run→budget锁序、资格先于幂等、首次binder/时间及账本原子性。 */
public final class ExecutionScopeServiceImpl implements ExecutionScopeService {
  private final ScopeBindingRecords records;
  private final ScopeBindingCodec codec;
  private final LedgerUnitOfWork transactions;
  private final java.util.function.Supplier<Requirement> requirement;

  public ExecutionScopeServiceImpl(
      ScopeBindingRecords records,
      ScopeBindingCodec codec,
      LedgerUnitOfWork transactions,
      Requirement requirement) {
    this.records = records;
    this.codec = codec;
    this.transactions = transactions;
    this.requirement = () -> requirement;
  }

  public ExecutionScopeServiceImpl(
      ScopeBindingRecords records,
      ScopeBindingCodec codec,
      LedgerUnitOfWork transactions,
      java.util.function.Supplier<Requirement> requirement) {
    this.records = records;
    this.codec = codec;
    this.transactions = transactions;
    this.requirement = requirement;
  }

  private static void reject(String code) {
    throw new TaskFailure(code.equals("EVALUATION_SCOPE_UNAVAILABLE") ? 503 : 409, code);
  }

  public Map<String, Object> bindOnce(Input input) {
    if (input == null || input.profiles() == null) reject("EVALUATION_SCOPE_NOT_ALLOWED");
    var scope = input.scope();
    String snapshot = codec.canonical(scope), digest = codec.hash(snapshot);
    if (input.boundBy() == null || input.boundBy().isBlank() || input.boundBy().length() > 160)
      reject("EVALUATION_SCOPE_NOT_ALLOWED");
    return transactions.required(
        () -> {
          var run = records.lockRun(scope.tenantId(), scope.runId());
          if (run == null) reject("EVALUATION_SCOPE_NOT_ALLOWED");
          verify(input, run);
          if (!"OPEN".equals(run.state())) reject("MODEL_BUDGET_EXCEEDED");
          var existing = records.binding(scope.tenantId(), scope.runId());
          if (existing != null) {
            if (!snapshot.equals(existing.snapshot()) || !digest.equals(existing.digest()))
              reject("EVALUATION_SCOPE_BINDING_CONFLICT");
            return readback(existing, scope);
          }
          var budget = records.lockBudget(scope.tenantId());
          if (budget == null
              || !"OPEN".equals(run.state())
              || run.maxRequests() > 63
              || run.maxCost() > 500000000
              || run.requestsUsed() != records.dispatchCount(scope.tenantId(), scope.runId()))
            reject("EVALUATION_SCOPE_NOT_ALLOWED");
          if (records.accountedCost(scope.tenantId(), scope.runId()) != run.costHeld()
              || records.invalidTasks(scope.tenantId(), scope.runId()) != 0
              || budget.costHeld() != records.tenantCost(scope.tenantId())
              || budget.modelUsed() != records.tenantModels(scope.tenantId())
              || records.invalidReservations(scope.tenantId(), scope.runId()) != 0)
            reject("EVALUATION_SCOPE_LEDGER_UNPROVEN");
          records.insert(scope.tenantId(), scope.runId(), snapshot, digest, input.boundBy());
          return readback(records.binding(scope.tenantId(), scope.runId()), scope);
        });
  }

  private ScopeBindingRecords.Receipt receipt(String tenant) {
    var rows = records.receipts(tenant);
    if (rows.size() > 1) reject("EVALUATION_SCOPE_UNAVAILABLE");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void verify(Input input, ScopeBindingRecords.Run run) {
    var s = input.scope();
    var r = receipt(s.tenantId());
    var req = requirement.get();
    if (r == null
        || req == null
        || req.version() != 1
        || !s.tenantId().equals(req.tenantId())
        || req.issueId() == null
        || req.runId() == null
        || req.schema() == null
        || req.instanceUuid() == null
        || !codec.digest(req.approvalDigest())) reject("EVALUATION_SCOPE_UNAVAILABLE");
    String approval = r.approvalSnapshot();
    if (approval == null
        || r.version() != null && r.version() != 1
        || !codec.hash(approval).equals(r.approvalDigest())
        || !req.issueId().equals(r.issueId())
        || !req.runId().equals(r.runId())
        || !req.approvalDigest().equals(r.approvalDigest())
        || !req.instanceUuid().equals(r.instanceUuid())
        || !req.instanceUuid().equals(records.serverUuid())
        || !req.schema().equals(records.schema())) reject("EVALUATION_SCOPE_NOT_ALLOWED");
    if (!"qwen3.7-plus-2026-05-26".equals(codec.approvedCandidate(approval)))
      reject("EVALUATION_SCOPE_NOT_ALLOWED");
    // Preserve the original second read in the same transaction, including receipt cardinality.
    r = receipt(s.tenantId());
    var e = input.accountEvidence();
    if (req.version() != s.version()
        || !req.issueId().equals(s.issueId())
        || !req.instanceUuid().equals(s.instanceUuid())
        || !req.schema().equals(s.schema())
        || !req.tenantId().equals(s.tenantId())
        || !req.runId().equals(s.runId())
        || !req.approvalDigest().equals(s.approvalDigest())
        || !s.planDigest().equals(run.planDigest())
        || !s.planDigest().equals(r.planDigest())
        || !s.materialsDigest().equals(r.materialsDigest())
        || !s.profilePins().keySet().equals(input.profiles().keySet())
        || e == null
        || !codec.digest(e.evidenceDigest())
        || !s.accountEvidenceDigest().equals(codec.hash(codec.json(e)))
        || e.region() == null
        || e.region().isBlank()
        || e.billingScope() == null
        || e.billingScope().isBlank()
        || !e.exactModelAuthorized()
        || !e.egressVerified()) reject("EVALUATION_SCOPE_NOT_ALLOWED");
    var plan = codec.plan();
    if (!s.planDigest().equals(plan.digest())
        || !s.sourcePlanSha256().equals(plan.sourceSha())
        || !s.materialsDigest().equals(plan.materialsDigest())
        || !plan.snapshot().equals(run.planSnapshot())) reject("EVALUATION_SCOPE_NOT_ALLOWED");
    for (var entry : input.profiles().entrySet()) {
      var p = entry.getValue();
      if (p == null) reject("EVALUATION_SCOPE_NOT_ALLOWED");
      var pin = s.profilePins().get(entry.getKey());
      codec.validateProfile(p, s.localOnly());
      if (!s.allowedModel().equals(p.model())
          || !p.model().equals(p.revision())
          || !entry.getKey().equals(p.profile())
          || !pin.modelSnapshotDigest().equals(codec.profileDigest(p))
          || pin.configVersion() != p.configVersion()
          || pin.stream() != p.stream()
          || !URI.create(p.baseUrl()).getHost().equals(e.apiHost())
          || s.localOnly() != codec.localEvaluation(p)) reject("EVALUATION_SCOPE_NOT_ALLOWED");
    }
    if (!s.localOnly()
        && (!"cn-beijing".equals(e.region()) || !"modelstudio-payg-cny".equals(e.billingScope())))
      reject("EVALUATION_SCOPE_NOT_ALLOWED");
  }

  private Map<String, Object> readback(ScopeBindingRecords.Binding row, Scope scope) {
    String snapshot = codec.json(records.ledger(scope.tenantId(), scope.runId()));
    var result = new HashMap<String, Object>(row.readback());
    result.put("ledger_readback", snapshot);
    result.put("ledger_readback_digest", codec.hash(snapshot));
    return result;
  }
}
