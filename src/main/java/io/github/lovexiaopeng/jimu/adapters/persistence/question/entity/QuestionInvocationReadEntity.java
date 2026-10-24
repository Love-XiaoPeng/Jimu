package io.github.lovexiaopeng.jimu.adapters.persistence.question.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 本用例实际消费的正文与完整集合谓词；无伪单列主键。 */
@TableName("invocation")
public final class QuestionInvocationReadEntity {
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
