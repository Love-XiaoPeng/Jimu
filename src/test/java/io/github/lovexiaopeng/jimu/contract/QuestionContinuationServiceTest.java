package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionContinuation;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionContinuationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionContinuationServiceTest {
  private final QuestionContinuationServiceImpl service = new QuestionContinuationServiceImpl();

  static class Trace {
    final List<String> calls = new ArrayList<>();
    final String first = new String("first"), raw = new String(" raw pending🙂 ");
    List<String> rows = List.of(first, "second");
    int tenant, id, count = 1, fail = -1;
    Throwable failure;

    QuestionContinuation<?, ?> port() {
      return (QuestionContinuation<?, ?>)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionContinuation.class},
              (p, m, a) -> {
                calls.add(m.getName() + (a == null ? "" : Arrays.asList(a).toString()));
                if (calls.size() == fail) throw failure;
                return switch (m.getName()) {
                  case "tenant" -> "tenant" + (++tenant);
                  case "invocationId" -> "invocation";
                  case "taskId" -> "task";
                  case "invocations" -> rows;
                  case "pin" -> "pin";
                  case "version" -> "historic";
                  case "provider" -> {
                    assertSame(first, a[0]);
                    yield "provider";
                  }
                  case "pending" -> raw;
                  case "completeOutput" -> {
                    assertSame(raw, a[0]);
                    yield null;
                  }
                  case "questionId" -> "q" + (++id);
                  case "revision" -> 7L;
                  case "continuePhase" -> count;
                  case "resume", "event" -> null;
                  default -> throw new AssertionError();
                };
              });
    }
  }

  @Test
  void completeOriginalOrderAndIndependentReadsWithFirstOfMultipleRows() {
    var t = new Trace();
    service.continueQuestion(t.port());
    assertEquals(
        List.of(
            "tenant",
            "invocationId",
            "taskId",
            "invocations[tenant1, invocation, task]",
            "pin",
            "version[pin]",
            "provider[first]",
            "pending[historic, provider]",
            "completeOutput[ raw pending🙂 ]",
            "tenant",
            "questionId",
            "revision",
            "continuePhase[tenant2, q1, 7]",
            "questionId",
            "resume[q2]",
            "questionId",
            "event[question-continued:q3, question.continued, null]"),
        t.calls);
    assertEquals(2, t.tenant);
    assertEquals(3, t.id);
  }

  @Test
  void emptyListIsOriginalNotFoundBeforePinOrProvider() {
    var t = new Trace();
    t.rows = List.of();
    var failure = assertThrows(TaskFailure.class, () -> service.continueQuestion(t.port()));
    assertEquals("NOT_FOUND", failure.code());
    assertEquals(4, t.calls.size());
  }

  @Test
  void singletonUsesItsOriginalRowWithoutSizeConstraint() {
    var t = new Trace();
    t.rows = List.of(t.first);
    service.continueQuestion(t.port());
    assertEquals(17, t.calls.size());
  }

  @Test
  void eachNonOneCountRejectsAfterToolResultAndBeforeSecondIdOrResume() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.count = count;
      assertEquals(
          "QUESTION_REVISION_CONFLICT",
          assertThrows(TaskFailure.class, () -> service.continueQuestion(t.port())).code());
      assertEquals(13, t.calls.size());
      assertEquals(1, t.id);
      assertEquals("completeOutput[ raw pending🙂 ]", t.calls.get(8));
    }
  }

  @Test
  void everyReachedExceptionKeepsSameInstanceAndStopsLaterReadsAndEffects() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("original"),
            new AssertionError("original"),
            new TaskFailure(409, "original")))
      for (int i = 1; i <= 17; i++) {
        var t = new Trace();
        t.fail = i;
        t.failure = failure;
        assertSame(
            failure, assertThrows(Throwable.class, () -> service.continueQuestion(t.port())));
        assertEquals(i, t.calls.size());
      }
  }
}
