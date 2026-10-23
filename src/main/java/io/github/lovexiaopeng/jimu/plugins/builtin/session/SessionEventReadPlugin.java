package io.github.lovexiaopeng.jimu.plugins.builtin.session;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;

public final class SessionEventReadPlugin implements ToolPlugin<SessionEventPort.Input, String> {
  private final SessionEventPort port;
  private final ToolDescriptor descriptor;

  public SessionEventReadPlugin(SessionEventPort port, ToolDescriptor descriptor) {
    this.port = port;
    this.descriptor = descriptor;
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Class<SessionEventPort.Input> inputType() {
    return SessionEventPort.Input.class;
  }

  @Override
  public Class<String> outputType() {
    return String.class;
  }

  @Override
  public ToolResult<String> execute(SessionEventPort.Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.read(input));
  }
}
