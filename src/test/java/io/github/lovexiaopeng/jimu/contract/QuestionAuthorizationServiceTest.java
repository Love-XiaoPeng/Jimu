package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionAuthorization;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionAuthorizationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class QuestionAuthorizationServiceTest {
  private final QuestionAuthorizationServiceImpl service = new QuestionAuthorizationServiceImpl();
  private static final List<String> ORDER =
      List.of(
          "tx", "tenant1", "bucket", "visible", "tenant2", "task", "request", "query", "isEmpty",
          "tx-null");

  static class Port implements QuestionAuthorization {
    final List<String> calls = new ArrayList<>();
    int tenants;
    boolean empty;
    String failAt;
    Throwable failure;

    void hit(String at) {
      calls.add(at);
      if (at.equals(failAt)) {
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
      }
    }

    public Void transaction(Supplier<Void> work) {
      hit("tx");
      Void v = work.get();
      assertNull(v);
      hit("tx-null");
      return v;
    }

    public String tenant() {
      hit("tenant" + (++tenants));
      return "tenant" + tenants;
    }

    public String taskId() {
      hit("task");
      return "task";
    }

    public String requestId() {
      hit("request");
      return "request";
    }

    public void bucket(String tenant) {
      hit("bucket");
      assertEquals("tenant1", tenant);
    }

    public void visible() {
      hit("visible");
    }

    public List<Map<String, Object>> rows(String tenant, String task, String request) {
      hit("query");
      assertEquals("tenant2", tenant);
      assertEquals("task", task);
      assertEquals("request", request);
      return new AbstractList<>() {
        public boolean isEmpty() {
          hit("isEmpty");
          return empty;
        }

        public int size() {
          throw new AssertionError("size not original operation");
        }

        public Map<String, Object> get(int i) {
          throw new AssertionError("row consumed");
        }
      };
    }
  }

  @Test
  void completeOriginalOrderReReadsTenantAndReturnsVoidWithTransactionNull() {
    var p = new Port();
    service.authorize(p);
    assertEquals(ORDER, p.calls);
  }

  @Test
  void emptyIsExactQuestionNotFoundAndDoesNotReturnTransactionNull() {
    var p = new Port();
    p.empty = true;
    var e = assertThrows(TaskFailure.class, () -> service.authorize(p));
    assertEquals(404, e.status());
    assertEquals("QUESTION_NOT_FOUND", e.code());
    assertEquals(ORDER.subList(0, 9), p.calls);
  }

  @Test
  void nonemptyNullAndPoisonRowsAreNeverDecodedOrCounted() {
    for (int size : List.of(1, 2, 3)) {
      var p =
          new Port() {
            public List<Map<String, Object>> rows(String t, String task, String q) {
              super.rows(t, task, q);
              return new AbstractList<>() {
                public boolean isEmpty() {
                  hit("isEmpty");
                  return false;
                }

                public int size() {
                  return size;
                }

                public Map<String, Object> get(int i) {
                  throw new AssertionError("null/poison/multiple elements are unconsumed");
                }
              };
            }
          };
      service.authorize(p);
      assertEquals(ORDER, p.calls);
    }
  }

  @Test
  void everyFailureStopsLaterWorkAndPropagatesSameInstance() {
    for (String at : ORDER)
      for (Throwable e :
          List.of(
              new IllegalStateException(at),
              new AssertionError(at),
              new TaskFailure(403, "ORIGINAL"))) {
        var p = new Port();
        p.failAt = at;
        p.failure = e;
        assertSame(e, assertThrows(Throwable.class, () -> service.authorize(p)));
        assertEquals(ORDER.subList(0, ORDER.indexOf(at) + 1), p.calls);
      }
  }
}
