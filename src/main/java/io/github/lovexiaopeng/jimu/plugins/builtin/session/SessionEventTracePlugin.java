package io.github.lovexiaopeng.jimu.plugins.builtin.session;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class SessionEventTracePlugin
    implements ToolPlugin<SessionEventTracePort.Input, String> {
  private final SessionEventTracePort port;
  private final ToolDescriptor descriptor;

  public SessionEventTracePlugin(SessionEventTracePort port, ToolDescriptor descriptor) {
    this.port = port;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<SessionEventTracePort.Input> inputType() {
    return SessionEventTracePort.Input.class;
  }

  @Override
  public Class<String> outputType() {
    return String.class;
  }

  @Override
  public ToolResult<String> execute(SessionEventTracePort.Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.trace(input));
  }
}
