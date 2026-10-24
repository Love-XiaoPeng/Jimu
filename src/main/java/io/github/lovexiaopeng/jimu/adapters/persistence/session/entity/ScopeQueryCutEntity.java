package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("step_session_scope")
public final class ScopeQueryCutEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("step_id")
  private Object stepId;

  public Object getStepId() {
    return stepId;
  }

  @TableField("caller_task_id")
  private Object callerTaskId;

  public Object getCallerTaskId() {
    return callerTaskId;
  }

  @TableField("authority_digest")
  private Object authorityDigest;

  public Object getAuthorityDigest() {
    return authorityDigest;
  }

  @TableField("context_snapshot")
  private Object contextSnapshot;

  public Object getContextSnapshot() {
    return contextSnapshot;
  }

  @TableField("context_digest")
  private Object contextDigest;

  public Object getContextDigest() {
    return contextDigest;
  }
}
