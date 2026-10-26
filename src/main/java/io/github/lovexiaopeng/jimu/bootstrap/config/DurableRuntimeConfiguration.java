package io.github.lovexiaopeng.jimu.bootstrap.config;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.LedgerProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeRunner;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.PublicWebReader;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.WorkerLoop;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Shared tool capabilities and worker execution; control also needs MCP/native availability. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
public class DurableRuntimeConfiguration {
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
}
