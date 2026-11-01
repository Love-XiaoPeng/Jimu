package io.github.lovexiaopeng.jimu.bootstrap.config;

import io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerSchema;
import io.github.lovexiaopeng.jimu.adapters.runtime.identity.SignedIdentity;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.storage.LocalArtifactStore;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Ledger initialization, its storage/identity boundaries and published application services. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableLedgerConfiguration {
  @Bean
  ModelProfiles modelProfiles(Environment env) {
    return new ModelProfiles(env);
  }

  @Bean
  JdbcLedger ledger(
      DataSource source,
      DataSourceTransactionManager transactions,
      org.mybatis.spring.SqlSessionTemplate sqlSessions,
      LedgerUnitOfWork unitOfWork,
      LedgerSchema schema,
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
    var ledger =
        new JdbcLedger(source, models, transactions, unitOfWork, schema::check, sqlSessions);
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
    ledger.priorSessionSearchEnabled(
        env.getProperty("jimu.session-search.enabled", Boolean.class, false));
    ledger.sessionLineageEnabled(
        env.getProperty("jimu.session-trace.enabled", Boolean.class, false));
    ledger.sessionTraceEnabled(
        env.getProperty("jimu.session-event-trace.enabled", Boolean.class, false));
    ledger.sessionSearchEnabled(
        env.getProperty("jimu.session-event-search.enabled", Boolean.class, false));
    ledger.check();
    return ledger;
  }

  @Bean
  TaskService taskService(Tasks tasks) {
    return new TaskServiceImpl(tasks);
  }

  @Bean
  ArtifactService artifactService(JdbcLedger ledger, ArtifactStore store) {
    return new ArtifactServiceImpl(ledger.artifactAccess(), store);
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
}
