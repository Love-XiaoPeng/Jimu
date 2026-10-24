package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 清理摘要只读投影，完整集合键；不伪造复合主键。 */
@TableName("invocation_session_scope_query")
public final class HistoryInvocationSessionScopeQueryReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("caller_task_id")
  private Object callerTaskId;

  public Object getCallerTaskId() {
    return callerTaskId;
  }

  @TableField("invocation_id")
  private Object invocationId;

  public Object getInvocationId() {
    return invocationId;
  }

  @TableField("normalized_snapshot")
  private Object normalizedSnapshot;

  public Object getNormalizedSnapshot() {
    return normalizedSnapshot;
  }

  @TableField("read_refs_snapshot")
  private Object readRefsSnapshot;

  public Object getReadRefsSnapshot() {
    return readRefsSnapshot;
  }
}
