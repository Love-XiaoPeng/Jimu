package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class GlobPlugin implements ToolPlugin<GlobPlugin.Input, SearchPort.GlobPage> {
  public record Input(String pattern, String path, String cursor) {}

  private final SearchPort search;
  private final ToolDescriptor descriptor;

  public GlobPlugin(SearchPort search, ToolDescriptor descriptor) {
    this.search = search;
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
  public Class<SearchPort.GlobPage> outputType() {
    return SearchPort.GlobPage.class;
  }

  @Override
  public ToolResult<SearchPort.GlobPage> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(search.glob(input.pattern(), input.path(), input.cursor()));
  }
}
