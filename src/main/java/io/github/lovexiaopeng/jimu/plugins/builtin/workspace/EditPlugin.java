package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class EditPlugin implements ToolPlugin<EditPlugin.Input, FilePort.Written> {
  public record Input(String filePath, String oldString, String newString, boolean replaceAll) {}

  private final EditPort edits;
  private final ToolDescriptor descriptor;

  public EditPlugin(EditPort edits, ToolDescriptor descriptor) {
    this.edits = edits;
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
    return new ToolResult<>(
        edits.edit(input.filePath(), input.oldString(), input.newString(), input.replaceAll()));
  }
}
