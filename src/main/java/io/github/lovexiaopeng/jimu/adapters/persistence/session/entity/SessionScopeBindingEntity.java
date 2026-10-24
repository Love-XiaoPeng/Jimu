package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Actual binding INSERT fields; composite keys remain explicit, defaults stay database-owned. */
@TableName("invocation_session_scope_query")
public final class SessionScopeBindingEntity {
  @TableField(value = "tenant_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String tenantId;

  @TableField(value = "invocation_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String invocationId;

  @TableField(value = "caller_task_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String callerTaskId;

  @TableField(value = "step_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String stepId;

  @TableField(value = "operation", insertStrategy = FieldStrategy.ALWAYS)
  private final String operation;

  @TableField(value = "binding_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String bindingDigest;

  @TableField(value = "input_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String inputDigest;

  @TableField(value = "authority_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String authorityDigest;

  @TableField(value = "context_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String contextDigest;

  @TableField(value = "normalized_snapshot", insertStrategy = FieldStrategy.ALWAYS)
  private final String normalizedSnapshot;

  @TableField(value = "normalized_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String normalizedDigest;

  @TableField(value = "normalized_bytes", insertStrategy = FieldStrategy.ALWAYS)
  private final Long normalizedBytes;

  public SessionScopeBindingEntity(
      String tenantId,
      String invocationId,
      String callerTaskId,
      String stepId,
      String operation,
      String bindingDigest,
      String inputDigest,
      String authorityDigest,
      String contextDigest,
      String normalizedSnapshot,
      String normalizedDigest,
      Long normalizedBytes) {
    this.tenantId = tenantId;
    this.invocationId = invocationId;
    this.callerTaskId = callerTaskId;
    this.stepId = stepId;
    this.operation = operation;
    this.bindingDigest = bindingDigest;
    this.inputDigest = inputDigest;
    this.authorityDigest = authorityDigest;
    this.contextDigest = contextDigest;
    this.normalizedSnapshot = normalizedSnapshot;
    this.normalizedDigest = normalizedDigest;
    this.normalizedBytes = normalizedBytes;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public String getCallerTaskId() {
    return callerTaskId;
  }

  public String getStepId() {
    return stepId;
  }

  public String getOperation() {
    return operation;
  }

  public String getBindingDigest() {
    return bindingDigest;
  }

  public String getInputDigest() {
    return inputDigest;
  }

  public String getAuthorityDigest() {
    return authorityDigest;
  }

  public String getContextDigest() {
    return contextDigest;
  }

  public String getNormalizedSnapshot() {
    return normalizedSnapshot;
  }

  public String getNormalizedDigest() {
    return normalizedDigest;
  }

  public Long getNormalizedBytes() {
    return normalizedBytes;
  }
}
