package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.Environment;

/** Operator configuration only; request JSON cannot select profiles, endpoints or secrets. */
public final class ModelProfiles {
  // Spring needs one unambiguous binding constructor. Keep its DTO outside the leaf model API,
  // whose v2 Java constructor remains source compatible and contains no Spring annotations.
  public record ProfileBinding(
      int version,
      String profile,
      int configVersion,
      String adapter,
      String baseUrl,
      String model,
      String revision,
      String authMode,
      String credentialRef,
      int credentialVersion,
      String transportPolicy,
      int connectTimeoutMs,
      int requestTimeoutMs,
      int responseBytes,
      boolean stream,
      int maxOutputTokens,
      String outputLimitField,
      int outputMarginTokens,
      ModelSnapshot.Capabilities capabilities,
      Map<String, Boolean> providerOptions,
      boolean paid,
      ModelSnapshot.Price price,
      ModelSnapshot.InputLimit inputLimit,
      ModelSnapshot.ImageInput imageInput) {}

  public record SettingsBinding(
      String defaultProfileRef,
      Map<String, String> tenantProfiles,
      Map<String, ProfileBinding> profiles,
      List<String> allowedOrigins,
      boolean paidDispatchEnabled,
      String evaluationRunId,
      String evaluationPlanRef,
      String evaluationPhase,
      String evaluationSample) {}

  public record Settings(
      String defaultProfileRef,
      Map<String, String> tenantProfiles,
      Map<String, ModelSnapshot> profiles,
      List<String> allowedOrigins,
      boolean paidDispatchEnabled,
      String evaluationRunId,
      String evaluationPlanRef,
      String evaluationPhase,
      String evaluationSample) {}

  private final Environment env;
  private final Settings settings;
  private final boolean fixture;
  private final BusinessPolicies.Settings business;

  public ModelProfiles(Environment env) {
    this.env = env;
    business =
        Binder.get(env)
            .bind(
                "jimu.business",
                Bindable.of(BusinessPolicies.Settings.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
            .orElse(null);
    if (business != null) {
      if (business.policies() == null || !business.policies().containsKey(business.currentRef()))
        throw new IllegalArgumentException("Business policy versions required");
      business
          .policies()
          .forEach(
              (id, policy) -> {
                BusinessPolicies.validate(policy);
                if (!id.equals(policy.id()))
                  throw new IllegalArgumentException("Business policy identity mismatch");
              });
    }
    var bound =
        Binder.get(env)
            .bind(
                "jimu.model",
                Bindable.of(SettingsBinding.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
            .orElse(null);
    if (bound == null) settings = null;
    else {
      Map<String, ModelSnapshot> profiles =
          bound.profiles() == null
              ? null
              : bound.profiles().entrySet().stream()
                  .collect(
                      java.util.stream.Collectors.toMap(
                          Map.Entry::getKey,
                          e -> Payloads.read(Payloads.json(e.getValue()), ModelSnapshot.class)));
      settings =
          new Settings(
              bound.defaultProfileRef(),
              bound.tenantProfiles(),
              profiles,
              bound.allowedOrigins(),
              bound.paidDispatchEnabled(),
              bound.evaluationRunId(),
              bound.evaluationPlanRef(),
              bound.evaluationPhase(),
              bound.evaluationSample());
    }
    fixture = settings == null;
    if (fixture && !env.getProperty("jimu.test-mode", Boolean.class, false))
      throw new IllegalArgumentException("Explicit model profiles required outside test assembly");
    if (!fixture) {
      if (settings.profiles() == null || settings.allowedOrigins() == null)
        throw new IllegalArgumentException("Model profiles and allowed origins required");
      settings
          .profiles()
          .forEach(
              (id, profile) -> {
                if (!id.equals(profile.profile()))
                  throw new IllegalArgumentException("Profile identity mismatch");
                validate(profile);
              });
      selected("__default__");
    }
  }

  public boolean fixture() {
    return fixture;
  }

  public BusinessPolicies.Policy businessPolicy() {
    return business == null ? null : business.policies().get(business.currentRef());
  }

  public void retainedBusiness(BusinessPolicies.Policy policy) {
    if (business == null || !business.policies().containsKey(policy.id()))
      throw new TaskFailure(503, ModelErrorCode.BUSINESS_POLICY_UNAVAILABLE);
    if (!policy.equals(business.policies().get(policy.id())))
      throw new TaskFailure(409, ModelErrorCode.BUSINESS_POLICY_CONFLICT);
  }

  private boolean businessProfile(ModelSnapshot snapshot) {
    return business != null
        && business.policies().values().stream()
            .anyMatch(
                p ->
                    ModelProfiles.digest(snapshot)
                            .equals(p.profileDigests().get(snapshot.profile()))
                        && snapshot.adapter().equals(p.requestStrategy()));
  }

  public ExecutionScopes.Requirement scopeRequirement() {
    String json = env.getProperty("jimu.execution-scope.requirement");
    if (json == null) return null;
    try {
      return Payloads.read(json, ExecutionScopes.Requirement.class);
    } catch (IllegalArgumentException invalid) {
      throw new TaskFailure(503, ModelErrorCode.EVALUATION_SCOPE_UNAVAILABLE);
    }
  }

  public void requireDirectory(ModelSnapshot model) {
    if (fixture
        || model.paid()
        || !"false".equals(env.getProperty("jimu.model.profiles." + model.profile() + ".paid"))
        || !localEvaluation(model)
        || businessPolicy() != null
        || evaluationRunId() != null
        || scopeRequirement() != null)
      throw new TaskFailure(403, DirectoryErrorCode.DIRECTORY_NOT_AUTHORIZED);
    if (model.maxOutputTokens() > 4096
        || model.responseBytes() > 65536
        || model.inputLimit() == null
        || model.inputLimit().maxTokens() > 65536
        || !"test-whole-request-utf8-v1".equals(model.inputLimit().proof()))
      throw new TaskFailure(403, DirectoryErrorCode.DIRECTORY_NOT_AUTHORIZED);
  }

  public void requireSessionTrace(ModelSnapshot model) {
    try {
      requireSessionRead(model);
    } catch (TaskFailure failure) {
      throw new TaskFailure(403, SessionErrorCode.SESSION_TRACE_DISABLED);
    }
  }

  public void requireSessionLineage(ModelSnapshot model) {
    try {
      requireSessionRead(model);
    } catch (TaskFailure failure) {
      throw new TaskFailure(403, SessionErrorCode.SESSION_LINEAGE_DISABLED);
    }
  }

  public void requireSessionSearch(ModelSnapshot model) {
    try {
      requireSessionRead(model);
    } catch (TaskFailure rejected) {
      throw new TaskFailure(403, SessionErrorCode.SESSION_SEARCH_DISABLED);
    }
  }

  public void requireSessionRead(ModelSnapshot model) {
    if (fixture
        || model.paid()
        || !"false".equals(env.getProperty("jimu.model.profiles." + model.profile() + ".paid"))
        || !localEvaluation(model)
        || businessPolicy() != null
        || evaluationRunId() != null
        || scopeRequirement() != null
        || model.responseBytes() > 16384
        || model.maxOutputTokens() > 4096
        || model.inputLimit() == null
        || model.inputLimit().maxTokens() > 65536
        || !"test-whole-request-utf8-v1".equals(model.inputLimit().proof()))
      throw new TaskFailure(403, SessionErrorCode.SESSION_READ_DISABLED);
  }

  public List<String> sessionPrivateValues() {
    if (fixture) return List.of();
    var values = new java.util.ArrayList<String>();
    for (var profile : settings.profiles().values())
      if (localEvaluation(profile)) {
        String value = credential(profile);
        if (value != null) values.add(value);
      }
    return List.copyOf(values);
  }

  public void requireTodo(ModelSnapshot model) {
    if (fixture
        || model.paid()
        || !"false".equals(env.getProperty("jimu.model.profiles." + model.profile() + ".paid"))
        || !localEvaluation(model)
        || businessPolicy() != null
        || evaluationRunId() != null
        || scopeRequirement() != null)
      throw new TaskFailure(403, TodoErrorCode.TODO_NOT_AUTHORIZED);
    if (model.maxOutputTokens() > 4096
        || model.responseBytes() > 65536
        || model.inputLimit() == null
        || model.inputLimit().maxTokens() > 65536
        || !"test-whole-request-utf8-v1".equals(model.inputLimit().proof()))
      throw new TaskFailure(403, TodoErrorCode.TODO_NOT_AUTHORIZED);
  }

  public boolean localEvaluation(ModelSnapshot model) {
    return env.getProperty("jimu.test-mode", Boolean.class, false)
        && "trusted-local-v1".equals(model.transportPolicy())
        && List.of("127.0.0.1", "[::1]").contains(URI.create(model.baseUrl()).getHost());
  }

  public String evaluationRunId() {
    return fixture ? null : settings.evaluationRunId();
  }

  public EvaluationPlan.Selection evaluationSelection() {
    if (fixture || settings.evaluationPlanRef() == null) return null;
    return EvaluationPlans.selection(
        settings.evaluationPlanRef(), settings.evaluationPhase(), settings.evaluationSample());
  }

  public void checkEvaluationModel(EvaluationPlan.Plan plan, ModelSnapshot snapshot) {
    EvaluationPlans.validateModel(
        plan, snapshot, env.getProperty("jimu.test-mode", Boolean.class, false));
  }

  public boolean ready() {
    try {
      if (!fixture) {
        var refs = new java.util.HashSet<String>();
        refs.add(settings.defaultProfileRef());
        if (settings.tenantProfiles() != null) refs.addAll(settings.tenantProfiles().values());
        for (String ref : refs) {
          var profile = settings.profiles().get(ref);
          available(profile);
          credential(profile);
        }
      }
      return true;
    } catch (TaskFailure failure) {
      return false;
    }
  }

  public ModelSnapshot selected(String tenant) {
    if (fixture) {
      return new ModelSnapshot(
          2,
          "p2-fixture",
          1,
          "openai-chat-v1",
          env.getRequiredProperty("jimu.provider-origin").replaceAll("/+$", "") + "/v1",
          "fixture-v1",
          "fixture-v1",
          "bearer",
          "fixture:" + tenant,
          1,
          "fixture",
          2000,
          20000,
          1048576,
          env.getProperty("jimu.model-stream", Boolean.class, false),
          2048,
          "max_tokens",
          0,
          new ModelSnapshot.Capabilities(
              true, true, true, true, true, true, false, 65536, "p2-fixture-v1"),
          Map.of(),
          false,
          null,
          null);
    }
    String ref =
        settings.tenantProfiles() == null
            ? settings.defaultProfileRef()
            : settings.tenantProfiles().getOrDefault(tenant, settings.defaultProfileRef());
    var profile = settings.profiles().get(ref);
    if (profile == null) throw new TaskFailure(503, ModelErrorCode.MODEL_CONFIG_UNAVAILABLE);
    return profile;
  }

  public void available(ModelSnapshot snapshot) {
    if (snapshot == null
        || !(snapshot.version() == 2
                && snapshot.imageInput() == null
                && List.of("openai-chat-v1", BusinessPolicies.STRATEGY).contains(snapshot.adapter())
            || snapshot.version() == 3 && ModelImages.enabled(snapshot)))
      throw new TaskFailure(503, ModelErrorCode.MODEL_CONFIG_UNAVAILABLE);
    if (fixture) {
      if (!"p2-fixture".equals(snapshot.profile()))
        throw new TaskFailure(503, ModelErrorCode.MODEL_CONFIG_UNAVAILABLE);
    } else {
      var retained = settings.profiles().get(snapshot.profile());
      if (!snapshot.equals(retained))
        throw new TaskFailure(503, ModelErrorCode.MODEL_CONFIG_UNAVAILABLE);
      validate(snapshot);
    }
  }

  public void admit(ModelSnapshot snapshot) {
    available(snapshot);
    if (snapshot.paid()
        && (!settings.paidDispatchEnabled()
            || snapshot.price() == null
            || evaluationRunId() == null
            || evaluationRunId().isBlank()))
      throw new TaskFailure(503, ModelErrorCode.MODEL_COST_BOUND_UNAVAILABLE);
    var selection = evaluationSelection();
    if (!ModelImages.deepseek(snapshot.adapter())
        && selection != null
        && !(env.getProperty("jimu.test-mode", Boolean.class, false)
            && "trusted-local-v1".equals(snapshot.transportPolicy())
            && List.of("127.0.0.1", "[::1]").contains(URI.create(snapshot.baseUrl()).getHost()))
        && (!selection.plan().enabled() || !selection.plan().paidDispatchAuthorized()))
      throw new TaskFailure(503, ModelErrorCode.MODEL_EVALUATION_CLOSED);
    credential(snapshot);
  }

  public String credential(ModelSnapshot snapshot) {
    if ("none".equals(snapshot.authMode())) return null;
    String ref = snapshot.credentialRef();
    if (fixture && ref.startsWith("fixture:")) {
      String tenant = ref.substring(8);
      for (String entry : env.getRequiredProperty("jimu.fixture.credentials").split(";")) {
        String[] pair = entry.split(":", 2);
        if (pair[0].equals(tenant)) return pair[1];
      }
    } else if (ref.startsWith("env:")) {
      // Read only the process environment, never arbitrary Spring property sources or CLI values.
      String value = System.getenv(ref.substring(4));
      if (value != null && value.matches("[!-~]{1,4096}")) return value;
    }
    throw new TaskFailure(503, TaskErrorCode.CREDENTIAL_UNAVAILABLE);
  }

  public void checkInput(ModelSnapshot snapshot, String body) {
    if (snapshot.version() == 3) ModelImages.body(snapshot, body);
    ModelInputBounds.check(snapshot, body, env.getProperty("jimu.test-mode", Boolean.class, false));
  }

  private void validate(ModelSnapshot p) {
    var selection = evaluationSelection();
    validateStructure(
        selection == null ? null : selection.plan(),
        p,
        env.getProperty("jimu.test-mode", Boolean.class, false),
        businessProfile(p));
    var uri = URI.create(p.baseUrl());
    if (!settings.allowedOrigins().contains(uri.getScheme() + "://" + uri.getRawAuthority()))
      throw new IllegalArgumentException("Model origin not allowed");
  }

  /** Pure validation shared with operator binding; never resolves secrets or enables dispatch. */
  public static void validateStructure(
      EvaluationPlan.Plan plan, ModelSnapshot p, boolean testMode) {
    validateStructure(plan, p, testMode, false);
  }

  private static void validateStructure(
      EvaluationPlan.Plan plan, ModelSnapshot p, boolean testMode, boolean business) {
    if (p == null || p.baseUrl() == null || p.profile() == null || p.profile().isBlank())
      throw new IllegalArgumentException("Invalid model profile");
    URI uri;
    try {
      uri = URI.create(p.baseUrl());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid model base URL");
    }
    if (!(p.version() == 2
                && p.imageInput() == null
                && ("openai-chat-v1".equals(p.adapter())
                    || business && BusinessPolicies.STRATEGY.equals(p.adapter()))
            || p.version() == 3
                && ModelImages.enabled(p)
                && (ModelImages.OPENAI.equals(p.adapter())
                    || business && ModelImages.DEEPSEEK.equals(p.adapter())))
        || p.configVersion() < 1
        || p.model() == null
        || p.model().isBlank()
        || p.model().length() > 160
        || p.revision() == null
        || p.revision().isBlank()
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath().endsWith("/")
        || uri.getPath().contains("..")
        || uri.getPath().endsWith("/chat/completions")
        || uri.getRawPath().contains("%")
        || "cloud-https-v1".equals(p.transportPolicy()) && !p.paid()
        || !("cloud-https-v1".equals(p.transportPolicy()) && "https".equals(uri.getScheme())
            || "trusted-local-v1".equals(p.transportPolicy())
                && List.of("http", "https").contains(uri.getScheme()))
        || p.connectTimeoutMs() < 1
        || p.connectTimeoutMs() > 20000
        || p.requestTimeoutMs() < 1
        || p.requestTimeoutMs() > 180000
        || p.responseBytes() < 1
        || p.responseBytes() > 1048576
        || p.maxOutputTokens() < 1
        || p.maxOutputTokens() > (p.paid() ? (plan == null && !business ? 512 : 4096) : 2048)
        || p.outputMarginTokens() < 0
        || p.outputMarginTokens() >= p.maxOutputTokens()
        || p.outputLimitField() == null
        || !List.of("max_tokens", "max_completion_tokens").contains(p.outputLimitField()))
      throw new IllegalArgumentException("Invalid model profile");
    if (!("none".equals(p.authMode()) && p.credentialRef() == null && p.credentialVersion() == 0
        || "bearer".equals(p.authMode())
            && p.credentialVersion() > 0
            && p.credentialRef() != null
            && p.credentialRef().matches("env:[A-Z][A-Z0-9_]*_V[0-9]+")))
      throw new IllegalArgumentException("Invalid model credential reference");
    var c = p.capabilities();
    if (c == null
        || !c.call()
        || !c.tools()
        || c.contextLimit() < p.maxOutputTokens()
        || c.evidence() == null
        || c.evidence().isBlank()
        || p.stream() && (!c.streamText() || !c.streamTools()))
      throw new TaskFailure(503, ModelErrorCode.MODEL_CAPABILITY_UNSUPPORTED);
    if (ModelImages.deepseek(p.adapter())
        && (!business
            || !p.paid()
            || !"deepseek-flash".equals(p.model())
            || !"max_tokens".equals(p.outputLimitField())
            || p.outputMarginTokens() < 10
            || !p.providerOptions().isEmpty()
            || c.parallelToolField()
            || !c.usageCall()
            || !c.usageStream()
            || !p.adapter().equals(c.evidence())
            || !(testMode
                    && "trusted-local-v1".equals(p.transportPolicy())
                    && List.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || "cloud-https-v1".equals(p.transportPolicy())
                    && "https://api.deepseek.com".equals(p.baseUrl()))))
      throw new IllegalArgumentException("Unsupported DeepSeek request strategy");
    var input = p.inputLimit();
    if (input != null
        && (input.version() != 1
            || input.maxTokens() < 1
            || input.maxTokens()
                > (testMode
                        && !p.paid()
                        && "trusted-local-v1".equals(p.transportPolicy())
                        && List.of("127.0.0.1", "[::1]").contains(uri.getHost())
                        && "local-byte-accounted-v1".equals(p.model())
                        && "local-byte-accounted-v1".equals(p.revision())
                        && "test-whole-request-utf8-v1".equals(input.proof())
                    ? 65536
                    : 8192)
            || input.proof() == null
            || input.proof().isBlank()
            || input.proof().length() > 160))
      throw new IllegalArgumentException("Invalid complete input limit");
    if (p.paid()
        && "cloud-https-v1".equals(p.transportPolicy())
        && List.of(
                "qwen3.7-plus-2026-05-26",
                "qwen3.7-flash-2026-07-15",
                "qwen3.8-max-0902",
                "deepseek-v4-pro-0813")
            .contains(p.model())
        && (c.contextLimit() != 1000000
            || !"max_completion_tokens".equals(p.outputLimitField())
            || p.outputMarginTokens() < 10
            || !p.providerOptions()
                .equals(Map.of("enable_thinking", false, "enable_search", false))))
      throw new IllegalArgumentException(
          "Bailian snapshot requires documented full context and total output bounds");
    if (!p.providerOptions().isEmpty()
        && !("modelstudio-nonthinking-v1".equals(c.evidence())
            && p.providerOptions()
                .equals(Map.of("enable_thinking", false, "enable_search", false))))
      throw new IllegalArgumentException("Unsupported provider options");
    if (!business && p.maxOutputTokens() > 512 && p.paid() && plan != null)
      EvaluationPlans.validateModel(plan, p, testMode);
    if (p.price() != null) {
      if (!p.paid()) throw new IllegalArgumentException("Price requires monetary accounting");
      var price = p.price();
      if (!"CNY".equals(price.currency())
          || price.version() == null
          || price.version().isBlank()
          || price.inputMicrosPerMillion() < 0
          || price.inputMicrosPerMillion() > 1000000000000L
          || price.outputMicrosPerMillion() < 0
          || price.outputMicrosPerMillion() > 1000000000000L
          || price.fixedMicros() < 0
          || price.fixedMicros() > 1000000
          || price.inputTokenUpper() < 1
          || !(price.inputTokenUpper() == c.contextLimit()
              || input != null && price.inputTokenUpper() == input.maxTokens())
          || price.inputTokenUpper() > 1000000000
          || price.boundEvidence() == null
          || price.boundEvidence().isBlank())
        throw new IllegalArgumentException("Invalid model price/bound profile");
    }
  }

  public static String digest(ModelSnapshot snapshot) {
    return Payloads.hash(Payloads.json(snapshot));
  }
}
