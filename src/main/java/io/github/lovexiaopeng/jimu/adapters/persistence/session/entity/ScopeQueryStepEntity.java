package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("step")
public final class ScopeQueryStepEntity {
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

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  @TableField("request_snapshot")
  private Object requestSnapshot;

  public Object getRequestSnapshot() {
    return requestSnapshot;
  }

  @TableField("request_digest")
  private Object requestDigest;

  public Object getRequestDigest() {
    return requestDigest;
  }

  @TableField("session_upper_seq")
  private Object sessionUpperSeq;

  public Object getSessionUpperSeq() {
    return sessionUpperSeq;
  }

  @TableField("session_context_digest")
  private Object sessionContextDigest;

  public Object getSessionContextDigest() {
    return sessionContextDigest;
  }

  @TableField("session_binding_digest")
  private Object sessionBindingDigest;

  public Object getSessionBindingDigest() {
    return sessionBindingDigest;
  }
}
