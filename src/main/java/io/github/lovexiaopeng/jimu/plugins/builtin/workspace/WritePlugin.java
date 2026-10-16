package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** CAS and operation identity belong to the host port, not the model input. */
public final class WritePlugin implements ToolPlugin<WritePlugin.Input, FilePort.Written> {
  public record Input(String filePath, String content) {}

  private final FilePort files;
  private final ToolDescriptor descriptor;

  public WritePlugin(FilePort files, ToolDescriptor descriptor) {
    this.files = files;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<FilePort.Written> outputType() {
    return FilePort.Written.class;
  }

  @Override
  public ToolResult<FilePort.Written> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(files.write(input.filePath(), input.content()));
  }
}
