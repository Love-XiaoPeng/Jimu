package io.github.lovexiaopeng.jimu.plugins.builtin.workspace;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class GrepPlugin implements ToolPlugin<GrepPlugin.Input, SearchPort.GrepPage> {
  public record Input(String pattern, String path, String include, String cursor) {}

  private final SearchPort search;
  private final ToolDescriptor descriptor;

  public GrepPlugin(SearchPort search, ToolDescriptor descriptor) {
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
  public Class<SearchPort.GrepPage> outputType() {
    return SearchPort.GrepPage.class;
  }

  @Override
  public ToolResult<SearchPort.GrepPage> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(
        search.grep(input.pattern(), input.path(), input.include(), input.cursor()));
  }
}
