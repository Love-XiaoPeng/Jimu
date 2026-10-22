package io.github.lovexiaopeng.jimu.plugins.builtin.interaction;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolPlugin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolResult;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort;
import java.util.List;

public record AskUserQuestionPlugin(UserQuestionPort port, ToolDescriptor descriptor)
    implements ToolPlugin<AskUserQuestionPlugin.Input, UserQuestionPort.AnswerBatch> {
  public record Input(List<UserQuestionPort.Question> questions, Integer timeout) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Class<UserQuestionPort.AnswerBatch> outputType() {
    return UserQuestionPort.AnswerBatch.class;
  }

  @Override
  public ToolResult<UserQuestionPort.AnswerBatch> execute(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    return new ToolResult<>(port.ask(input.questions(), input.timeout()));
  }
}
