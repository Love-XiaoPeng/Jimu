package io.github.lovexiaopeng.jimu.adapters.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** model_dispatch 的新增记录字段；复合主键显式保留 tenant，不声明单列主键或自动 ID。 */
@TableName("model_dispatch")
public final class ModelDispatchEntity {
  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    tenantId = value;
  }

  @TableField(value = "id")
  private String dispatchId;

  public String getDispatchId() {
    return dispatchId;
  }

  public void setDispatchId(String value) {
    dispatchId = value;
  }

  private String taskId;

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String value) {
    taskId = value;
  }

  private String stepId;

  public String getStepId() {
    return stepId;
  }

  public void setStepId(String value) {
    stepId = value;
  }

  private String configDigest;

  public String getConfigDigest() {
    return configDigest;
  }

  public void setConfigDigest(String value) {
    configDigest = value;
  }

  private String requestDigest;

  public String getRequestDigest() {
    return requestDigest;
  }

  public void setRequestDigest(String value) {
    requestDigest = value;
  }

  private String state;

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String priceVersion;

  public String getPriceVersion() {
    return priceVersion;
  }

  public void setPriceVersion(String value) {
    priceVersion = value;
  }

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private String currency;

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    currency = value;
  }

  @TableField(insertStrategy = FieldStrategy.ALWAYS)
  private Long reservedCost;

  public Long getReservedCost() {
    return reservedCost;
  }

  public void setReservedCost(Long value) {
    reservedCost = value;
  }
}
