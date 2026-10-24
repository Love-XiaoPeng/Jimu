package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("task")
public final class HistoryTaskReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("id")
  private Object rowId;

  public Object getRowId() {
    return rowId;
  }

  @TableField("request_snapshot")
  private Object requestSnapshot;

  public Object getRequestSnapshot() {
    return requestSnapshot;
  }

  @TableField("goal")
  private Object goal;

  public Object getGoal() {
    return goal;
  }

  @TableField("summary")
  private Object summary;

  public Object getSummary() {
    return summary;
  }
}
