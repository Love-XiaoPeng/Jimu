package io.github.lovexiaopeng.jimu.bootstrap;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.events.DatabaseEventFeed;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.HttpResourceProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.LedgerProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.identity.SignedIdentity;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeRunner;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.PublicWebReader;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LedgerMaintenance;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.LocalWorkerSupervisor;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.WorkerLoop;
import io.github.lovexiaopeng.jimu.adapters.storage.LocalArtifactStore;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableConfiguration {
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter>
      sessionSearchBodyFilter() {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter());
    registration.setAsyncSupported(true);
    registration.addUrlPatterns("/api/tasks/*");
    return registration;
  }

  @Bean
  ModelProfiles modelProfiles(Environment env) {
    return new ModelProfiles(env);
  }

  @Bean
  DataSource ledgerDataSource(Environment env) {
    return new DriverManagerDataSource(
        env.getRequiredProperty("jimu.db.url"),
        env.getRequiredProperty("jimu.db.user"),
        env.getRequiredProperty("jimu.db.password"));
  }

  @Bean
  JdbcLedger ledger(
      DataSource source,
      Environment env,
      ModelProfiles models,
      NativeSearchRuntime searches,
      io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings mcpSettings,
      @Qualifier("imageStore") java.util.Optional<ArtifactStore> images)
      throws IOException {
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
    var ledger = new JdbcLedger(source, models);
    ledger.nativeSearchAvailability(searches::available);
    ledger.imageStorage(images.orElse(null));
    ledger.mcpSettings(mcpSettings);
    ledger.directoryEnabled(
        env.getProperty("jimu.working-directory.enabled", Boolean.class, false));
    ledger.todosEnabled(env.getProperty("jimu.todos.enabled", Boolean.class, false));
    ledger.questionsEnabled(env.getProperty("jimu.questions.enabled", Boolean.class, false));
    if (env.getProperty("jimu.workspace-root") != null)
      ledger.directoryWorkspace(
          new SecureWorkspaceStore(Path.of(env.getRequiredProperty("jimu.workspace-root")))
              .logicalDirectory());
    else if (env.getProperty("jimu.working-directory.enabled", Boolean.class, false))
      throw new IllegalStateException("Directory workspace root required");
    var sensitive = new java.util.ArrayList<String>();
    sensitive.add(env.getRequiredProperty("jimu.identity.key"));
    sensitive.add(env.getRequiredProperty("jimu.db.password"));
    sensitive.add(env.getRequiredProperty("jimu.db.migration-password"));
    if (env instanceof org.springframework.core.env.ConfigurableEnvironment configured)
      for (var propertySource : configured.getPropertySources())
        if (propertySource
            instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)
          for (String name : enumerable.getPropertyNames())
            if (name.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(password|secret|api[-_]?key|private).*")) {
              String value = env.getProperty(name);
              if (value != null && !value.isEmpty()) sensitive.add(value);
            }
    if (env.getProperty("jimu.session-event-read.enabled", Boolean.class, false))
      sensitive.addAll(models.sessionPrivateValues());
    ledger.sessionReadEnabled(
        env.getProperty("jimu.session-event-read.enabled", Boolean.class, false), sensitive);
    ledger.sessionSearchEnabled(
        env.getProperty("jimu.session-event-search.enabled", Boolean.class, false));
    ledger.check();
    return ledger;
  }

  @Bean
  @org.springframework.context.annotation.Primary
  ArtifactStore artifactStore(Environment env) throws IOException {
    return new LocalArtifactStore(Path.of(env.getRequiredProperty("jimu.storage-root")));
  }

  @Bean("imageStore")
  @ConditionalOnProperty(name = "jimu.images.enabled", havingValue = "true")
  ArtifactStore imageStore(Environment env) throws IOException {
    return new io.github.lovexiaopeng.jimu.adapters.storage.SecureImageStore(
        Path.of(env.getRequiredProperty("jimu.images.root")));
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
      ToolDescriptor descriptor,
      @Qualifier("publicWebReader") WebPageReader publicReader,
      BoundaryProbe probe,
      ModelProfiles models,
      NativeSearchRuntime searches,
      io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime mcp,
      OperationThreads operations,
      @Qualifier("imageStore") java.util.Optional<ArtifactStore> images,
      Environment env)
      throws IOException {
    var credentials = new HashMap<String, String>();
    if (env.getProperty("jimu.test-mode", Boolean.class, false))
      for (String entry : env.getRequiredProperty("jimu.fixture.credentials").split(";")) {
        String[] pair = entry.split(":", 2);
        credentials.put(pair[0], pair[1]);
      }
    FetchRuntime fetch =
        FetchRuntime.fixtureMode(env)
            ? new FetchRuntime(
                descriptor,
                URI.create(env.getRequiredProperty("jimu.provider-origin")),
                credentials)
            : new FetchRuntime(
                descriptor,
                FetchRuntime.domains(env),
                publicReader,
                env.getProperty("jimu.web.public-dispatch-enabled", Boolean.class, false));
    String workspaceRoot = env.getProperty("jimu.workspace-root");
    return new DurableAgent(
        ledger,
        store,
        fetch,
        probe,
        models,
        workspaceRoot == null
            ? null
            : new WorkspaceRuntime(
                new SecureWorkspaceStore(Path.of(workspaceRoot)),
                searches,
                new io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntime(
                    images.orElse(null), operations)),
        mcp);
  }

  @Bean
  io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings mcpSettings(Environment env) {
    return new io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings(env);
  }

  @Bean
  io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime mcpRuntime(
      io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings settings) throws IOException {
    return new io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime(settings);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "jimu.role", havingValue = "worker")
  WorkerLoop workerLoop(
      ExecutionLedger ledger,
      DurableAgent agent,
      Environment env,
      LedgerProbe resources,
      OperationThreads operations) {
    return new WorkerLoop(
        ledger,
        agent,
        java.util.UUID.fromString(env.getRequiredProperty("jimu.host-id")).toString(),
        resources,
        operations);
  }

  @Bean
  @ConditionalOnProperty(name = "jimu.role", havingValue = "worker")
  WebPageReader publicWebReader(Environment env, OperationThreads operations) {
    return new PublicWebReader(FetchRuntime.domains(env), operations);
  }

  @Bean
  OperationThreads operationThreads() {
    return new OperationThreads();
  }

  @Bean
  NativeSearchRuntime nativeSearchRuntime(Environment env, OperationThreads operations)
      throws IOException {
    if (!env.getProperty("jimu.search.enabled", Boolean.class, false))
      return new NativeSearchRuntime();
    return new NativeSearchRuntime(
        Path.of(env.getRequiredProperty("jimu.search.root")),
        new NativeRunner.Bundle(
            env.getRequiredProperty("jimu.search.launcher"),
            env.getRequiredProperty("jimu.search.launcher-sha256"),
            env.getRequiredProperty("jimu.search.rg"),
            env.getRequiredProperty("jimu.search.bubblewrap")),
        operations);
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
  LocalWorkerSupervisor supervisor(
      ExecutionLedger ledger, Environment env, NativeSearchRuntime search) {
    return new LocalWorkerSupervisor(
        ledger,
        java.util.UUID.fromString(env.getRequiredProperty("jimu.host-id")).toString(),
        search);
  }
}
