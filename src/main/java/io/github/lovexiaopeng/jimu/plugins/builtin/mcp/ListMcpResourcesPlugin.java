package io.github.lovexiaopeng.jimu.plugins.builtin.mcp;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public record ListMcpResourcesPlugin(McpResourcePort port, ToolDescriptor descriptor)
    implements ToolPlugin<ListMcpResourcesPlugin.Input, McpResourcePort.Resources> {
  public record Input(String server, String cursor) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<McpResourcePort.Resources> outputType() {
    return McpResourcePort.Resources.class;
  }

  @Override
  public ToolResult<McpResourcePort.Resources> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.listResources(input.server(), input.cursor()));
  }
}
