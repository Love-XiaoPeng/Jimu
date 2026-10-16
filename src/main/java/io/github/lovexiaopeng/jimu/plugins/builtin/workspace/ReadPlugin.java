package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** Only the already scoped host port may open files. */
public final class ReadPlugin implements ToolPlugin<ReadPlugin.Input, FilePort.Read> {
  public record Input(String filePath, int offset, int limit) {}

  private final FilePort files;
  private final ToolDescriptor descriptor;

  public ReadPlugin(FilePort files, ToolDescriptor descriptor) {
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
  public Class<FilePort.Read> outputType() {
    return FilePort.Read.class;
  }

  @Override
  public ToolResult<FilePort.Read> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(files.read(input.filePath(), input.offset(), input.limit()));
  }
}
