package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("session_public_event")
public final class ScopeQueryNativeEntity {
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

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  @TableField("event_version")
  private Object eventVersion;

  public Object getEventVersion() {
    return eventVersion;
  }

  @TableField("fact_key")
  private Object factKey;

  public Object getFactKey() {
    return factKey;
  }

  @TableField("type")
  private Object type;

  public Object getType() {
    return type;
  }

  @TableField("occurred_at")
  private Object occurredAt;

  public Object getOccurredAt() {
    return occurredAt;
  }

  @TableField("event_snapshot")
  private Object eventSnapshot;

  public Object getEventSnapshot() {
    return eventSnapshot;
  }

  @TableField("event_digest")
  private Object eventDigest;

  public Object getEventDigest() {
    return eventDigest;
  }

  @TableField("event_bytes")
  private Object eventBytes;

  public Object getEventBytes() {
    return eventBytes;
  }

  @TableField("source_digest")
  private Object sourceDigest;

  public Object getSourceDigest() {
    return sourceDigest;
  }

  @TableField("source_step_id")
  private Object sourceStepId;

  public Object getSourceStepId() {
    return sourceStepId;
  }

  @TableField("source_invocation_id")
  private Object sourceInvocationId;

  public Object getSourceInvocationId() {
    return sourceInvocationId;
  }

  @TableField("committed_attempt_id")
  private Object committedAttemptId;

  public Object getCommittedAttemptId() {
    return committedAttemptId;
  }

  @TableField("committed_fence")
  private Object committedFence;

  public Object getCommittedFence() {
    return committedFence;
  }

  @TableField("reliable_event_seq")
  private Object reliableEventSeq;

  public Object getReliableEventSeq() {
    return reliableEventSeq;
  }

  @TableField("content_state")
  private Object contentState;

  public Object getContentState() {
    return contentState;
  }
}
