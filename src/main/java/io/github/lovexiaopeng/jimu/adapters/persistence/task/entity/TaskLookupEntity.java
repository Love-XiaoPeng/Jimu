package io.github.lovexiaopeng.jimu.adapters.persistence.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 仅用于固定受理查询的实体，复合键始终显式传递，不声明单列主键。 */
@TableName(value = "task", autoResultMap = true)
public final class TaskLookupEntity {
  @TableField("tenant_id")
  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    tenantId = value;
  }

  @TableField("id")
  private String taskId;

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String value) {
    taskId = value;
  }

  @TableField("owner_actor_id")
  private String ownerActorId;

  public String getOwnerActorId() {
    return ownerActorId;
  }

  public void setOwnerActorId(String value) {
    ownerActorId = value;
  }

  @TableField("idempotency_key")
  private String idempotencyKey;

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String value) {
    idempotencyKey = value;
  }

  @TableField("evaluation_run_id")
  private String evaluationRunId;

  public String getEvaluationRunId() {
    return evaluationRunId;
  }

  public void setEvaluationRunId(String value) {
    evaluationRunId = value;
  }

  @TableField("evaluation_model")
  private String evaluationModel;

  public String getEvaluationModel() {
    return evaluationModel;
  }

  public void setEvaluationModel(String value) {
    evaluationModel = value;
  }

  @TableField("evaluation_sample")
  private String evaluationSample;

  public String getEvaluationSample() {
    return evaluationSample;
  }

  public void setEvaluationSample(String value) {
    evaluationSample = value;
  }
}
