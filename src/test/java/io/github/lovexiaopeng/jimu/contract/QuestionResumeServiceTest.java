package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionResume;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionResumeServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionResumeServiceTest {
  private final QuestionResumeServiceImpl service = new QuestionResumeServiceImpl();

  static class Trace {
    final List<String> calls = new ArrayList<>();
    int count = 1, fail = -1;
    Throwable failure;

    QuestionResume port() {
      return (QuestionResume)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionResume.class},
              (p, m, a) -> {
                calls.add(m.getName() + (a == null ? "" : Arrays.asList(a).toString()));
                if (calls.size() == fail) throw failure;
                return switch (m.getName()) {
                  case "tenant" -> "tenant";
                  case "taskId" -> "task";
                  case "update" -> count;
                  case "event" -> null;
                  default -> throw new AssertionError();
                };
              });
    }
  }

  @Test
  void exactlyOneUpdatesBeforeOneOriginalEvent() {
    var t = new Trace();
    service.resume(t.port(), "request");
    assertEquals(
        List.of(
            "tenant",
            "taskId",
            "update[tenant, task, request]",
            "event[question-resume:request, task.resumable, null]"),
        t.calls);
  }

  @Test
  void everyOtherCountRejectsWithoutEvent() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.count = count;
      assertEquals(
          "QUESTION_BINDING_MISMATCH",
          assertThrows(TaskFailure.class, () -> service.resume(t.port(), "r")).getMessage());
      assertEquals(List.of("tenant", "taskId", "update[tenant, task, r]"), t.calls);
    }
  }

  @Test
  void nullRequestRetainsSqlArgumentAndLiteralFact() {
    var t = new Trace();
    service.resume(t.port(), null);
    assertEquals(
        List.of(
            "tenant",
            "taskId",
            "update[tenant, task, null]",
            "event[question-resume:null, task.resumable, null]"),
        t.calls);
  }

  @Test
  void eachReachedFailurePropagatesSameRuntimeOrErrorWithoutLaterOperations() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("sql-or-runtime"),
            new AssertionError("error"),
            new TaskFailure(409, "original"))) {
      for (int i = 1; i <= 4; i++) {
        var t = new Trace();
        t.fail = i;
        t.failure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> service.resume(t.port(), "r")));
        assertEquals(i, t.calls.size());
      }
    }
  }
}
