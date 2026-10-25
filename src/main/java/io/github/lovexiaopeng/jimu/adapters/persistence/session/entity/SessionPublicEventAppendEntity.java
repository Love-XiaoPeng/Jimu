package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** The original sixteen INSERT fields only; NULLs explicit and other columns DB-defaulted. */
@TableName("session_public_event")
public final class SessionPublicEventAppendEntity {
  @TableField(value = "tenant_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String tenantId;

  @TableField(value = "task_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String taskId;

  @TableField(value = "event_version", insertStrategy = FieldStrategy.ALWAYS)
  private final int eventVersion;

  @TableField(value = "seq", insertStrategy = FieldStrategy.ALWAYS)
  private final long seq;

  @TableField(value = "fact_key", insertStrategy = FieldStrategy.ALWAYS)
  private final String factKey;

  @TableField(value = "type", insertStrategy = FieldStrategy.ALWAYS)
  private final String type;

  @TableField(value = "occurred_at", insertStrategy = FieldStrategy.ALWAYS)
  private final java.time.LocalDateTime occurredAt;

  @TableField(value = "event_snapshot", insertStrategy = FieldStrategy.ALWAYS)
  private final String eventSnapshot;

  @TableField(value = "event_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String eventDigest;

  @TableField(value = "event_bytes", insertStrategy = FieldStrategy.ALWAYS)
  private final int eventBytes;

  @TableField(value = "source_digest", insertStrategy = FieldStrategy.ALWAYS)
  private final String sourceDigest;

  @TableField(value = "source_step_id", insertStrategy = FieldStrategy.ALWAYS)
  private final Object sourceStepId;

  @TableField(value = "source_invocation_id", insertStrategy = FieldStrategy.ALWAYS)
  private final Object sourceInvocationId;

  @TableField(value = "committed_attempt_id", insertStrategy = FieldStrategy.ALWAYS)
  private final String committedAttemptId;

  @TableField(value = "committed_fence", insertStrategy = FieldStrategy.ALWAYS)
  private final Long committedFence;

  @TableField(value = "reliable_event_seq", insertStrategy = FieldStrategy.ALWAYS)
  private final long reliableEventSeq;

  public SessionPublicEventAppendEntity(
      String tenantId,
      String taskId,
      int eventVersion,
      long seq,
      String factKey,
      String type,
      java.time.LocalDateTime occurredAt,
      String eventSnapshot,
      String eventDigest,
      int eventBytes,
      String sourceDigest,
      Object sourceStepId,
      Object sourceInvocationId,
      String committedAttemptId,
      Long committedFence,
      long reliableEventSeq) {
    this.tenantId = tenantId;
    this.taskId = taskId;
    this.eventVersion = eventVersion;
    this.seq = seq;
    this.factKey = factKey;
    this.type = type;
    this.occurredAt = occurredAt;
    this.eventSnapshot = eventSnapshot;
    this.eventDigest = eventDigest;
    this.eventBytes = eventBytes;
    this.sourceDigest = sourceDigest;
    this.sourceStepId = sourceStepId;
    this.sourceInvocationId = sourceInvocationId;
    this.committedAttemptId = committedAttemptId;
    this.committedFence = committedFence;
    this.reliableEventSeq = reliableEventSeq;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getTaskId() {
    return taskId;
  }

  public int getEventVersion() {
    return eventVersion;
  }

  public long getSeq() {
    return seq;
  }

  public String getFactKey() {
    return factKey;
  }

  public String getType() {
    return type;
  }

  public java.time.LocalDateTime getOccurredAt() {
    return occurredAt;
  }

  public String getEventSnapshot() {
    return eventSnapshot;
  }

  public String getEventDigest() {
    return eventDigest;
  }

  public int getEventBytes() {
    return eventBytes;
  }

  public String getSourceDigest() {
    return sourceDigest;
  }

  public Object getSourceStepId() {
    return sourceStepId;
  }

  public Object getSourceInvocationId() {
    return sourceInvocationId;
  }

  public String getCommittedAttemptId() {
    return committedAttemptId;
  }

  public Long getCommittedFence() {
    return committedFence;
  }

  public long getReliableEventSeq() {
    return reliableEventSeq;
  }
}
