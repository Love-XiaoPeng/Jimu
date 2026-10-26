package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionClockMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionClockPortTest {
  @Test
  void constructorDoesNotAcquireLedgerSessionOrClock() {
    var port = new MapperQuestionClock(null);
    assertThrows(NullPointerException.class, port::read);
  }

  @Test
  void nullableSingleResultPreservesOriginalEmptyMultipleAndNullBoundaries() {
    var empty =
        assertThrows(
            EmptyResultDataAccessException.class, () -> MapperQuestionClock.single(List.of()));
    assertEquals(1, empty.getExpectedSize());
    assertEquals(0, empty.getActualSize());
    var now = LocalDateTime.of(2026, 10, 26, 0, 0, 0, 123456000);
    assertSame(now, MapperQuestionClock.single(List.of(now)));
    assertNull(MapperQuestionClock.single(Collections.singletonList(null)));
    var multiple =
        assertThrows(
            IncorrectResultSizeDataAccessException.class,
            () -> MapperQuestionClock.single(List.of(now, now)));
    assertEquals(1, multiple.getExpectedSize());
    assertEquals(2, multiple.getActualSize());
  }

  @Test
  void loadedOriginalZeroBindSqlAndLocalDateTimeMappingKeepExistingCacheConfiguration() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var c = session.getConfiguration();
    var statement = c.getMappedStatement(QuestionClockMapper.class.getName() + ".clock");
    for (Object query :
        Arrays.asList(
            null,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
                System.nanoTime() + 2_000_000_000L, null))) {
      var parameters = new HashMap<String, Object>();
      parameters.put("query", query);
      var bound = statement.getBoundSql(parameters);
      assertEquals("SELECT CURRENT_TIMESTAMP(6)", bound.getSql());
      assertEquals(List.of(), bound.getParameterMappings());
    }
    assertEquals(LocalDateTime.class, statement.getResultMaps().getFirst().getType());
    assertFalse(c.isCacheEnabled());
    assertEquals(LocalCacheScope.STATEMENT, c.getLocalCacheScope());
  }
}
