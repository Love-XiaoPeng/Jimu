package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Actual binding INSERT fields; composite keys remain explicit, defaults stay database-owned. */
@TableName("invocation_session_read")
public final class SessionReadBindingEntity {
  @TableField(value = "tenant_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String tenantId;

  @TableField(value = "invocation_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String invocationId;

  @TableField(value = "task_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String taskId;

  @TableField(value = "step_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String stepId;

  @TableField(value = "binding_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String bindingDigest;

  @TableField(value = "input_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String inputDigest;

  @TableField(value = "upper_seq", insertStrategy = FieldStrategy.ALWAYS)
  private final Long upperSeq;

  @TableField(value = "target_seq", insertStrategy = FieldStrategy.ALWAYS)
  private final Long targetSeq;

  @TableField(value = "before_count", insertStrategy = FieldStrategy.ALWAYS)
  private final Long beforeCount;

  @TableField(value = "after_count", insertStrategy = FieldStrategy.ALWAYS)
  private final Long afterCount;

  @TableField(value = "relation_version", insertStrategy = FieldStrategy.ALWAYS)
  private final Long relationVersion;

  public SessionReadBindingEntity(
      String tenantId,
      String invocationId,
      String taskId,
      String stepId,
      String bindingDigest,
      String inputDigest,
      Long upperSeq,
      Long targetSeq,
      Long beforeCount,
      Long afterCount,
      Long relationVersion) {
    this.tenantId = tenantId;
    this.invocationId = invocationId;
    this.taskId = taskId;
    this.stepId = stepId;
    this.bindingDigest = bindingDigest;
    this.inputDigest = inputDigest;
    this.upperSeq = upperSeq;
    this.targetSeq = targetSeq;
    this.beforeCount = beforeCount;
    this.afterCount = afterCount;
    this.relationVersion = relationVersion;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public String getTaskId() {
    return taskId;
  }

  public String getStepId() {
    return stepId;
  }

  public String getBindingDigest() {
    return bindingDigest;
  }

  public String getInputDigest() {
    return inputDigest;
  }

  public Long getUpperSeq() {
    return upperSeq;
  }

  public Long getTargetSeq() {
    return targetSeq;
  }

  public Long getBeforeCount() {
    return beforeCount;
  }

  public Long getAfterCount() {
    return afterCount;
  }

  public Long getRelationVersion() {
    return relationVersion;
  }
}
