package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;
import java.util.List;

/** Produces receipts through host ports; publication authority stays in the fenced host. */
public final class PresentPlugin implements ToolPlugin<PresentPlugin.Input, ReportPort.Result> {
  public record File(String path, String description) {}

  public record Input(List<File> files) {
    public Input {
      files = List.copyOf(files);
    }
  }

  private final FilePort files;
  private final ReportPort reports;
  private final ToolDescriptor descriptor;

  public PresentPlugin(FilePort files, ReportPort reports, ToolDescriptor descriptor) {
    this.files = files;
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
  public Class<ReportPort.Result> outputType() {
    return ReportPort.Result.class;
  }

  @Override
  public ToolResult<ReportPort.Result> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    var snapshots = input.files().stream().map(file -> files.snapshot(file.path())).toList();
    // The host checks cancellation inside the publication transaction. A post-commit
    // cancellation cannot retroactively turn an already committed result into unknown IO.
    return new ToolResult<>(reports.publish(snapshots));
  }
}
