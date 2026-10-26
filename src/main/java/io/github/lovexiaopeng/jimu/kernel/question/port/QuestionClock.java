package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.time.LocalDateTime;

/** One lazy read of the original database expression; no conversion or fallback. */
public interface QuestionClock {
  LocalDateTime read();
}
