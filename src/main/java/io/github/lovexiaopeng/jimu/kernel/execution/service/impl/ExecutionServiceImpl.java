package io.github.lovexiaopeng.jimu.kernel.execution.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactErrorCode;
import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import io.github.lovexiaopeng.jimu.kernel.execution.service.ExecutionService;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Step;
import io.github.lovexiaopeng.jimu.kernel.task.api.LeaseUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 租约内检查点、准备、结束用例；短事务和材料拒绝提交沿用原账本边界。 */
public final class ExecutionServiceImpl implements ExecutionService {
  private final ExecutionRecords records;
  private final LedgerUnitOfWork work;
  private final LeaseUnitOfWork admissions;
  private final SnapshotDigest digest;

  public ExecutionServiceImpl(
      ExecutionRecords records,
      LedgerUnitOfWork work,
      LeaseUnitOfWork admissions,
      SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.admissions = admissions;
    this.digest = digest;
  }

  @Override
  public void stop(Lease lease, String code) {
    work.required(
        () -> {
          var task = records.lockSettlement(lease);
          if (code.equals(ModelErrorCode.EVALUATION_MATERIAL_MISMATCH) && task.hasEvaluationRun())
            task.stopEvaluationRun();
          if (task.pendingEffect()
              || List.of(
                      TaskErrorCode.SNAPSHOT_CORRUPT, ModelErrorCode.MODEL_SETTLEMENT_PROOF_INVALID)
                  .contains(code)) task.terminal(TaskState.NEEDS_RECONCILIATION, code);
          else
            task.terminal(
                task.cancellationRequested() ? TaskState.CANCELLED : TaskState.FAILED, code);
          return null;
        });
  }

  @Override
  public Optional<Step> checkpoint(Lease lease) {
    return admissions.admission(
        lease,
        () -> {
          var task = records.lockAdmission(lease);
          return task.latest()
              .map(
                  previous -> {
                    task.validateMaterial(previous.request(), previous.ordinal());
                    return checked(previous);
                  });
        });
  }

  private Step checked(ExecutionRecords.StoredStep step) {
    if (!"RETAINED".equals(step.cleanup()))
      throw new TaskFailure(410, TaskErrorCode.CHECKPOINT_MEDIA_PURGED);
    if (step.version() != 1) throw new TaskFailure(409, TaskErrorCode.PAYLOAD_VERSION_UNSUPPORTED);
    if (step.response() != null && !digest.hash(step.response()).equals(step.responseDigest()))
      throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
    if (!digest.hash(step.request()).equals(step.requestDigest()))
      throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
    return new Step(step.id(), step.ordinal(), step.request(), step.response(), step.dispatchId());
  }

  @Override
  public Step prepare(Lease lease, String request) {
    return admissions.admission(
        lease,
        () -> {
          var task = records.lockAdmission(lease);
          var previous = task.latest();
          if (previous.isPresent() && request.equals(previous.get().request())) {
            task.validateMaterial(request, previous.get().ordinal());
            return checked(previous.get());
          }
          int ordinal = previous.isEmpty() ? 1 : previous.get().ordinal() + 1;
          task.validateMaterial(request, ordinal);
          if (previous.isPresent()
              && (previous.get().response() == null
                  || task.unfinishedInvocations(previous.get().id())))
            throw new TaskFailure(409, TaskErrorCode.CHECKPOINT_INCOMPLETE);
          task.validateModuleRequest(request);
          String id = UUID.randomUUID().toString();
          task.reserveMedia(id, request);
          task.insertStep(id, ordinal, request);
          task.prepareProjections(id, request);
          task.preparedEvent(id);
          task.commitFence();
          return new Step(id, ordinal, request, null, null);
        });
  }

  @Override
  public boolean finish(Lease lease, String summary) {
    return admissions.admission(
        lease,
        () -> {
          var task = records.lockAdmission(lease);
          task.validateSummary(summary);
          if (task.pendingQuestions()) {
            String step = task.latestStepId();
            task.pauseQuestions(step);
            return true;
          }
          if (task.publicReport() && !task.publishedReport())
            throw new TaskFailure(409, ArtifactErrorCode.REPORT_NOT_PUBLISHED);
          if (task.unresolvedInvocations() || task.unresolvedArtifacts())
            throw new TaskFailure(409, ArtifactErrorCode.UNRESOLVED_FACTS);
          task.finishSummary(summary);
          task.terminal(TaskState.SUCCEEDED, null);
          return false;
        });
  }
}
