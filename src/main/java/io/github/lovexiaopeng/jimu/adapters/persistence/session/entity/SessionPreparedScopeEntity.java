package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原七列检查点 scope INSERT；显式 NULL，其他列沿数据库默认值。 */
@TableName("step_session_scope")
public final class SessionPreparedScopeEntity {
  @TableField(value = "tenant_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String tenantId;

  @TableField(value = "step_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String stepId;

  @TableField(value = "caller_task_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String callerTaskId;

  @TableField(value = "authority_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String authorityDigest;

  @TableField(value = "context_snapshot", insertStrategy = FieldStrategy.ALWAYS)
  private final String contextSnapshot;

  @TableField(value = "context_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String contextDigest;

  @TableField(value = "context_bytes", insertStrategy = FieldStrategy.ALWAYS)
  private final int contextBytes;

  public SessionPreparedScopeEntity(
      String tenantId,
      String stepId,
      String callerTaskId,
      String authorityDigest,
      String contextSnapshot,
      String contextDigest,
      int contextBytes) {
    this.tenantId = tenantId;
    this.stepId = stepId;
    this.callerTaskId = callerTaskId;
    this.authorityDigest = authorityDigest;
    this.contextSnapshot = contextSnapshot;
    this.contextDigest = contextDigest;
    this.contextBytes = contextBytes;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getStepId() {
    return stepId;
  }

  public String getCallerTaskId() {
    return callerTaskId;
  }

  public String getAuthorityDigest() {
    return authorityDigest;
  }

  public String getContextSnapshot() {
    return contextSnapshot;
  }

  public String getContextDigest() {
    return contextDigest;
  }

  public int getContextBytes() {
    return contextBytes;
  }
}
