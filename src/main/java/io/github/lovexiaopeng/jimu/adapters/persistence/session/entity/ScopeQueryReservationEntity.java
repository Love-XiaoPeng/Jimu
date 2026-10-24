package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("reservation")
public final class ScopeQueryReservationEntity {
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

  @TableField("category")
  private Object category;

  public Object getCategory() {
    return category;
  }

  @TableField("state")
  private Object state;

  public Object getState() {
    return state;
  }

  @TableField("actual")
  private Object actual;

  public Object getActual() {
    return actual;
  }
}
