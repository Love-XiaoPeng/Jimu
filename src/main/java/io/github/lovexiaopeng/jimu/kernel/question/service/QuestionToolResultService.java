package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionToolResult;

public interface QuestionToolResultService {
  <P> void complete(QuestionToolResult<P> port, String output);
}
