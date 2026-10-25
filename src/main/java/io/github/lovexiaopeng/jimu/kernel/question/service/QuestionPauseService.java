package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionPause;

public interface QuestionPauseService {
  void pause(QuestionPause port, String state, String continuation);
}
