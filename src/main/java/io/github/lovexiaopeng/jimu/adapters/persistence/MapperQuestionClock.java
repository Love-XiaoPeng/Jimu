package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionClockMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionClock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.support.DataAccessUtils;

/** Saves only the original Ledger reference; query/session acquisition happens at read. */
final class MapperQuestionClock implements QuestionClock {
  private final JdbcLedger ledger;

  MapperQuestionClock(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public LocalDateTime read() {
    return single(
        ledger.mappers.session().getMapper(QuestionClockMapper.class).clock(ledger.queryContext()));
  }

  static LocalDateTime single(List<LocalDateTime> rows) {
    return DataAccessUtils.nullableSingleResult(rows);
  }
}
