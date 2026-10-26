package io.github.lovexiaopeng.jimu.bootstrap;

import io.github.lovexiaopeng.jimu.bootstrap.config.DurableControlConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableEndpointConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableLedgerConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableRuntimeConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Durable assembly entry point, also usable without the application component scan. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
@Import({
  DurableLedgerConfiguration.class,
  DurableEndpointConfiguration.class,
  DurableRuntimeConfiguration.class,
  DurableControlConfiguration.class
})
public class DurableConfiguration {}
