package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.question.port.PendingQuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.PendingQuestionMessagesServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PendingQuestionMessagesServiceTest {
  private final PendingQuestionMessagesServiceImpl service =
      new PendingQuestionMessagesServiceImpl();
  private static final Lease LEASE =
      new Lease("t", "a", "task", "attempt", 7, "worker", null, null, List.of(), 1);

  static final class Trace implements PendingQuestionMessages {
    final List<String> calls = new ArrayList<>();
    boolean inside;
    String fail;
    Throwable failure;
    Lease expected = LEASE;
    boolean nullMap;
    List<String> values = Arrays.asList("第二🙂", "same", null, "same");
    final Map<String, Object> task =
        new AbstractMap<>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("task iteration");
          }

          public Object get(Object k) {
            throw new AssertionError("task field " + k);
          }
        };

    void hit(String name) {
      calls.add(name);
      if (!name.equals("transaction") && !name.equals("end")) assertTrue(inside, name);
      if (name.equals(fail)) raise(failure);
    }

    static void raise(Throwable t) {
      if (t instanceof RuntimeException e) throw e;
      throw (Error) t;
    }

    public List<String> transaction(Lease l, Supplier<List<String>> work) {
      assertSame(expected, l);
      hit("transaction");
      inside = true;
      try {
        var result = work.get();
        hit("end");
        return result;
      } finally {
        inside = false;
      }
    }

    public Map<String, Object> fenced(Lease l, boolean admission) {
      assertSame(expected, l);
      assertTrue(admission);
      hit("fenced");
      return task;
    }

    public Map<String, String> messages(Map<String, Object> t) {
      assertSame(task, t);
      hit("messages");
      if (nullMap) return null;
      return new AbstractMap<>() {
        public Set<Entry<String, String>> entrySet() {
          throw new AssertionError("entries");
        }

        public Set<String> keySet() {
          throw new AssertionError("keys");
        }

        public Collection<String> values() {
          hit("values");
          return new AbstractCollection<>() {
            public int size() {
              throw new AssertionError("size");
            }

            public Iterator<String> iterator() {
              throw new AssertionError("direct traversal");
            }

            @SuppressWarnings("unchecked")
            public Stream<String> stream() {
              hit("stream");
              var stream = values.stream().peek(v -> hit("traverse"));
              return (Stream<String>)
                  Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {Stream.class},
                      (p, m, a) -> {
                        assertEquals("toList", m.getName());
                        hit("toList");
                        try {
                          return m.invoke(stream, a);
                        } catch (InvocationTargetException e) {
                          throw e.getCause();
                        }
                      });
            }
          };
        }
      };
    }
  }

  @Test
  void preservesReferencesOrderDuplicatesNullAndUnmodifiableListInsideTransaction() {
    var t = new Trace();
    assertTrue(t.calls.isEmpty());
    var result = service.pending(LEASE, t);
    assertEquals(t.values, result);
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "messages",
            "values",
            "stream",
            "toList",
            "traverse",
            "traverse",
            "traverse",
            "traverse",
            "end"),
        t.calls);
    assertThrows(UnsupportedOperationException.class, () -> result.add("x"));
    assertFalse(t.inside);
  }

  @Test
  void emptyMapStillMaterializesInsideCallback() {
    var t = new Trace();
    t.values = List.of();
    assertEquals(List.of(), service.pending(LEASE, t));
    assertEquals(
        List.of("transaction", "fenced", "messages", "values", "stream", "toList", "end"), t.calls);
  }

  @Test
  void operationFailuresRetainOriginalInstanceAndBlockAllLaterOperations() {
    var order =
        List.of("transaction", "fenced", "messages", "values", "stream", "toList", "traverse");
    for (String point : order)
      for (Throwable failure :
          List.of(
              new TaskFailure(409, "SYNTHETIC"),
              new IllegalStateException(point),
              new AssertionError(point))) {
        var t = new Trace();
        t.fail = point;
        t.failure = failure;
        Throwable actual = assertThrows(Throwable.class, () -> service.pending(LEASE, t));
        assertSame(failure, actual);
        assertEquals(order.subList(0, order.indexOf(point) + 1), t.calls);
        assertFalse(t.inside);
      }
  }

  @Test
  void nullMapFailsOnlyAtValuesAfterFencedAndMessages() {
    var t = new Trace();
    t.nullMap = true;
    assertThrows(NullPointerException.class, () -> service.pending(LEASE, t));
    assertEquals(List.of("transaction", "fenced", "messages"), t.calls);
  }

  @Test
  void nullLeaseReachesOriginalTransactionBeforeAnyFencedWork() {
    var t = new Trace();
    t.expected = null;
    t.fail = "transaction";
    t.failure = new NullPointerException("original directory member");
    assertSame(t.failure, assertThrows(NullPointerException.class, () -> service.pending(null, t)));
    assertEquals(List.of("transaction"), t.calls);
  }
}
