package io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("model_dispatch")
public final class TodoDispatchReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  @TableField("id")
  private Object rowId;

  public Object getRowId() {
    return rowId;
  }

  public void setRowId(Object value) {
    rowId = value;
  }

  @TableField("usage_snapshot")
  private Object usageSnapshot;

  public Object getUsageSnapshot() {
    return usageSnapshot;
  }

  public void setUsageSnapshot(Object value) {
    usageSnapshot = value;
  }
}
