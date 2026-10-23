package io.github.lovexiaopeng.jimu.plugins.builtin.session;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class SessionEventSearchPlugin
    implements ToolPlugin<SessionEventSearchPort.Input, String> {
  private final SessionEventSearchPort port;
  private final ToolDescriptor descriptor;

  public SessionEventSearchPlugin(SessionEventSearchPort port, ToolDescriptor descriptor) {
    this.port = port;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<SessionEventSearchPort.Input> inputType() {
    return SessionEventSearchPort.Input.class;
  }

  @Override
  public Class<String> outputType() {
    return String.class;
  }

  @Override
  public ToolResult<String> execute(SessionEventSearchPort.Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.search(input));
  }
}
