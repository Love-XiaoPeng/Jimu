package io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运维证明固定只读投影，不声明伪单列主键。 */
@TableName(value = "event", autoResultMap = true)
public final class OperatorEventEntity {
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

  @TableField("fact_id")
  private Object factId;

  public Object getFactId() {
    return factId;
  }

  public void setFactId(Object value) {
    factId = value;
  }

  @TableField("type")
  private Object type;

  public Object getType() {
    return type;
  }

  public void setType(Object value) {
    type = value;
  }

  @TableField("code")
  private Object code;

  public Object getCode() {
    return code;
  }

  public void setCode(Object value) {
    code = value;
  }
}
