package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionPause;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionPauseServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionPauseServiceTest {
  private final QuestionPauseServiceImpl service = new QuestionPauseServiceImpl();

  static class Trace {
    final List<String> calls = new ArrayList<>();
    int first = 1, second = 1, tenant, fence, fail = -1;
    Throwable failure;

    QuestionPause port() {
      return (QuestionPause)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionPause.class},
              (p, m, a) -> {
                calls.add(m.getName() + (a == null ? "" : Arrays.asList(a).toString()));
                if (calls.size() == fail) throw failure;
                return switch (m.getName()) {
                  case "tenant" -> "tenant" + (++tenant);
                  case "attemptId" -> "attempt";
                  case "fence" -> (long) ++fence;
                  case "taskId" -> "task";
                  case "pauseAttempt" -> first;
                  case "pauseTask" -> second;
                  default -> throw new AssertionError();
                };
              });
    }
  }

  @Test
  void retainsIndependentLeaseReadsAndBothOriginalArgumentOrders() {
    var t = new Trace();
    service.pause(t.port(), "RESUME_READY", "step");
    assertEquals(
        List.of(
            "tenant",
            "attemptId",
            "fence",
            "pauseAttempt[tenant1, attempt, 1]",
            "tenant",
            "taskId",
            "fence",
            "pauseTask[RESUME_READY, step, tenant2, task, 2]"),
        t.calls);
  }

  @Test
  void firstCountOtherThanOneStopsBeforeSecondMemberOrPersistence() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.first = count;
      assertEquals(
          "FENCE_REJECTED",
          assertThrows(TaskFailure.class, () -> service.pause(t.port(), "WAITING_USER", null))
              .getMessage());
      assertEquals(
          List.of("tenant", "attemptId", "fence", "pauseAttempt[tenant1, attempt, 1]"), t.calls);
    }
  }

  @Test
  void secondCountOtherThanOneRejectsAfterExactlyOneFirstUpdate() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.second = count;
      assertEquals(
          "FENCE_REJECTED",
          assertThrows(TaskFailure.class, () -> service.pause(t.port(), "state", "continuation"))
              .getMessage());
      assertEquals(8, t.calls.size());
      assertEquals("pauseAttempt[tenant1, attempt, 1]", t.calls.get(3));
      assertEquals("pauseTask[state, continuation, tenant2, task, 2]", t.calls.getLast());
    }
  }

  @Test
  void nullStateAndContinuationReachSecondUpdateWithoutNewGuard() {
    var t = new Trace();
    service.pause(t.port(), null, null);
    assertEquals("pauseTask[null, null, tenant2, task, 2]", t.calls.getLast());
  }

  @Test
  void everyReachedMemberAndUpdateFailurePreservesSameInstanceAndNoLaterCall() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("original"),
            new AssertionError("original"),
            new TaskFailure(409, "original"))) {
      for (int i = 1; i <= 8; i++) {
        var t = new Trace();
        t.fail = i;
        t.failure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> service.pause(t.port(), "s", "c")));
        assertEquals(i, t.calls.size());
      }
    }
  }
}
