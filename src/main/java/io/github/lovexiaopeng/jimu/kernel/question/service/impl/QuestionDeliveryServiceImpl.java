package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionDeliveries;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionDeliveryService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;

public final class QuestionDeliveryServiceImpl implements QuestionDeliveryService {
  public <W, M> void prepare(String step, String request, QuestionDeliveries<W, M> port) {
    if (port.questionTask()) {
      var wire = port.decode(request);
      for (var pending : port.pending().entrySet()) {
        if (port.messages(wire).stream()
            .anyMatch(
                m -> port.role(m).equals("user") && pending.getValue().equals(port.content(m)))) {
          if (port.checkpoint(step, port.tenant(), pending.getKey()) != 1)
            throw new TaskFailure(409, QuestionErrorCode.QUESTION_DELIVERY_CONFLICT);
        }
      }
      port.clearContinuation();
    }
  }
}
