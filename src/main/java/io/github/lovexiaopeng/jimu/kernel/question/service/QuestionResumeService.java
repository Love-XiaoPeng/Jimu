package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionResume;

public interface QuestionResumeService {
  void resume(QuestionResume port, String requestId);
}
