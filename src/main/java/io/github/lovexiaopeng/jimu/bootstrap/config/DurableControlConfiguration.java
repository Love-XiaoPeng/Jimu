package io.github.lovexiaopeng.jimu.bootstrap.config;

import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LedgerMaintenance;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LocalWorkerSupervisor;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Control-owned maintenance and local worker supervision lifecycles. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableControlConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "control")
  LedgerMaintenance maintenance(ExecutionLedger ledger, ArtifactStore store) {
    return new LedgerMaintenance(ledger, store);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "control")
  LocalWorkerSupervisor supervisor(
      ExecutionLedger ledger, Environment env, NativeSearchRuntime search) {
    return new LocalWorkerSupervisor(
        ledger,
        java.util.UUID.fromString(env.getRequiredProperty("jimu.host-id")).toString(),
        search);
  }
}
