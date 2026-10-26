package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAnswer;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Submission;

public interface QuestionAnswerService {
  <Q, A, B, T> Submission answer(QuestionAnswer<Q, A, B, T> port);
}
