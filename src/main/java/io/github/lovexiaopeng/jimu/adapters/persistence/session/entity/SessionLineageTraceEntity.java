package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** M2原槽插入；复合键和PENDING默认值由同一账本事务固定。 */
@TableName("invocation_session_lineage_trace")
public final class SessionLineageTraceEntity {
  private final String tenantId;
  private final String invocationId;
  private final String callerTaskId;
  private final String stepId;
  private final String bindingDigest;
  private final String inputDigest;
  private final String authorityDigest;
  private final String graphDigest;
  private final String contextDigest;
  private final String normalizedSnapshot;
  private final String normalizedDigest;
  private final Long normalizedBytes;

  public SessionLineageTraceEntity(
      String tenantId,
      String invocationId,
      String callerTaskId,
      String stepId,
      String bindingDigest,
      String inputDigest,
      String authorityDigest,
      String graphDigest,
      String contextDigest,
      String normalizedSnapshot,
      String normalizedDigest,
      Long normalizedBytes) {
    this.tenantId = tenantId;
    this.invocationId = invocationId;
    this.callerTaskId = callerTaskId;
    this.stepId = stepId;
    this.bindingDigest = bindingDigest;
    this.inputDigest = inputDigest;
    this.authorityDigest = authorityDigest;
    this.graphDigest = graphDigest;
    this.contextDigest = contextDigest;
    this.normalizedSnapshot = normalizedSnapshot;
    this.normalizedDigest = normalizedDigest;
    this.normalizedBytes = normalizedBytes;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public String getCallerTaskId() {
    return callerTaskId;
  }

  public String getStepId() {
    return stepId;
  }

  public String getBindingDigest() {
    return bindingDigest;
  }

  public String getInputDigest() {
    return inputDigest;
  }

  public String getAuthorityDigest() {
    return authorityDigest;
  }

  public String getGraphDigest() {
    return graphDigest;
  }

  public String getContextDigest() {
    return contextDigest;
  }

  public String getNormalizedSnapshot() {
    return normalizedSnapshot;
  }

  public String getNormalizedDigest() {
    return normalizedDigest;
  }

  public Long getNormalizedBytes() {
    return normalizedBytes;
  }
}
