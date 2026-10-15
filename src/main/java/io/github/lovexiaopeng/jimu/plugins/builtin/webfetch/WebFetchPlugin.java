package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** P1 trusted built-in sample. No public invocation endpoint is installed. */
public final class WebFetchPlugin implements ToolPlugin<FetchInput, FetchOutput> {
  private final WebPageReader reader;
  private final ToolDescriptor descriptor;

  public WebFetchPlugin(WebPageReader reader, ToolDescriptor descriptor) {
    this.reader = reader;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<FetchInput> inputType() {
    return FetchInput.class;
  }

  @Override
  public Class<FetchOutput> outputType() {
    return FetchOutput.class;
  }

  @Override
  public ToolResult<FetchOutput> execute(FetchInput input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    FetchOutput output = reader.fetch(input.url(), context);
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(output);
  }
}
