package io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("invocation")
public final class TodoInvocationReadEntity {
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

  @TableField("input_snapshot")
  private Object inputSnapshot;

  public Object getInputSnapshot() {
    return inputSnapshot;
  }

  public void setInputSnapshot(Object value) {
    inputSnapshot = value;
  }

  @TableField("output_snapshot")
  private Object outputSnapshot;

  public Object getOutputSnapshot() {
    return outputSnapshot;
  }

  public void setOutputSnapshot(Object value) {
    outputSnapshot = value;
  }
}
