package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("invocation")
public final class HistoryInvocationReadEntity {
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

  @TableField("input_snapshot")
  private Object inputSnapshot;

  public Object getInputSnapshot() {
    return inputSnapshot;
  }

  @TableField("output_snapshot")
  private Object outputSnapshot;

  public Object getOutputSnapshot() {
    return outputSnapshot;
  }
}
