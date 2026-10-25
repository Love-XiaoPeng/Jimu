package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionVisibility;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionVisibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionVisibilityServiceTest {
  private final QuestionVisibilityServiceImpl service = new QuestionVisibilityServiceImpl();
  private static final List<String> ORDER =
      List.of(
          "identity", "tenant1", "task1", "actor", "owner", "tenant2", "task2", "locked", "access");

  static class Port implements QuestionVisibility<Object> {
    final List<String> calls = new ArrayList<>();
    List<Map<String, Object>> rows = List.of(new HashMap<>());
    final Object task = new Object();
    String failAt;
    Throwable failure;
    int tenants, tasks;

    void called(String name) {
      calls.add(name);
      if (name.equals(failAt)) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
      }
    }

    public void identity() {
      called("identity");
    }

    public String tenant() {
      called("tenant" + ++tenants);
      return "tenant" + tenants;
    }

    public String taskId() {
      called("task" + ++tasks);
      return "task";
    }

    public String actor() {
      called("actor");
      return "actor";
    }

    public List<Map<String, Object>> owned(String tenant, String id, String actor) {
      called("owner");
      assertEquals("tenant1", tenant);
      assertEquals("task", id);
      assertEquals("actor", actor);
      return rows;
    }

    public Object locked(String tenant, String id) {
      called("locked");
      assertEquals("tenant2", tenant);
      assertEquals("task", id);
      return task;
    }

    public void access(Object row) {
      called("access");
      assertSame(task, row);
    }
  }

  @Test
  void originalCompleteRowInstanceAndAllOperationsOnce() {
    var port = new Port();
    assertSame(port.task, service.visible(port));
    assertEquals(ORDER, port.calls);
  }

  @Test
  void emptyOwnerRetainsExactFailureAndSkipsLocksAndAccess() {
    var port = new Port();
    port.rows = List.of();
    var failure = assertThrows(TaskFailure.class, () -> service.visible(port));
    assertEquals(404, failure.status());
    assertEquals("TASK_NOT_FOUND", failure.code());
    assertEquals(ORDER.subList(0, 5), port.calls);
  }

  @Test
  void arbitraryNonemptyRowsNeverReadIdOrDemandOneRow() {
    var unread =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            throw new AssertionError("owner row value read");
          }
        };
    for (var rows :
        List.of(
            List.<Map<String, Object>>of(unread), List.<Map<String, Object>>of(unread, unread))) {
      var port = new Port();
      port.rows = rows;
      assertSame(port.task, service.visible(port));
      assertEquals(ORDER, port.calls);
    }
  }

  @Test
  void eachRuntimeErrorPropagatesBeforeAnyLaterOperation() {
    for (String at : ORDER) {
      for (Throwable error :
          List.of(
              new IllegalStateException(at),
              new AssertionError(at),
              new TaskFailure(403, "ORIGINAL"))) {
        var port = new Port();
        port.failAt = at;
        port.failure = error;
        assertSame(error, assertThrows(Throwable.class, () -> service.visible(port)));
        assertEquals(ORDER.subList(0, ORDER.indexOf(at) + 1), port.calls);
      }
    }
  }
}
