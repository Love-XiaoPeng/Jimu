package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Non-secret, immutable operator authority; neither task JSON nor configuration can mint a scope.
 */
public final class ExecutionScopes {
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

  private ExecutionScopes() {}

  public static boolean digest(String value) {
    return value != null && value.matches("[0-9a-f]{64}") && !value.matches("0{64}");
  }

  public static boolean valid(Scope s) {
    return s != null
        && s.version() == 1
        && s.scopeId() != null
        && !s.scopeId().isBlank()
        && s.issueId() != null
        && !s.issueId().isBlank()
        && s.instanceUuid() != null
        && !s.instanceUuid().isBlank()
        && s.schema() != null
        && !s.schema().isBlank()
        && s.tenantId() != null
        && !s.tenantId().isBlank()
        && s.runId() != null
        && !s.runId().isBlank()
        && digest(s.approvalDigest())
        && digest(s.planDigest())
        && digest(s.materialsDigest())
        && digest(s.sourcePlanSha256())
        && digest(s.accountEvidenceDigest())
        && "qwen3.7-plus-2026-05-26".equals(s.allowedModel())
        && s.allowedModel().equals(s.allowedRevision())
        && s.profilePins() != null
        && s.profilePins().size() == 2
        && s.profilePins().keySet().stream().allMatch(k -> !k.isBlank())
        && s.profilePins().values().stream()
            .allMatch(p -> p != null && p.configVersion() > 0 && digest(p.modelSnapshotDigest()))
        && s.profilePins().values().stream().filter(Pin::stream).count() == 1
        && s.sampleProfiles() != null
        && s.sampleProfiles().keySet().equals(java.util.Set.of("W1", "W2", "W3", "W4"))
        && s.sampleProfiles().values().stream().allMatch(s.profilePins()::containsKey)
        && s.sampleProfiles().entrySet().stream()
            .allMatch(
                e ->
                    s.profilePins().get(e.getValue()).stream()
                        == java.util.List.of("W2", "W4").contains(e.getKey()));
  }

  public static String canonical(Scope scope) {
    if (!valid(scope)) throw new IllegalArgumentException("Invalid execution scope");
    return Payloads.json(scope);
  }
}
