package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** scope查询的具名只读投影；复合键不伪造成单列主键。 */
@TableName("invocation_session_scope_query")
public final class ScopeQueryBindingEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  @TableField("invocation_id")
  private Object invocationId;

  public Object getInvocationId() {
    return invocationId;
  }

  @TableField("caller_task_id")
  private Object callerTaskId;

  public Object getCallerTaskId() {
    return callerTaskId;
  }

  @TableField("step_id")
  private Object stepId;

  public Object getStepId() {
    return stepId;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  @TableField("operation")
  private Object operation;

  public Object getOperation() {
    return operation;
  }

  @TableField("binding_digest")
  private Object bindingDigest;

  public Object getBindingDigest() {
    return bindingDigest;
  }

  @TableField("input_digest")
  private Object inputDigest;

  public Object getInputDigest() {
    return inputDigest;
  }

  @TableField("authority_digest")
  private Object authorityDigest;

  public Object getAuthorityDigest() {
    return authorityDigest;
  }

  @TableField("context_digest")
  private Object contextDigest;

  public Object getContextDigest() {
    return contextDigest;
  }

  @TableField("normalized_snapshot")
  private Object normalizedSnapshot;

  public Object getNormalizedSnapshot() {
    return normalizedSnapshot;
  }

  @TableField("normalized_digest")
  private Object normalizedDigest;

  public Object getNormalizedDigest() {
    return normalizedDigest;
  }

  @TableField("normalized_bytes")
  private Object normalizedBytes;

  public Object getNormalizedBytes() {
    return normalizedBytes;
  }

  @TableField("content_state")
  private Object contentState;

  public Object getContentState() {
    return contentState;
  }

  @TableField("outcome")
  private Object outcome;

  public Object getOutcome() {
    return outcome;
  }

  @TableField("failure_code")
  private Object failureCode;

  public Object getFailureCode() {
    return failureCode;
  }

  @TableField("output_digest")
  private Object outputDigest;

  public Object getOutputDigest() {
    return outputDigest;
  }

  @TableField("output_bytes")
  private Object outputBytes;

  public Object getOutputBytes() {
    return outputBytes;
  }

  @TableField("receipt_event_seq")
  private Object receiptEventSeq;

  public Object getReceiptEventSeq() {
    return receiptEventSeq;
  }

  @TableField("native_read_seq")
  private Object nativeReadSeq;

  public Object getNativeReadSeq() {
    return nativeReadSeq;
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
}
