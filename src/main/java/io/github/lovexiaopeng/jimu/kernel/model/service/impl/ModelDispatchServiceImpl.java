package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.model.service.ModelDispatchService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LeaseUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 模型准入和实际用量结算；网络调用在事务外，持久拒绝提交后再向调用者抛错。 */
public final class ModelDispatchServiceImpl implements ModelDispatchService {
  private final ModelDispatchRecords records;
  private final LedgerUnitOfWork work;
  private final ModelWorkflowRecords workflows;
  private final LeaseUnitOfWork admissions;
  private final SnapshotDigest digest;

  public ModelDispatchServiceImpl(
      ModelDispatchRecords records,
      LedgerUnitOfWork work,
      ModelWorkflowRecords workflows,
      LeaseUnitOfWork admissions,
      SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.workflows = workflows;
    this.admissions = admissions;
    this.digest = digest;
  }

  @Override
  public ModelSnapshot snapshot(Lease lease) {
    return admissions.admission(
        lease,
        () -> {
          var access = workflows.lockModelSnapshot(lease);
          var snapshot = access.model();
          access.checkEvaluation(snapshot);
          return snapshot;
        });
  }

  @Override
  public ToolSnapshot tools(Lease lease) {
    return admissions.admission(lease, () -> workflows.lockToolSnapshot(lease));
  }

  @Override
  public void failed(Lease lease, String dispatch, String code, ModelUsage usage) {
    work.required(
        () -> {
          var row = records.lockSettlement(lease, dispatch);
          var model = ModelCostSettlement.prove(row.costs());
          if (usage != null) ModelCostSettlement.settle(row.costs(), usage);
          else if (model.paid())
            ModelCostSettlement.settle(
                row.costs(), new ModelUsage(null, null, null, null, null, null, null, null));
          row.markFailed(code);
          Long tokens = usage == null ? null : usage.totalTokens();
          long reserved = row.reservedTokens();
          if (row.session() && tokens != null && tokens <= reserved)
            row.settleTokens(tokens, reserved - tokens);
          else row.holdUnknown();
          if (row.session()) row.accountSession();
          return null;
        });
  }

  @Override
  public String dispatch(Lease lease, String step) {
    return admissions.admission(
        lease,
        () -> {
          var row = workflows.lockAdmission(lease, step);
          row.validateStep();
          if (row.responded()) throw new TaskFailure(409, "ALREADY_RESPONDED");
          row.validateDirectory();
          var request = row.request();
          String bodyDigest = digest.hash(request.body());
          if (!bodyDigest.equals(request.bodyDigest()))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          if (!request.actualConfigDigest().equals(request.configDigest()))
            throw new TaskFailure(409, "MODEL_CONFIG_UNAVAILABLE");
          row.validateEvaluationAndMaterial();
          row.validateInput(request.body());
          var b = row.limits();
          long upper =
              b.paid()
                  ? b.inputUpper() + b.maxOutput()
                  : request.snapshot().getBytes(StandardCharsets.UTF_8).length + 4096L;
          long cost = b.reservedCost();
          if (b.paid()
              && (!b.runPresent()
                  || !"OPEN".equals(b.runState())
                  || b.requests() >= b.maxRequests()
                  || cost > b.runRoom()
                  || cost > b.taskCostRoom()
                  || cost > b.tenantCostRoom()))
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          if (b.dispatches() >= (b.paid() ? 1 : 2)
              || b.modelsUsed() >= b.maxModels()
              || b.tenantModels() >= b.tenantModelLimit()
              || b.tenantTokens() + upper > b.upperTenantTokens()
              || b.taskTokens() + upper > b.maxTokens())
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          var plan = row.planAllowance();
          ModelEvaluationAdmission.check(plan, cost);
          if (plan != null && row.activePlanDispatch())
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          row.reserveModuleStorage();
          String dispatch = UUID.randomUUID().toString();
          if (b.paid()) row.reserveCost(dispatch, cost);
          row.insertDispatch(dispatch, bodyDigest, cost);
          row.reserveTokens(dispatch, upper);
          row.markRunning(dispatch);
          row.dispatched(dispatch);
          row.commitFence();
          return dispatch;
        });
  }

  @Override
  public void responded(
      Lease lease,
      String step,
      String dispatch,
      String response,
      List<Call> calls,
      Integer tokens,
      ModelUsage usage) {
    work.required(
        () -> {
          var row = workflows.lockResponse(lease, step, dispatch);
          if (!row.originalDispatch()) throw new TaskFailure(409, "DISPATCH_REJECTED");
          if (ModelCostSettlement.settle(row.costs(), usage)) {
            String code = row.costRejection();
            row.rejectStep();
            row.rejected(code);
            return null;
          }
          if (row.constrained())
            try {
              row.validatePayload(response, calls);
            } catch (TaskFailure rejected) {
              settle(row, tokens, row.rejectedPayloadUpper());
              row.rejectStep();
              row.recordRejection(rejected.code());
              row.rejected(rejected.code());
              return null;
            }
          if (row.question()
              && (response.getBytes(StandardCharsets.UTF_8).length > 32768 || calls.size() > 8)) {
            row.rejectOversizedQuestion();
            return null;
          }
          row.reserveQuestionStorage(response.getBytes(StandardCharsets.UTF_8).length * 2L);
          row.storeResponse(response);
          settle(row, tokens, row.reservedTokens());
          try {
            String rejection = row.modelRejection();
            if (rejection == null) rejection = row.runRejection();
            if (rejection != null) throw new TaskFailure(409, rejection);
            row.requireScope();
            for (var call : calls)
              if (call.input().getBytes(StandardCharsets.UTF_8).length > 65535)
                throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          } catch (TaskFailure rejected) {
            row.rejectStep();
            row.recordRejection(rejected.code());
            row.rejected(rejected.code());
            return null;
          }
          int ordinal = row.invocationCount();
          long revision = row.todoRevision();
          for (var call : calls) {
            if (!lease.capabilities().contains(call.tool()))
              throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
            row.insertCall(call, ++ordinal, revision++);
          }
          row.bindCalls();
          row.responded();
          return null;
        });
    // 拒绝载荷仍须保留已发生的实际用量与审计，不能用抛错回滚抹去结算。
    var outcome = workflows.outcome(lease, dispatch);
    if (List.of("BOUND_EXCEEDED", "USAGE_UNKNOWN").contains(outcome.state())
        || outcome.code() != null) throw new TaskFailure(409, outcome.code());
  }

  private void settle(ModelWorkflowRecords.Response row, Integer tokens, long upper) {
    if (tokens != null && tokens >= 0 && tokens <= upper) row.settleTokens(tokens, upper - tokens);
    else row.holdUnknown();
  }
}
