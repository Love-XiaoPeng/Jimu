package io.github.lovexiaopeng.jimu.bootstrap;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.ToolDefinitionMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.HttpResourceProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.ConfiguredOriginReader;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.kernel.plugin.api.PluginCatalog;
import io.github.lovexiaopeng.jimu.kernel.plugin.application.PluginRegistry;
import io.github.lovexiaopeng.jimu.kernel.readiness.api.Readiness;
import io.github.lovexiaopeng.jimu.kernel.readiness.application.ReadinessService;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean
  ToolDescriptor descriptor(Environment env) throws IOException {
    var loader = new BuiltinManifestLoader();
    ToolDescriptor descriptor =
        (!env.getProperty("jimu.durable.enabled", Boolean.class, false)
                || FetchRuntime.fixtureMode(env))
            ? loader.load()
            : loader.loadPublic();
    new ToolDefinitionMapper()
        .map(
            descriptor,
            new String(
                BuiltinManifestLoader.resource("input.schema.json"), StandardCharsets.UTF_8));
    return descriptor;
  }

  @Bean
  PluginCatalog pluginCatalog(ToolDescriptor descriptor) {
    return new PluginRegistry(List.of(descriptor));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "jimu.durable.enabled",
      havingValue = "false",
      matchIfMissing = true)
  HttpResourceProbe resourceProbe(Environment environment) {
    String role = environment.getRequiredProperty("jimu.role");
    return switch (role) {
      case "worker" ->
          new HttpResourceProbe(
              origin(environment.getRequiredProperty("jimu.provider-origin")), false);
      case "control" ->
          new HttpResourceProbe(
              origin(environment.getRequiredProperty("jimu.worker-origin")).resolve("/readyz"),
              true);
      default -> throw new IllegalArgumentException("jimu.role must be control or worker");
    };
  }

  @Bean
  Readiness readiness(Environment environment, PluginCatalog catalog, ResourceProbe probe) {
    return new ReadinessService(environment.getRequiredProperty("jimu.role"), catalog, probe);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnExpression("'${jimu.role:}' == 'worker' && !${jimu.durable.enabled:false}")
  ConfiguredOriginReader reader(Environment environment) {
    return new ConfiguredOriginReader(
        origin(environment.getRequiredProperty("jimu.provider-origin")));
  }

  @Bean
  @ConditionalOnExpression("'${jimu.role:}' == 'worker' && !${jimu.durable.enabled:false}")
  WebFetchPlugin webFetchPlugin(ConfiguredOriginReader reader, ToolDescriptor descriptor) {
    return new WebFetchPlugin(reader, descriptor);
  }

  private static URI origin(String value) {
    URI uri = URI.create(value);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("Required resource must be an HTTP(S) origin");
    }
    return uri;
  }
}
