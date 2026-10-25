package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionEligibility;

public interface QuestionEligibilityService {
  <P, C> void access(QuestionEligibility<P, C> port);
}
