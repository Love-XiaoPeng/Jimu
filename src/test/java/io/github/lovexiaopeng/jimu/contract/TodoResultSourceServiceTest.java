package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoResultSources;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoResultSourceServiceImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoResultSourceServiceTest {
  private final TodoResultSourceServiceImpl service = new TodoResultSourceServiceImpl();
  private static final List<String> ORDER =
      List.of(
          "appliedRevisionPresent",
          "appliedRevision",
          "expectedRevision",
          "state",
          "tool",
          "committedAttemptPresent",
          "committedFencePresent",
          "eventSequencePresent",
          "committedAtPresent",
          "attempts",
          "contentState",
          "outputSnapshot",
          "hash",
          "outputDigest");

  private static final class Fixture {
    final List<String> calls = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    String fault;
    Throwable error = new IllegalStateException("original");

    Fixture() {
      for (String key :
          List.of(
              "appliedRevisionPresent",
              "committedAttemptPresent",
              "committedFencePresent",
              "eventSequencePresent",
              "committedAtPresent")) values.put(key, true);
      values.put("appliedRevision", 1L);
      values.put("expectedRevision", 0L);
      values.put("state", "SUCCEEDED");
      values.put("tool", "todo_write");
      values.put("attempts", List.of(Map.of()));
      values.put("contentState", "PRESENT");
      values.put("outputSnapshot", "output");
      values.put("hash", "digest");
      values.put("outputDigest", "digest");
    }

    TodoResultSources port() {
      return (TodoResultSources)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {TodoResultSources.class},
              (p, m, a) -> {
                calls.add(m.getName());
                if (m.getName().equals(fault)) throw error;
                if (m.getName().equals("hash")) assertEquals(values.get("outputSnapshot"), a[0]);
                return values.get(m.getName());
              });
    }
  }

  private void reject(Fixture f) {
    var failure = assertThrows(TaskFailure.class, () -> service.require(f.port()));
    assertEquals(409, failure.status());
    assertEquals("SNAPSHOT_CORRUPT", failure.code());
  }

  @Test
  void completeOrderAndRepeatedInvocationReadsAgain() {
    var f = new Fixture();
    service.require(f.port());
    assertEquals(ORDER, f.calls);
    f.calls.clear();
    service.require(f.port());
    assertEquals(ORDER, f.calls);
  }

  @Test
  void everyOrStopsAtOriginalPosition() {
    for (String key :
        List.of(
            "appliedRevisionPresent",
            "appliedRevision",
            "state",
            "tool",
            "committedAttemptPresent",
            "committedFencePresent",
            "eventSequencePresent",
            "committedAtPresent")) {
      var f = new Fixture();
      Object value = f.values.get(key);
      f.values.put(key, value instanceof Boolean ? false : value instanceof Long ? 2L : "bad");
      reject(f);
      int end =
          key.equals("appliedRevision") ? ORDER.indexOf("expectedRevision") : ORDER.indexOf(key);
      assertEquals(ORDER.subList(0, end + 1), f.calls);
    }
  }

  @Test
  void nullStateAndToolAreOriginalRejections() {
    for (String key : List.of("state", "tool")) {
      var f = new Fixture();
      f.values.put(key, null);
      reject(f);
      assertEquals(ORDER.subList(0, ORDER.indexOf(key) + 1), f.calls);
    }
  }

  @Test
  void originalSignedIncrementOverflowIsAllowed() {
    var f = new Fixture();
    f.values.put("appliedRevision", Long.MIN_VALUE);
    f.values.put("expectedRevision", Long.MAX_VALUE);
    service.require(f.port());
    assertEquals(ORDER, f.calls);
  }

  @Test
  void exactlyOneRowWithoutReadingItsIdIsEnough() {
    var f = new Fixture();
    f.values.put("attempts", Arrays.asList((Object) null));
    service.require(f.port());
    assertEquals(ORDER, f.calls);
  }

  @Test
  void zeroAndManyRowsRejectBeforeContent() {
    for (var rows : List.of(List.of(), List.of("first", "second"))) {
      var f = new Fixture();
      f.values.put("attempts", rows);
      reject(f);
      assertEquals(ORDER.subList(0, 10), f.calls);
    }
  }

  @Test
  void purgedStillQueriesAndSkipsAllOutputReads() {
    var f = new Fixture();
    f.values.put("contentState", "PURGED");
    f.fault = "outputSnapshot";
    service.require(f.port());
    assertEquals(ORDER.subList(0, 11), f.calls);
  }

  @Test
  void nullAndOtherContentStatesStillHash() {
    for (String state : Arrays.asList(null, "OTHER", "purged")) {
      var f = new Fixture();
      f.values.put("contentState", state);
      service.require(f.port());
      assertEquals(ORDER, f.calls);
    }
  }

  @Test
  void hashMismatchIsOriginalFailure() {
    var f = new Fixture();
    f.values.put("outputDigest", "other");
    reject(f);
    assertEquals(ORDER, f.calls);
  }

  @Test
  void digestReadPrecedesEqualsNullFailure() {
    var f = new Fixture();
    f.values.put("hash", null);
    assertThrows(NullPointerException.class, () -> service.require(f.port()));
    assertEquals(ORDER, f.calls);
  }

  @Test
  void failuresAtEveryReadPropagateWithNoLaterAccess() {
    for (String at : ORDER) {
      var f = new Fixture();
      f.fault = at;
      assertSame(
          f.error, assertThrows(IllegalStateException.class, () -> service.require(f.port())));
      assertEquals(ORDER.subList(0, ORDER.indexOf(at) + 1), f.calls);
    }
  }

  @Test
  void taskFailureAndErrorsAreNotTranslated() {
    for (Throwable error :
        List.of(new TaskFailure(404, "NOT_FOUND"), new AssertionError("original"))) {
      var f = new Fixture();
      f.fault = "attempts";
      f.error = error;
      assertSame(error, assertThrows(error.getClass(), () -> service.require(f.port())));
      assertEquals(ORDER.subList(0, 10), f.calls);
    }
  }
}
