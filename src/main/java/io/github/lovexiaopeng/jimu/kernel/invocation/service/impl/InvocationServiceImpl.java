package io.github.lovexiaopeng.jimu.kernel.invocation.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactErrorCode;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.service.InvocationService;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.LeaseUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.McpErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;

/** 原槽效果准入和一次结算；未知效果不重放，已确认成功不因材料后验拒绝而消失。 */
public final class InvocationServiceImpl implements InvocationService {
  private final InvocationRecords records;
  private final LedgerUnitOfWork work;
  private final InvocationWorkflowRecords workflows;
  private final LeaseUnitOfWork admissions;
  private final SnapshotDigest digest;

  public InvocationServiceImpl(
      InvocationRecords records,
      LedgerUnitOfWork work,
      InvocationWorkflowRecords workflows,
      LeaseUnitOfWork admissions,
      SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.workflows = workflows;
    this.admissions = admissions;
    this.digest = digest;
  }

  @Override
  public void failed(Lease lease, String invocation, String code) {
    work.required(
        () -> {
          var row = records.lockSettlement(lease, invocation);
          if (row.session()) {
            requireOrdinaryCommit(lease, invocation);
            throw new TaskFailure(409, SessionErrorCode.SESSION_READ_COMMIT_REQUIRED);
          }
          if (row.failRunning() != 1)
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          row.settleUnit();
          row.failureEvent(code);
          return null;
        });
  }

  @Override
  public void uncertain(Lease lease, String invocation) {
    requireOrdinaryCommit(lease, invocation);
    work.required(
        () -> {
          var row = records.lockSettlement(lease, invocation);
          row.markUnknown();
          row.holdUnknown();
          row.reconciliationRequired();
          return null;
        });
  }

  @Override
  public Invocation admit(
      Lease lease, String step, Call call, String pluginVersion, String pluginDigest) {
    if (call.tool().equals(ToolCapability.SESSION_SEARCH)
        || lease.capabilities().contains(ToolCapability.SESSION_SEARCH)
            && call.tool().equals(ToolCapability.SESSION_EVENT_READ))
      throw new TaskFailure(409, SessionErrorCode.SESSION_SCOPE_COMMIT_REQUIRED);
    if (call.tool().equals(ToolCapability.SESSION_EVENT_TRACE))
      throw new TaskFailure(409, SessionErrorCode.SESSION_TRACE_COMMIT_REQUIRED);
    if (call.tool().equals(ToolCapability.SESSION_EVENT_SEARCH))
      throw new TaskFailure(409, SessionErrorCode.SESSION_SEARCH_COMMIT_REQUIRED);
    if (call.tool().equals(ToolCapability.SESSION_EVENT_READ))
      throw new TaskFailure(409, SessionErrorCode.SESSION_READ_COMMIT_REQUIRED);
    if (call.tool().equals(ToolCapability.WORKING_DIRECTORY))
      throw new TaskFailure(409, DirectoryErrorCode.DIRECTORY_COMMIT_REQUIRED);
    if (call.tool().equals(ToolCapability.TODO_WRITE))
      throw new TaskFailure(409, TodoErrorCode.TODO_COMMIT_REQUIRED);

    return admissions.admission(
        lease,
        () -> {
          var row = workflows.lockAdmission(lease, step, call, pluginVersion, pluginDigest);
          var slot = row.slot();
          if (!digest.hash(call.input()).equals(slot.inputDigest())
              || !call.tool().equals(slot.tool())
              || !call.providerId().equals(slot.provider()))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
          row.freezeDirectory();
          validateMaterial(row, call);
          slot = row.slot();
          var pin = row.pin();
          if (!pluginVersion.equals(pin.version())
              || !pluginDigest.equals(pin.digest())
              || !pluginVersion.equals(pin.pinnedVersion())
              || !pluginDigest.equals(pin.pinnedDigest())
              || pin.epoch() != pin.pinnedEpoch()
              || !pluginVersion.equals(pin.installedVersion())
              || !pluginDigest.equals(pin.installedDigest()))
            throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
          if (InvocationState.SUCCEEDED.equals(slot.state())) {
            if (!digest.hash(slot.output()).equals(slot.outputDigest()))
              throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
            return new Invocation(
                slot.id(), slot.effect(), InvocationState.SUCCEEDED, slot.output());
          }
          if (!InvocationState.PENDING.equals(slot.state()))
            throw new TaskFailure(409, TaskErrorCode.EFFECT_OUTCOME_UNKNOWN);
          if (row.toolsUsed() >= row.maxTools() || row.tenantTools() >= row.tenantToolLimit())
            throw new TaskFailure(429, ToolErrorCode.TOOL_BUDGET_EXCEEDED);
          row.reserveUnit();
          row.markRunning();
          row.dispatched();
          row.commitFence();
          return new Invocation(slot.id(), slot.effect(), InvocationState.RUNNING, null);
        });
  }

  @Override
  public void succeeded(Lease lease, String invocation, String output) {
    requireOrdinaryCommit(lease, invocation);
    commit(lease, invocation, output, false);
  }

  private void requireOrdinaryCommit(Lease lease, String invocation) {
    if (lease.capabilities().contains(ToolCapability.SESSION_SEARCH))
      throw new TaskFailure(409, SessionErrorCode.SESSION_SCOPE_COMMIT_REQUIRED);
    // 保留原五次惰性查询和拒绝优先级；不以一次预加载改变计量或错误次序。
    if (records.protectedInvocation(lease, invocation, ToolCapability.SESSION_EVENT_TRACE))
      throw new TaskFailure(409, SessionErrorCode.SESSION_TRACE_COMMIT_REQUIRED);
    if (records.protectedInvocation(lease, invocation, ToolCapability.SESSION_EVENT_SEARCH))
      throw new TaskFailure(409, SessionErrorCode.SESSION_SEARCH_COMMIT_REQUIRED);
    if (records.protectedInvocation(lease, invocation, ToolCapability.SESSION_EVENT_READ))
      throw new TaskFailure(409, SessionErrorCode.SESSION_READ_COMMIT_REQUIRED);
    if (records.protectedInvocation(lease, invocation, ToolCapability.WORKING_DIRECTORY))
      throw new TaskFailure(409, DirectoryErrorCode.DIRECTORY_COMMIT_REQUIRED);
    if (records.protectedInvocation(lease, invocation, ToolCapability.TODO_WRITE))
      throw new TaskFailure(409, TodoErrorCode.TODO_COMMIT_REQUIRED);
  }

  @Override
  public void commit(Lease lease, String invocation, String output, boolean mcpCommit) {
    boolean rejected =
        work.required(
            () ->
                admissions.directory(
                    lease,
                    () -> {
                      var row = workflows.lockSuccess(lease, invocation, output);
                      if (McpLedger.NAMES.contains(row.tool()) && !mcpCommit)
                        throw new TaskFailure(409, McpErrorCode.MCP_COMMIT_REQUIRED);
                      if (ToolCapability.PRESENT.equals(row.tool()))
                        throw new TaskFailure(409, ArtifactErrorCode.PRESENT_COMMIT_REQUIRED);
                      validateResultProof(row, output);
                      if (row.succeed() != 1)
                        throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
                      row.settleUnit();
                      row.succeeded();
                      row.commitFence();
                      try {
                        row.validateMaterial();
                        return false;
                      } catch (TaskFailure failure) {
                        if (!failure.code().equals(ModelErrorCode.EVALUATION_MATERIAL_MISMATCH))
                          throw failure;
                        row.stopEvaluationRun();
                        return true;
                      }
                    }));
    // 成功、计账和STOPPED已提交，随后通知调用者材料不再匹配。
    if (rejected) throw new TaskFailure(409, ModelErrorCode.EVALUATION_MATERIAL_MISMATCH);
  }

  private static void validateMaterial(InvocationWorkflowRecords.Admission row, Call call) {
    var use = row.materialUse();
    if (use == null) return;
    if (call.tool().equals(ToolCapability.WEB_FETCH))
      requireMaterial(use.sources().contains(use.url()));
    if (call.tool().equals(ToolCapability.WRITE)) requireMaterial(row.hasSuccessfulRead());
    if (call.tool().equals(ToolCapability.READ) || call.tool().equals(ToolCapability.WRITE))
      requireMaterial(use.target().equals(use.filePath()));
    if (call.tool().equals(ToolCapability.PRESENT))
      requireMaterial(use.fileCount() == 1 && use.target().equals(use.presentedPath()));
  }

  private static void requireMaterial(boolean matches) {
    if (!matches) throw new TaskFailure(409, ModelErrorCode.EVALUATION_MATERIAL_MISMATCH);
  }

  private void validateResultProof(InvocationWorkflowRecords.Success row, String output) {
    var image = row.imageProof();
    if (image != null
        && (image.committedIds().size() != 1
            || !image.committedIds().getFirst().equals(image.outputId())))
      throw new TaskFailure(409, ImageErrorCode.IMAGE_COMMIT_REQUIRED);
    var web = row.webProof();
    if (web != null
        && (!output.equals(web.storedOutput())
            || !digest.hash(output).equals(web.storedDigest())
            || !row.webProofDigest().equals(web.receipt())))
      throw new TaskFailure(409, TaskErrorCode.WEB_RESULT_PROOF_INVALID);
  }
}
