package io.github.lovexiaopeng.jimu.plugins.builtin.mcp;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public record ListMcpResourceTemplatesPlugin(McpResourcePort port, ToolDescriptor descriptor)
    implements ToolPlugin<ListMcpResourceTemplatesPlugin.Input, McpResourcePort.Templates> {
  public record Input(String server, String cursor) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<McpResourcePort.Templates> outputType() {
    return McpResourcePort.Templates.class;
  }

  @Override
  public ToolResult<McpResourcePort.Templates> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.listTemplates(input.server(), input.cursor()));
  }
}
