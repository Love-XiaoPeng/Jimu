package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;

/** Nonsecret, operator-owned authority. Never accepted from a task request. */
public final class BusinessPolicies {
  private BusinessPolicies() {}

  public static final String STRATEGY = "deepseek-nonthinking-v1";

  public record Policy(
      int version,
      String id,
      String mode,
      ExecutionScopes.Requirement anchor,
      String instructionDigest,
      Map<String, String> profileDigests,
      String requestStrategy,
      boolean localOnly,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String reportMode) {
    public Policy(
        int version,
        String id,
        String mode,
        ExecutionScopes.Requirement anchor,
        String instructionDigest,
        Map<String, String> profileDigests,
        String requestStrategy,
        boolean localOnly) {
      this(
          version,
          id,
          mode,
          anchor,
          instructionDigest,
          profileDigests,
          requestStrategy,
          localOnly,
          null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Policy {
      if (profileDigests != null)
        profileDigests =
            java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(profileDigests));
    }
  }

  public record Settings(String currentRef, Map<String, Policy> policies) {}

  public static void validate(Policy p) {
    if (p == null
        || !(p.version() == 1 && p.reportMode() == null && STRATEGY.equals(p.requestStrategy())
            || p.version() == 2
                && "task-image-v1".equals(p.reportMode())
                && ModelImages.DEEPSEEK.equals(p.requestStrategy()))
        || p.id() == null
        || !p.id().matches("[a-zA-Z0-9_-]{1,80}")
        || !"BUSINESS".equals(p.mode())
        || p.anchor() == null
        || p.anchor().version() != 1
        || p.anchor().tenantId() == null
        || p.anchor().tenantId().isBlank()
        || p.anchor().runId() == null
        || p.anchor().runId().isBlank()
        || p.anchor().instanceUuid() == null
        || p.anchor().instanceUuid().isBlank()
        || p.anchor().schema() == null
        || p.anchor().schema().isBlank()
        || p.anchor().issueId() == null
        || p.anchor().issueId().isBlank()
        || !ExecutionScopes.digest(p.anchor().approvalDigest())
        || !ExecutionScopes.digest(p.instructionDigest())
        || p.profileDigests() == null
        || p.profileDigests().isEmpty()
        || !p.profileDigests().values().stream().allMatch(ExecutionScopes::digest))
      throw new IllegalArgumentException("Invalid business policy");
  }

  public static void profile(Policy p, ModelSnapshot model, boolean local) {
    if (!p.requestStrategy().equals(model.adapter())
        || !ModelProfiles.digest(model).equals(p.profileDigests().get(model.profile()))
        || !model.paid()
        || p.localOnly() != local) throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
  }

  public static String canonical(Policy policy) {
    return Payloads.json(policy);
  }
}
