package io.github.lovexiaopeng.jimu.plugins.builtin.todo;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;
import java.util.List;

public record TodoWritePlugin(TodoPort port, ToolDescriptor descriptor)
    implements ToolPlugin<TodoWritePlugin.Input, TodoPort.Result> {
  public record Input(List<TodoPort.Item> todos) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<TodoPort.Result> outputType() {
    return TodoPort.Result.class;
  }

  @Override
  public ToolResult<TodoPort.Result> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.replace(input.todos()));
  }
}
