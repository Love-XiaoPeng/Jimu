package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionClockServiceImpl;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class QuestionClockServiceTest {
  private final QuestionClockServiceImpl service = new QuestionClockServiceImpl();

  @Test
  void originalUtcEpochNanoAndCrossDayConversion() {
    for (String value :
        List.of(
            "1970-01-01T00:00:00",
            "1969-12-31T23:59:59.999999",
            "2026-10-25T23:59:59.123456",
            "2026-10-26T00:00:00.000001")) {
      var time = LocalDateTime.parse(value);
      var expected = Instant.parse(value + "Z");
      var calls = new AtomicInteger();
      var actual =
          service.now(
              () -> {
                calls.incrementAndGet();
                return time;
              });
      assertEquals(expected, actual);
      assertEquals(expected.getEpochSecond(), actual.getEpochSecond());
      assertEquals(time.getNano(), actual.getNano());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void constructionIsLazyAndEachInvocationReadsAgain() {
    var calls = new AtomicInteger();
    io.github.lovexiaopeng.jimu.kernel.question.port.QuestionClock port =
        () -> LocalDateTime.of(2026, 10, 26, 0, 0, calls.getAndIncrement());
    assertEquals(0, calls.get());
    assertEquals(Instant.parse("2026-10-26T00:00:00Z"), service.now(port));
    assertEquals(Instant.parse("2026-10-26T00:00:01Z"), service.now(port));
    assertEquals(2, calls.get());
  }

  @Test
  void nullReadFailsAtOriginalConversionWithoutFallback() {
    var count = new AtomicInteger();
    assertThrows(
        NullPointerException.class,
        () ->
            service.now(
                () -> {
                  count.incrementAndGet();
                  return null;
                }));
    assertEquals(1, count.get());
  }

  @Test
  void originalRuntimeDataAccessAndErrorInstancesEscapeUnchanged() {
    for (Throwable failure :
        List.of(
            new IllegalArgumentException("original"),
            new DataAccessResourceFailureException("original"),
            new AssertionError("original"))) {
      var calls = new AtomicInteger();
      assertSame(
          failure,
          assertThrows(
              Throwable.class,
              () ->
                  service.now(
                      () -> {
                        calls.incrementAndGet();
                        if (failure instanceof Error error) throw error;
                        throw (RuntimeException) failure;
                      })));
      assertEquals(1, calls.get());
    }
  }
}
