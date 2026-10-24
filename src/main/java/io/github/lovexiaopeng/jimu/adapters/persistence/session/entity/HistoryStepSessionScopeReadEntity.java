package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("step_session_scope")
public final class HistoryStepSessionScopeReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("caller_task_id")
  private Object callerTaskId;

  public Object getCallerTaskId() {
    return callerTaskId;
  }

  @TableField("step_id")
  private Object stepId;

  public Object getStepId() {
    return stepId;
  }

  @TableField("context_snapshot")
  private Object contextSnapshot;

  public Object getContextSnapshot() {
    return contextSnapshot;
  }
}
