package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionInput;

public interface QuestionInputService {
  <P, R> R input(QuestionInput<P, R> port);
}
