package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Actual binding INSERT fields; composite keys remain explicit, defaults stay database-owned. */
@TableName("invocation_session_search")
public final class SessionSearchBindingEntity {
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

  @TableField(value = "normalized_query_snapshot", insertStrategy = FieldStrategy.ALWAYS)
  private final String normalizedQuerySnapshot;

  @TableField(value = "normalized_query_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String normalizedQueryDigest;

  @TableField(value = "normalized_query_bytes", insertStrategy = FieldStrategy.ALWAYS)
  private final Long normalizedQueryBytes;

  public SessionSearchBindingEntity(
      String tenantId,
      String invocationId,
      String taskId,
      String stepId,
      String bindingDigest,
      String inputDigest,
      Long upperSeq,
      String normalizedQuerySnapshot,
      String normalizedQueryDigest,
      Long normalizedQueryBytes) {
    this.tenantId = tenantId;
    this.invocationId = invocationId;
    this.taskId = taskId;
    this.stepId = stepId;
    this.bindingDigest = bindingDigest;
    this.inputDigest = inputDigest;
    this.upperSeq = upperSeq;
    this.normalizedQuerySnapshot = normalizedQuerySnapshot;
    this.normalizedQueryDigest = normalizedQueryDigest;
    this.normalizedQueryBytes = normalizedQueryBytes;
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

  public String getNormalizedQuerySnapshot() {
    return normalizedQuerySnapshot;
  }

  public String getNormalizedQueryDigest() {
    return normalizedQueryDigest;
  }

  public Long getNormalizedQueryBytes() {
    return normalizedQueryBytes;
  }
}
