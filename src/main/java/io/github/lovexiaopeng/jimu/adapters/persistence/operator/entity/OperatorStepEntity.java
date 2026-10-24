package io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运维证明固定只读投影，不声明伪单列主键。 */
@TableName(value = "step", autoResultMap = true)
public final class OperatorStepEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("id")
  private Object rowId;

  public Object getRowId() {
    return rowId;
  }

  public void setRowId(Object value) {
    rowId = value;
  }

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  @TableField("request_snapshot")
  private Object requestSnapshot;

  public Object getRequestSnapshot() {
    return requestSnapshot;
  }

  public void setRequestSnapshot(Object value) {
    requestSnapshot = value;
  }

  @TableField("request_digest")
  private Object requestDigest;

  public Object getRequestDigest() {
    return requestDigest;
  }

  public void setRequestDigest(Object value) {
    requestDigest = value;
  }

  @TableField("response_snapshot")
  private Object responseSnapshot;

  public Object getResponseSnapshot() {
    return responseSnapshot;
  }

  public void setResponseSnapshot(Object value) {
    responseSnapshot = value;
  }

  @TableField("response_digest")
  private Object responseDigest;

  public Object getResponseDigest() {
    return responseDigest;
  }

  public void setResponseDigest(Object value) {
    responseDigest = value;
  }
}
