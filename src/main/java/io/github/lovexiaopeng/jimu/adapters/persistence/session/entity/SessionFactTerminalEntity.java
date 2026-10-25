package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** Four selected terminal columns; full-key predicates remain explicit. */
@TableName("event")
public final class SessionFactTerminalEntity {
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

  @TableField("fact_id")
  private Object factId;

  public Object getFactId() {
    return factId;
  }

  @TableField("event_id")
  private Object eventId;

  public Object getEventId() {
    return eventId;
  }

  @TableField("type")
  private Object type;

  public Object getType() {
    return type;
  }

  @TableField("status")
  private Object status;

  public Object getStatus() {
    return status;
  }

  @TableField("code")
  private Object code;

  public Object getCode() {
    return code;
  }
}
