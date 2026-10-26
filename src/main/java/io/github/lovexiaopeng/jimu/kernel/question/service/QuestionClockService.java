package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionClock;
import java.time.Instant;

public interface QuestionClockService {
  Instant now(QuestionClock clock);
}
