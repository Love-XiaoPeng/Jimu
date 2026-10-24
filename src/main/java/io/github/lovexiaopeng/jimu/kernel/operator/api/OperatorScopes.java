package io.github.lovexiaopeng.jimu.kernel.operator.api;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 运维输入的纯Java值；JSON和冻结结构校验由显式编码端口完成。 */
public final class OperatorScopes {
  public record Requirement(
      int version,
      String issueId,
      String instanceUuid,
      String schema,
      String tenantId,
      String runId,
      String approvalDigest) {}

  public record Pin(int configVersion, boolean stream, String modelSnapshotDigest) {}

  public record Scope(
      int version,
      String scopeId,
      String issueId,
      String instanceUuid,
      String schema,
      String tenantId,
      String runId,
      String approvalDigest,
      String planDigest,
      String sourcePlanSha256,
      String materialsDigest,
      String accountEvidenceDigest,
      String allowedModel,
      String allowedRevision,
      Map<String, Pin> profilePins,
      Map<String, String> sampleProfiles,
      boolean localOnly) {
    public Scope {
      if (profilePins != null)
        profilePins = Collections.unmodifiableMap(new TreeMap<>(profilePins));
      if (sampleProfiles != null)
        sampleProfiles = Collections.unmodifiableMap(new TreeMap<>(sampleProfiles));
    }
  }

  /** Console facts, never a credential. Local evidence cannot bind a cloud profile. */
  public record AccountEvidence(
      String region,
      String billingScope,
      String apiHost,
      boolean exactModelAuthorized,
      boolean egressVerified,
      String evidenceDigest) {}

  private OperatorScopes() {}
}
