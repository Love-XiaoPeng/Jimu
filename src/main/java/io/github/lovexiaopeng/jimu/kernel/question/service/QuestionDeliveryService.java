package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionDeliveries;

public interface QuestionDeliveryService {
  <W, M> void prepare(String step, String request, QuestionDeliveries<W, M> port);
}
