package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("session_public_event")
public final class HistorySessionPublicEventReadEntity {
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

  @TableField("seq")
  private Object seq;

  public Object getSeq() {
    return seq;
  }

  @TableField("event_snapshot")
  private Object eventSnapshot;

  public Object getEventSnapshot() {
    return eventSnapshot;
  }
}
