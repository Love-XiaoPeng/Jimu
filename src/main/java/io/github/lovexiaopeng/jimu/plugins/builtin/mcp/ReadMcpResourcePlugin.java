package io.github.lovexiaopeng.jimu.plugins.builtin.mcp;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public record ReadMcpResourcePlugin(McpResourcePort port, ToolDescriptor descriptor)
    implements ToolPlugin<ReadMcpResourcePlugin.Input, McpResourcePort.Read> {
  public record Input(String server, String uri) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<McpResourcePort.Read> outputType() {
    return McpResourcePort.Read.class;
  }

  @Override
  public ToolResult<McpResourcePort.Read> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.read(input.server(), input.uri()));
  }
}
