package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.execution.service.impl.CommitFenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommitFenceServiceTest {
  private final CommitFenceServiceImpl service = new CommitFenceServiceImpl();

  @Test
  void exactlyOneSucceedsWithOneUpdateEachTime() {
    var calls = new AtomicInteger();
    for (int i = 0; i < 2; i++)
      service.require(
          () -> {
            calls.incrementAndGet();
            return 1;
          });
    assertEquals(2, calls.get());
  }

  @Test
  void everyOtherCountHasOriginalFailureWithoutRetry() {
    for (int count : List.of(0, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
      var calls = new AtomicInteger();
      var failure =
          assertThrows(
              TaskFailure.class,
              () ->
                  service.require(
                      () -> {
                        calls.incrementAndGet();
                        return count;
                      }));
      assertEquals(409, failure.status());
      assertEquals("FENCE_REJECTED", failure.code());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void originalRuntimeExceptionsPropagate() {
    for (RuntimeException failure :
        List.of(
            new NullPointerException("lease"),
            new IllegalStateException("sql"),
            new TaskFailure(403, "original"))) {
      assertSame(
          failure,
          assertThrows(
              failure.getClass(),
              () ->
                  service.require(
                      () -> {
                        throw failure;
                      })));
    }
  }

  @Test
  void errorsPropagate() {
    var failure = new AssertionError("original");
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () ->
                service.require(
                    () -> {
                      throw failure;
                    })));
  }
}
