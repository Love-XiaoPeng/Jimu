package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("event")
public final class ScopeQueryReceiptEntity {
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

  @TableField("fact_id")
  private Object factId;

  public Object getFactId() {
    return factId;
  }

  @TableField("type")
  private Object type;

  public Object getType() {
    return type;
  }

  @TableField("code")
  private Object code;

  public Object getCode() {
    return code;
  }
}
