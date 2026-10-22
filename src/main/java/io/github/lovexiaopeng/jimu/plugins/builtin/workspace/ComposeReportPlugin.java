package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** The host validates the whole draft before a bound FilePort may write. */
public final class ComposeReportPlugin
    implements ToolPlugin<ComposeReportPlugin.Input, ReportCompositionPort.Receipt> {
  public record Input(String file_path, ReportCompositionPort.Draft draft) {}

  /** Same plugin identity with an explicitly versioned public payload. */
  public static final class Images
      implements ToolPlugin<
          Images.Input, io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort.Receipt> {
    public record Input(
        String file_path, io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort.Draft draft) {}

    private final io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort port;
    private final ToolDescriptor descriptor;

    public Images(
        io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort port, ToolDescriptor descriptor) {
      this.port = port;
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
    public Class<io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort.Receipt> outputType() {
      return io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort.Receipt.class;
    }

    @Override
    public ToolResult<io.github.lovexiaopeng.jimu.plugin.api.ImageReportPort.Receipt> execute(
        Input input, ExecutionContext context) {
      context.cancellation().throwIfCancelled();
      return new ToolResult<>(port.compose(input.file_path(), input.draft()));
    }
  }

  private final ReportCompositionPort reports;
  private final ToolDescriptor descriptor;

  public ComposeReportPlugin(ReportCompositionPort reports, ToolDescriptor descriptor) {
    this.reports = reports;
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
  public Class<ReportCompositionPort.Receipt> outputType() {
    return ReportCompositionPort.Receipt.class;
  }

  @Override
  public ToolResult<ReportCompositionPort.Receipt> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(reports.compose(input.file_path(), input.draft()));
  }
}
