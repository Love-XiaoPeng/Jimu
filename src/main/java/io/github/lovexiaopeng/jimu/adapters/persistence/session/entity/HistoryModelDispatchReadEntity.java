package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("model_dispatch")
public final class HistoryModelDispatchReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  @TableField("id")
  private Object rowId;

  public Object getRowId() {
    return rowId;
  }

  @TableField("usage_snapshot")
  private Object usageSnapshot;

  public Object getUsageSnapshot() {
    return usageSnapshot;
  }
}
