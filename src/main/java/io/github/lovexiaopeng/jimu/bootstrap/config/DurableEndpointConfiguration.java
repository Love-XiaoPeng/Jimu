package io.github.lovexiaopeng.jimu.bootstrap.config;

import io.github.lovexiaopeng.jimu.adapters.runtime.events.DatabaseEventFeed;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.HttpResourceProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.LedgerProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Durable HTTP body filtering, event subscriptions and readiness resource ownership. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableEndpointConfiguration {
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter>
      sessionSearchBodyFilter() {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter());
    registration.setAsyncSupported(true);
    registration.addUrlPatterns("/api/tasks/*", "/api/session-search/tasks");
    return registration;
  }

  @Bean(destroyMethod = "close")
  LedgerProbe durableReadiness(
      ExecutionLedger ledger, ArtifactStore store, Environment env, ModelProfiles models) {
    return new LedgerProbe(
        ledger,
        store,
        models.fixture() && "worker".equals(env.getRequiredProperty("jimu.role"))
            ? new HttpResourceProbe(
                URI.create(env.getRequiredProperty("jimu.provider-origin")), false)
            : null,
        models::ready);
  }

  @Bean(destroyMethod = "close")
  DatabaseEventFeed eventFeed(Tasks tasks, Authenticator auth) {
    return new DatabaseEventFeed(tasks, auth);
  }
}
