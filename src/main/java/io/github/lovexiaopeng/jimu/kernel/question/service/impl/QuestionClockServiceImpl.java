package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionClock;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionClockService;
import java.time.Instant;
import java.time.ZoneOffset;

public final class QuestionClockServiceImpl implements QuestionClockService {
  public Instant now(QuestionClock clock) {
    return clock.read().toInstant(ZoneOffset.UTC);
  }
}
