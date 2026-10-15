package io.github.lovexiaopeng.jimu.bootstrap;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger;
import io.github.lovexiaopeng.jimu.adapters.runtime.events.DatabaseEventFeed;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.HttpResourceProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.LedgerProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.identity.SignedIdentity;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LedgerMaintenance;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LocalWorkerSupervisor;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.WorkerLoop;
import io.github.lovexiaopeng.jimu.adapters.storage.LocalArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableConfiguration {
  @Bean
  DataSource ledgerDataSource(Environment env) {
    return new DriverManagerDataSource(
        env.getRequiredProperty("jimu.db.url"),
        env.getRequiredProperty("jimu.db.user"),
        env.getRequiredProperty("jimu.db.password"));
  }

  @Bean
  JdbcLedger ledger(DataSource source, Environment env) {
    if ("control".equals(env.getRequiredProperty("jimu.role"))) {
      Flyway.configure()
          .dataSource(
              env.getRequiredProperty("jimu.db.url"),
              env.getRequiredProperty("jimu.db.migration-user"),
              env.getRequiredProperty("jimu.db.migration-password"))
          .cleanDisabled(true)
          .load()
          .migrate();
    }
    Flyway.configure().dataSource(source).cleanDisabled(true).load().validate();
    var ledger = new JdbcLedger(source);
    ledger.check();
    return ledger;
  }

  @Bean
  ArtifactStore artifactStore(Environment env) throws IOException {
    return new LocalArtifactStore(Path.of(env.getRequiredProperty("jimu.storage-root")));
  }

  @Bean
  Authenticator authenticator(Environment env) {
    var subjects = new HashMap<String, Identity>();
    for (String entry : env.getRequiredProperty("jimu.identity.subjects").split(";")) {
      String[] parts = entry.split(":");
      if (parts.length != 3
          || !parts[1].matches("[A-Za-z0-9_-]{1,64}")
          || !parts[2].matches("[A-Za-z0-9_-]{1,64}"))
        throw new IllegalArgumentException("Invalid principal mapping");
      subjects.put(parts[0], new Identity(parts[1], parts[2]));
    }
    return new SignedIdentity(env.getRequiredProperty("jimu.identity.key"), subjects);
  }

  @Bean(destroyMethod = "close")
  LedgerProbe durableReadiness(ExecutionLedger ledger, ArtifactStore store, Environment env) {
    return new LedgerProbe(
        ledger,
        store,
        "worker".equals(env.getRequiredProperty("jimu.role"))
            ? new HttpResourceProbe(
                URI.create(env.getRequiredProperty("jimu.provider-origin")), false)
            : null);
  }

  @Bean
  BoundaryProbe boundaryProbe(Environment env) {
    String boundary = env.getProperty("jimu.test.boundary", "");
    if (!boundary.isEmpty() && !env.getProperty("jimu.test-mode", Boolean.class, false))
      throw new IllegalArgumentException("Fault injection requires test assembly");
    return new BoundaryProbe(boundary, Path.of(env.getProperty("jimu.test.barrier", ".")));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "worker")
  DurableAgent durableAgent(
      ExecutionLedger ledger,
      ArtifactStore store,
      WebFetchPlugin plugin,
      BoundaryProbe probe,
      Environment env)
      throws IOException {
    var credentials = new HashMap<String, String>();
    for (String entry : env.getRequiredProperty("jimu.fixture.credentials").split(";")) {
      String[] pair = entry.split(":", 2);
      credentials.put(pair[0], pair[1]);
    }
    return new DurableAgent(
        ledger,
        store,
        plugin,
        probe,
        URI.create(env.getRequiredProperty("jimu.provider-origin")),
        credentials,
        env.getProperty("jimu.model-stream", Boolean.class, false));
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "worker")
  WorkerLoop workerLoop(
      ExecutionLedger ledger, DurableAgent agent, Environment env, LedgerProbe resources) {
    return new WorkerLoop(
        ledger,
        agent,
        java.util.UUID.fromString(env.getRequiredProperty("jimu.host-id")).toString(),
        resources);
  }

  @Bean(destroyMethod = "close")
  DatabaseEventFeed eventFeed(Tasks tasks, Authenticator auth) {
    return new DatabaseEventFeed(tasks, auth);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "control")
  LedgerMaintenance maintenance(ExecutionLedger ledger, ArtifactStore store) {
    return new LedgerMaintenance(ledger, store);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "control")
  LocalWorkerSupervisor supervisor(ExecutionLedger ledger, Environment env) {
    return new LocalWorkerSupervisor(
        ledger, java.util.UUID.fromString(env.getRequiredProperty("jimu.host-id")).toString());
  }
}
