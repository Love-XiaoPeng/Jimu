package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** Pixel bytes never enter tool JSON; only the host may create a provider media part. */
public final class ReadImagePlugin implements ToolPlugin<ReadImagePlugin.Input, ImagePort.Result> {
  public record Input(String filePath) {}

  private final ImagePort images;
  private final ToolDescriptor descriptor;

  public ReadImagePlugin(ImagePort images, ToolDescriptor descriptor) {
    this.images = images;
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
  public Class<ImagePort.Result> outputType() {
    return ImagePort.Result.class;
  }

  @Override
  public ToolResult<ImagePort.Result> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    var result = images.read(input.filePath());
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(result);
  }
}
