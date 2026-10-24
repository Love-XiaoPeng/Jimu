package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("invocation_session_read")
public final class HistoryInvocationSessionReadReadEntity {
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

  @TableField("invocation_id")
  private Object invocationId;

  public Object getInvocationId() {
    return invocationId;
  }

  @TableField("read_sources_snapshot")
  private Object readSourcesSnapshot;

  public Object getReadSourcesSnapshot() {
    return readSourcesSnapshot;
  }
}
