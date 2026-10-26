package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FileErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.McpErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 按任务快照和能力顺序组装工具定义；保留各工具的可用性与 pin 首错顺序。 */
final class HostToolDefinitions {
  private final ExecutionLedger ledger;
  private final FetchRuntime fetch;
  private final WorkspaceRuntime workspace;
  private final McpRuntime mcp;
  private final ToolDefinition definition;

  HostToolDefinitions(
      ExecutionLedger ledger, FetchRuntime fetch, WorkspaceRuntime workspace, McpRuntime mcp)
      throws IOException {
    this.ledger = ledger;
    this.fetch = fetch;
    this.workspace = workspace;
    this.mcp = mcp;
    definition =
        new ToolDefinitionMapper()
            .map(
                fetch.descriptor(),
                new String(
                    BuiltinManifestLoader.resource("input.schema.json"), StandardCharsets.UTF_8));
  }

  List<ToolDefinition> definitions(Lease lease) {
    var pins = ledger.toolSnapshot(lease).pins();
    var result = new ArrayList<ToolDefinition>();
    for (String name : lease.capabilities()) {
      if (io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(name)) {
        if (mcp == null) throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
        mcp.available();
        var contract = mcp.contract(name);
        var pin = pins.get(name);
        if (pin == null
            || !pin.version().equals(contract.descriptor().version())
            || !pin.digest().equals(contract.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(mcp.description(name))
                .inputSchema(contract.input())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.WORKING_DIRECTORY)) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts();
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.SESSION_SEARCH)) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts();
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.SESSION_EVENT_TRACE)) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts();
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.SESSION_EVENT_SEARCH)) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts(
                lease.capabilities().contains(ToolCapability.SESSION_EVENT_TRACE) ? 3 : 2);
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.SESSION_EVENT_READ)) {
        var contracts =
            new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts(
                lease.capabilities().contains(ToolCapability.SESSION_SEARCH)
                    ? 4
                    : lease.capabilities().contains(ToolCapability.SESSION_EVENT_TRACE)
                        ? 3
                        : lease.capabilities().contains(ToolCapability.SESSION_EVENT_SEARCH)
                            ? 2
                            : 1);
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.TODO_WRITE)) {
        var contracts = new io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts();
        var pin = pins.get(name);
        if (!pin.version().equals(contracts.descriptor().version())
            || !pin.digest().equals(contracts.descriptor().outputSha256()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contracts.description())
                .inputSchema(contracts.inputSchema())
                .build());
        continue;
      }
      if (name.equals(ToolCapability.ASK_USER_QUESTION)) {
        var contract =
            new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts()
                .contract(pins.get(name).version());
        if (!contract.descriptor().outputSha256().equals(pins.get(name).digest()))
          throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(contract.description())
                .inputSchema(contract.input())
                .build());
        continue;
      }
      boolean web = name.equals(ToolCapability.WEB_FETCH);
      if (!web && (!fetch.publicMode() || workspace == null))
        throw new TaskFailure(503, FileErrorCode.WORKSPACE_NOT_CONFIGURED);
      var descriptor =
          web
              ? fetch.descriptor()
              : workspace.contract(name, pins.get(name).version()).descriptor();
      if (!web) workspace.available(name);
      var pin = pins.get(name);
      if (pin == null
          || !descriptor.version().equals(pin.version())
          || !descriptor.outputSha256().equals(pin.digest()))
        throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
      if (web) {
        fetch.available();
        result.add(definition);
      } else
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(
                    io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts
                        .description(name))
                .inputSchema(workspace.contract(name, pin.version()).input())
                .build());
    }
    return List.copyOf(result);
  }
}
