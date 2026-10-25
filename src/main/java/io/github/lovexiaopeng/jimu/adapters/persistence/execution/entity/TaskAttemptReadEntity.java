package io.github.lovexiaopeng.jimu.adapters.persistence.execution.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Composite tenant/id key is never treated as a global single-column primary key. */
@TableName("task_attempt")
public final class TaskAttemptReadEntity {
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

  @TableField("fence")
  private Object fence;

  public Object getFence() {
    return fence;
  }
}
