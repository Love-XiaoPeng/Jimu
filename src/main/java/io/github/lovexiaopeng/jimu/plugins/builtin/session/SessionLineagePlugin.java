package io.github.lovexiaopeng.jimu.plugins.builtin.session;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SessionLineagePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

/** Session trace remains a leaf; the host owns authorization and original-slot commits. */
public final class SessionLineagePlugin implements ToolPlugin<String, String> {
  private final SessionLineagePort port;
  private final ToolDescriptor descriptor;

  public SessionLineagePlugin(SessionLineagePort port, ToolDescriptor descriptor) {
    this.port = port;
    this.descriptor = descriptor;
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public Class<String> inputType() {
    return String.class;
  }

  public Class<String> outputType() {
    return String.class;
  }

  public ToolResult<String> execute(String input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.trace(input, context));
  }
}
