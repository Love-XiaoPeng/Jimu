package io.github.lovexiaopeng.jimu.adapters.persistence.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 既有可见性/权限复核所需的固定完整投影；不声明伪单列主键，不用于实体写入。 */
@TableName(value = "task", autoResultMap = true)
public final class TaskVisibilityEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  @TableField("owner_actor_id")
  private Object ownerActorId;

  public Object getOwnerActorId() {
    return ownerActorId;
  }

  public void setOwnerActorId(Object value) {
    ownerActorId = value;
  }

  @TableField("idempotency_key")
  private Object idempotencyKey;

  public Object getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(Object value) {
    idempotencyKey = value;
  }

  @TableField("request_digest")
  private Object requestDigest;

  public Object getRequestDigest() {
    return requestDigest;
  }

  public void setRequestDigest(Object value) {
    requestDigest = value;
  }

  @TableField("request_snapshot")
  private Object requestSnapshot;

  public Object getRequestSnapshot() {
    return requestSnapshot;
  }

  public void setRequestSnapshot(Object value) {
    requestSnapshot = value;
  }

  @TableField("goal")
  private Object goal;

  public Object getGoal() {
    return goal;
  }

  public void setGoal(Object value) {
    goal = value;
  }

  @TableField("capabilities")
  private Object capabilities;

  public Object getCapabilities() {
    return capabilities;
  }

  public void setCapabilities(Object value) {
    capabilities = value;
  }

  @TableField("installation_epoch")
  private Object installationEpoch;

  public Object getInstallationEpoch() {
    return installationEpoch;
  }

  public void setInstallationEpoch(Object value) {
    installationEpoch = value;
  }

  @TableField("policy_version")
  private Object policyVersion;

  public Object getPolicyVersion() {
    return policyVersion;
  }

  public void setPolicyVersion(Object value) {
    policyVersion = value;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Object value) {
    recordVersion = value;
  }

  @TableField("state")
  private Object state;

  public Object getState() {
    return state;
  }

  public void setState(Object value) {
    state = value;
  }

  @TableField("revision")
  private Object revision;

  public Object getRevision() {
    return revision;
  }

  public void setRevision(Object value) {
    revision = value;
  }

  @TableField("created_at")
  private Object createdAt;

  public Object getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Object value) {
    createdAt = value;
  }

  @TableField("updated_at")
  private Object updatedAt;

  public Object getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Object value) {
    updatedAt = value;
  }

  @TableField("deadline")
  private Object deadline;

  public Object getDeadline() {
    return deadline;
  }

  public void setDeadline(Object value) {
    deadline = value;
  }

  @TableField("ready_at")
  private Object readyAt;

  public Object getReadyAt() {
    return readyAt;
  }

  public void setReadyAt(Object value) {
    readyAt = value;
  }

  @TableField("cancel_requested_at")
  private Object cancelRequestedAt;

  public Object getCancelRequestedAt() {
    return cancelRequestedAt;
  }

  public void setCancelRequestedAt(Object value) {
    cancelRequestedAt = value;
  }

  @TableField("current_attempt_id")
  private Object currentAttemptId;

  public Object getCurrentAttemptId() {
    return currentAttemptId;
  }

  public void setCurrentAttemptId(Object value) {
    currentAttemptId = value;
  }

  @TableField("worker_id")
  private Object workerId;

  public Object getWorkerId() {
    return workerId;
  }

  public void setWorkerId(Object value) {
    workerId = value;
  }

  @TableField("fence")
  private Object fence;

  public Object getFence() {
    return fence;
  }

  public void setFence(Object value) {
    fence = value;
  }

  @TableField("lease_until")
  private Object leaseUntil;

  public Object getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Object value) {
    leaseUntil = value;
  }

  @TableField("next_event_seq")
  private Object nextEventSeq;

  public Object getNextEventSeq() {
    return nextEventSeq;
  }

  public void setNextEventSeq(Object value) {
    nextEventSeq = value;
  }

  @TableField("max_models")
  private Object maxModels;

  public Object getMaxModels() {
    return maxModels;
  }

  public void setMaxModels(Object value) {
    maxModels = value;
  }

  @TableField("max_tools")
  private Object maxTools;

  public Object getMaxTools() {
    return maxTools;
  }

  public void setMaxTools(Object value) {
    maxTools = value;
  }

  @TableField("models_used")
  private Object modelsUsed;

  public Object getModelsUsed() {
    return modelsUsed;
  }

  public void setModelsUsed(Object value) {
    modelsUsed = value;
  }

  @TableField("tools_used")
  private Object toolsUsed;

  public Object getToolsUsed() {
    return toolsUsed;
  }

  public void setToolsUsed(Object value) {
    toolsUsed = value;
  }

  @TableField("token_held")
  private Object tokenHeld;

  public Object getTokenHeld() {
    return tokenHeld;
  }

  public void setTokenHeld(Object value) {
    tokenHeld = value;
  }

  @TableField("max_tokens")
  private Object maxTokens;

  public Object getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(Object value) {
    maxTokens = value;
  }

  @TableField("code")
  private Object code;

  public Object getCode() {
    return code;
  }

  public void setCode(Object value) {
    code = value;
  }

  @TableField("summary")
  private Object summary;

  public Object getSummary() {
    return summary;
  }

  public void setSummary(Object value) {
    summary = value;
  }

  @TableField("model_snapshot")
  private Object modelSnapshot;

  public Object getModelSnapshot() {
    return modelSnapshot;
  }

  public void setModelSnapshot(Object value) {
    modelSnapshot = value;
  }

  @TableField("model_digest")
  private Object modelDigest;

  public Object getModelDigest() {
    return modelDigest;
  }

  public void setModelDigest(Object value) {
    modelDigest = value;
  }

  @TableField("evaluation_run_id")
  private Object evaluationRunId;

  public Object getEvaluationRunId() {
    return evaluationRunId;
  }

  public void setEvaluationRunId(Object value) {
    evaluationRunId = value;
  }

  @TableField("cost_held")
  private Object costHeld;

  public Object getCostHeld() {
    return costHeld;
  }

  public void setCostHeld(Object value) {
    costHeld = value;
  }

  @TableField("max_cost_micros")
  private Object maxCostMicros;

  public Object getMaxCostMicros() {
    return maxCostMicros;
  }

  public void setMaxCostMicros(Object value) {
    maxCostMicros = value;
  }

  @TableField("tools_snapshot")
  private Object toolsSnapshot;

  public Object getToolsSnapshot() {
    return toolsSnapshot;
  }

  public void setToolsSnapshot(Object value) {
    toolsSnapshot = value;
  }

  @TableField("tools_digest")
  private Object toolsDigest;

  public Object getToolsDigest() {
    return toolsDigest;
  }

  public void setToolsDigest(Object value) {
    toolsDigest = value;
  }

  @TableField("report_time")
  private Object reportTime;

  public Object getReportTime() {
    return reportTime;
  }

  public void setReportTime(Object value) {
    reportTime = value;
  }

  @TableField("evaluation_snapshot")
  private Object evaluationSnapshot;

  public Object getEvaluationSnapshot() {
    return evaluationSnapshot;
  }

  public void setEvaluationSnapshot(Object value) {
    evaluationSnapshot = value;
  }

  @TableField("evaluation_digest")
  private Object evaluationDigest;

  public Object getEvaluationDigest() {
    return evaluationDigest;
  }

  public void setEvaluationDigest(Object value) {
    evaluationDigest = value;
  }

  @TableField("evaluation_phase")
  private Object evaluationPhase;

  public Object getEvaluationPhase() {
    return evaluationPhase;
  }

  public void setEvaluationPhase(Object value) {
    evaluationPhase = value;
  }

  @TableField("evaluation_sample")
  private Object evaluationSample;

  public Object getEvaluationSample() {
    return evaluationSample;
  }

  public void setEvaluationSample(Object value) {
    evaluationSample = value;
  }

  @TableField("evaluation_model")
  private Object evaluationModel;

  public Object getEvaluationModel() {
    return evaluationModel;
  }

  public void setEvaluationModel(Object value) {
    evaluationModel = value;
  }

  @TableField("material_snapshot")
  private Object materialSnapshot;

  public Object getMaterialSnapshot() {
    return materialSnapshot;
  }

  public void setMaterialSnapshot(Object value) {
    materialSnapshot = value;
  }

  @TableField("material_digest")
  private Object materialDigest;

  public Object getMaterialDigest() {
    return materialDigest;
  }

  public void setMaterialDigest(Object value) {
    materialDigest = value;
  }

  @TableField("material_system_digest")
  private Object materialSystemDigest;

  public Object getMaterialSystemDigest() {
    return materialSystemDigest;
  }

  public void setMaterialSystemDigest(Object value) {
    materialSystemDigest = value;
  }

  @TableField("material_file_snapshot")
  private Object materialFileSnapshot;

  public Object getMaterialFileSnapshot() {
    return materialFileSnapshot;
  }

  public void setMaterialFileSnapshot(Object value) {
    materialFileSnapshot = value;
  }

  @TableField("material_file_digest")
  private Object materialFileDigest;

  public Object getMaterialFileDigest() {
    return materialFileDigest;
  }

  public void setMaterialFileDigest(Object value) {
    materialFileDigest = value;
  }

  @TableField("execution_scope_digest")
  private Object executionScopeDigest;

  public Object getExecutionScopeDigest() {
    return executionScopeDigest;
  }

  public void setExecutionScopeDigest(Object value) {
    executionScopeDigest = value;
  }

  @TableField("business_policy_snapshot")
  private Object businessPolicySnapshot;

  public Object getBusinessPolicySnapshot() {
    return businessPolicySnapshot;
  }

  public void setBusinessPolicySnapshot(Object value) {
    businessPolicySnapshot = value;
  }

  @TableField("business_policy_digest")
  private Object businessPolicyDigest;

  public Object getBusinessPolicyDigest() {
    return businessPolicyDigest;
  }

  public void setBusinessPolicyDigest(Object value) {
    businessPolicyDigest = value;
  }

  @TableField("media_snapshot")
  private Object mediaSnapshot;

  public Object getMediaSnapshot() {
    return mediaSnapshot;
  }

  public void setMediaSnapshot(Object value) {
    mediaSnapshot = value;
  }

  @TableField("media_digest")
  private Object mediaDigest;

  public Object getMediaDigest() {
    return mediaDigest;
  }

  public void setMediaDigest(Object value) {
    mediaDigest = value;
  }

  @TableField("media_cleanup_state")
  private Object mediaCleanupState;

  public Object getMediaCleanupState() {
    return mediaCleanupState;
  }

  public void setMediaCleanupState(Object value) {
    mediaCleanupState = value;
  }

  @TableField("mcp_snapshot")
  private Object mcpSnapshot;

  public Object getMcpSnapshot() {
    return mcpSnapshot;
  }

  public void setMcpSnapshot(Object value) {
    mcpSnapshot = value;
  }

  @TableField("mcp_digest")
  private Object mcpDigest;

  public Object getMcpDigest() {
    return mcpDigest;
  }

  public void setMcpDigest(Object value) {
    mcpDigest = value;
  }

  @TableField("question_binding")
  private Object questionBinding;

  public Object getQuestionBinding() {
    return questionBinding;
  }

  public void setQuestionBinding(Object value) {
    questionBinding = value;
  }

  @TableField("question_binding_digest")
  private Object questionBindingDigest;

  public Object getQuestionBindingDigest() {
    return questionBindingDigest;
  }

  public void setQuestionBindingDigest(Object value) {
    questionBindingDigest = value;
  }

  @TableField("waiting_question_id")
  private Object waitingQuestionId;

  public Object getWaitingQuestionId() {
    return waitingQuestionId;
  }

  public void setWaitingQuestionId(Object value) {
    waitingQuestionId = value;
  }

  @TableField("question_continuation_step_id")
  private Object questionContinuationStepId;

  public Object getQuestionContinuationStepId() {
    return questionContinuationStepId;
  }

  public void setQuestionContinuationStepId(Object value) {
    questionContinuationStepId = value;
  }

  @TableField("question_cleanup_state")
  private Object questionCleanupState;

  public Object getQuestionCleanupState() {
    return questionCleanupState;
  }

  public void setQuestionCleanupState(Object value) {
    questionCleanupState = value;
  }

  @TableField("question_retained_digests")
  private Object questionRetainedDigests;

  public Object getQuestionRetainedDigests() {
    return questionRetainedDigests;
  }

  public void setQuestionRetainedDigests(Object value) {
    questionRetainedDigests = value;
  }
}
