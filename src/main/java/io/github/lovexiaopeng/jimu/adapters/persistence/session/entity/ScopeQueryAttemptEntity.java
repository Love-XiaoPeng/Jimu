package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("task_attempt")
public final class ScopeQueryAttemptEntity {
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

  @TableField("fence")
  private Object fence;

  public Object getFence() {
    return fence;
  }
}
