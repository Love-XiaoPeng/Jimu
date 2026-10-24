package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("task_session_scope_target")
public final class HistoryTaskSessionScopeTargetReadEntity {
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

  @TableField("ordinal")
  private Object ordinal;

  public Object getOrdinal() {
    return ordinal;
  }

  @TableField("source_task_id")
  private Object sourceTaskId;

  public Object getSourceTaskId() {
    return sourceTaskId;
  }

  @TableField("target_snapshot")
  private Object targetSnapshot;

  public Object getTargetSnapshot() {
    return targetSnapshot;
  }
}
