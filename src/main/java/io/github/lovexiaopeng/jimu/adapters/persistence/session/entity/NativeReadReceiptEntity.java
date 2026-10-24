package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原生回执投影；复合键不伪装单列主键。 */
@TableName("invocation_session_read")
public final class NativeReadReceiptEntity {
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

  @TableField("target_seq")
  private Object targetSeq;

  public Object getTargetSeq() {
    return targetSeq;
  }

  @TableField("before_count")
  private Object beforeCount;

  public Object getBeforeCount() {
    return beforeCount;
  }

  @TableField("after_count")
  private Object afterCount;

  public Object getAfterCount() {
    return afterCount;
  }

  @TableField("upper_seq")
  private Object upperSeq;

  public Object getUpperSeq() {
    return upperSeq;
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
}
