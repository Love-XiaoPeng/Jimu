package io.github.lovexiaopeng.jimu.plugins.builtin.session;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class SessionSearchPlugin implements ToolPlugin<SessionSearchPort.Input, String> {
  private final SessionSearchPort port;
  private final ToolDescriptor descriptor;

  public SessionSearchPlugin(SessionSearchPort port, ToolDescriptor descriptor) {
    this.port = port;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<SessionSearchPort.Input> inputType() {
    return SessionSearchPort.Input.class;
  }

  @Override
  public Class<String> outputType() {
    return String.class;
  }

  @Override
  public ToolResult<String> execute(SessionSearchPort.Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.search(input));
  }
}
