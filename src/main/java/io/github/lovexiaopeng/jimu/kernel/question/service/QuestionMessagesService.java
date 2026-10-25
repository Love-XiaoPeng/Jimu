package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMessages;
import java.util.Map;

public interface QuestionMessagesService {
  <R, Q, A> Map<String, String> messages(QuestionMessages<R, Q, A> port);
}
