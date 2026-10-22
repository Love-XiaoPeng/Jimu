package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;

public record WorkingDirectoryPlugin(WorkingDirectoryPort port, ToolDescriptor descriptor)
    implements ToolPlugin<WorkingDirectoryPlugin.Input, WorkingDirectoryPort.Result> {
  public record Input(String cd) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<WorkingDirectoryPort.Result> outputType() {
    return WorkingDirectoryPort.Result.class;
  }

  @Override
  public ToolResult<WorkingDirectoryPort.Result> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(input.cd() == null ? port.current() : port.change(input.cd()));
  }
}
