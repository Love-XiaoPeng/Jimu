package io.github.lovexiaopeng.jimu.adapters.persistence.budget.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原reservation完整11列读投影，不伪造复合主键。 */
@TableName(value = "reservation", autoResultMap = true)
public final class ReservationReadEntity {
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

  @TableField("dispatch_id")
  private Object dispatchId;

  public Object getDispatchId() {
    return dispatchId;
  }

  public void setDispatchId(Object value) {
    dispatchId = value;
  }

  @TableField("category")
  private Object category;

  public Object getCategory() {
    return category;
  }

  public void setCategory(Object value) {
    category = value;
  }

  @TableField("amount")
  private Object amount;

  public Object getAmount() {
    return amount;
  }

  public void setAmount(Object value) {
    amount = value;
  }

  @TableField("actual")
  private Object actual;

  public Object getActual() {
    return actual;
  }

  public void setActual(Object value) {
    actual = value;
  }

  @TableField("usage_quality")
  private Object usageQuality;

  public Object getUsageQuality() {
    return usageQuality;
  }

  public void setUsageQuality(Object value) {
    usageQuality = value;
  }

  @TableField("cost_quality")
  private Object costQuality;

  public Object getCostQuality() {
    return costQuality;
  }

  public void setCostQuality(Object value) {
    costQuality = value;
  }

  @TableField("state")
  private Object state;

  public Object getState() {
    return state;
  }

  public void setState(Object value) {
    state = value;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Object value) {
    recordVersion = value;
  }
}
