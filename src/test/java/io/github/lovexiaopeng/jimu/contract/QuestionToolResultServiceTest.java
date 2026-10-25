package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionToolResult;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionToolResultServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionToolResultServiceTest {
  private final QuestionToolResultServiceImpl service = new QuestionToolResultServiceImpl();

  static class Trace {
    final List<String> calls = new ArrayList<>();
    int first = 1, second = 1, fail = -1, tenants, invocations;
    String invocation = "invocation";
    Throwable failure;

    QuestionToolResult<?> port() {
      return (QuestionToolResult<?>)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionToolResult.class},
              (p, m, a) -> {
                calls.add(m.getName() + (a == null ? "" : Arrays.asList(a).toString()));
                if (calls.size() == fail) throw failure;
                return switch (m.getName()) {
                  case "tenant" -> "tenant" + (++tenants);
                  case "invocation" -> {
                    invocations++;
                    yield invocation;
                  }
                  case "pin" -> "pin";
                  case "version" -> "historic";
                  case "hash" -> "digest";
                  case "taskId" -> "task";
                  case "completeInvocation" -> first;
                  case "settleReservation" -> second;
                  case "output", "event" -> null;
                  default -> throw new AssertionError();
                };
              });
    }
  }

  @Test
  void rawOutputOrderAndOnlyOnceLocalsSurviveAllStages() {
    var t = new Trace();
    service.complete(t.port(), " raw🙂 ");
    assertEquals(
        List.of(
            "tenant",
            "invocation",
            "pin",
            "version[pin]",
            "output[historic,  raw🙂 ]",
            "hash[ raw🙂 ]",
            "taskId",
            "completeInvocation[ raw🙂 , digest, tenant1, task, invocation]",
            "settleReservation[tenant1, invocation]",
            "event[tool:invocation, tool.completed, null]"),
        t.calls);
    assertEquals(1, t.tenants);
    assertEquals(1, t.invocations);
  }

  @Test
  void firstCountRejectsWithoutReservationOrEvent() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.first = count;
      assertEquals(
          "INVOCATION_STATE",
          assertThrows(TaskFailure.class, () -> service.complete(t.port(), "raw")).code());
      assertEquals(8, t.calls.size());
    }
  }

  @Test
  void secondCountRejectsAfterFirstWriteWithoutCompensationOrEvent() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
      var t = new Trace();
      t.second = count;
      assertEquals(
          "RESERVATION_STATE",
          assertThrows(TaskFailure.class, () -> service.complete(t.port(), "raw")).code());
      assertEquals(9, t.calls.size());
      assertEquals("settleReservation[tenant1, invocation]", t.calls.getLast());
    }
  }

  @Test
  void everyReachedFailurePropagatesItsOriginalInstanceAndStopsLaterCalls() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("original"),
            new AssertionError("original"),
            new TaskFailure(409, "original")))
      for (int i = 1; i <= 10; i++) {
        var t = new Trace();
        t.fail = i;
        t.failure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> service.complete(t.port(), "raw")));
        assertEquals(i, t.calls.size());
      }
  }

  @Test
  void noNewNullGuardAndOriginalNullInvocationFactInSyntheticReachablePort() {
    var t = new Trace();
    t.invocation = null;
    service.complete(t.port(), null);
    assertEquals("output[historic, null]", t.calls.get(4));
    assertEquals("completeInvocation[null, digest, tenant1, task, null]", t.calls.get(7));
    assertEquals("event[tool:null, tool.completed, null]", t.calls.getLast());
  }
}
