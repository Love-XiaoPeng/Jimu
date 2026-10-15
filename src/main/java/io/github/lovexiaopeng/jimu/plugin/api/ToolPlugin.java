package io.github.lovexiaopeng.jimu.plugin.api;

public interface ToolPlugin<I, O> {
  ToolDescriptor descriptor();

  Class<I> inputType();

  Class<O> outputType();

  ToolResult<O> execute(I input, ExecutionContext context);
}
