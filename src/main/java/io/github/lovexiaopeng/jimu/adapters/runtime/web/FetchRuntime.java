package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.core.env.Environment;

/** Selects reviewed execution policy; the model cannot select transport or supply credentials. */
public final class FetchRuntime {
  private final ToolDescriptor descriptor;
  private final URI fixtureOrigin;
  private final Map<String, String> credentials;
  private final WebPageReader publicReader;
  private final PublicWebPolicy policy;
  private final boolean enabled;

  public FetchRuntime(ToolDescriptor descriptor, URI origin, Map<String, String> credentials) {
    if (!"0.2.0".equals(descriptor.version()))
      throw new IllegalArgumentException("Fixture runtime requires fixture descriptor");
    this.descriptor = descriptor;
    fixtureOrigin = origin;
    this.credentials = Map.copyOf(credentials);
    publicReader = null;
    policy = null;
    enabled = !credentials.isEmpty();
  }

  public FetchRuntime(
      ToolDescriptor descriptor, Set<String> domains, WebPageReader reader, boolean enabled) {
    if (!"0.3.0".equals(descriptor.version()))
      throw new IllegalArgumentException("Public runtime requires public descriptor");
    this.descriptor = descriptor;
    fixtureOrigin = null;
    credentials = Map.of();
    publicReader = reader;
    policy = new PublicWebPolicy(domains);
    this.enabled = enabled;
  }

  public static boolean fixtureMode(Environment env) {
    boolean test = env.getProperty("jimu.test-mode", Boolean.class, false);
    String mode = env.getProperty("jimu.web.mode", test ? "fixture" : "public");
    if (!mode.equals("public") && !(mode.equals("fixture") && test))
      throw new IllegalArgumentException("Web mode requires public or explicit test fixture");
    return mode.equals("fixture");
  }

  public static Set<String> domains(Environment env) {
    return Set.of(env.getProperty("jimu.web.allowed-domains", String[].class, new String[0]));
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public boolean publicMode() {
    return publicReader != null;
  }

  public void available() {
    if (!enabled) throw new TaskFailure(503, TaskErrorCode.PUBLIC_WEB_NOT_CONFIGURED);
  }

  public void validate(FetchInput input) {
    if (publicReader == null) ConfiguredOriginReader.checkedTarget(fixtureOrigin, input.url());
    else policy.target(input.url());
  }

  public FetchOutput execute(
      FetchInput input, String tenant, String effectId, ExecutionContext context) {
    return execute(input, tenant, effectId, context, observation -> {});
  }

  public FetchOutput execute(
      FetchInput input,
      String tenant,
      String effectId,
      ExecutionContext context,
      Consumer<WebFetchObservation> observer) {
    available();
    if (publicReader instanceof PublicWebReader reader)
      return new WebFetchPlugin((url, token) -> reader.fetch(url, token, observer), descriptor)
          .execute(input, context)
          .output();
    if (publicReader != null)
      return new WebFetchPlugin(publicReader, descriptor).execute(input, context).output();
    if (!credentials.containsKey(tenant)) throw new TaskFailure(403, CommonErrorCode.ACCESS_DENIED);
    try (var reader =
        new ConfiguredOriginReader(fixtureOrigin, credentials.get(tenant), effectId)) {
      return new WebFetchPlugin(reader, descriptor).execute(input, context).output();
    }
  }
}
