package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoProjections;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoProjectionServiceImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoProjectionServiceTest {
  private final TodoProjectionServiceImpl service = new TodoProjectionServiceImpl();

  private static final class Fixture implements TodoProjections<Fixture.Row> {
    final List<String> calls = new ArrayList<>();
    long record = 1, todo = 1;
    int version = 1, codecTodo = 1, snapshots;
    String digest = "digest", hash = "digest", expected = "snapshot";
    String fail;
    RuntimeException error = new IllegalStateException("original");
    List<String> snapshotValues = List.of("snapshot", "snapshot", "snapshot");
    final Row row = new Row();

    void call(String name) {
      calls.add(name);
      if (name.equals(fail)) throw error;
    }

    final class Row implements TodoProjections.Projection {
      public long recordVersion() {
        call("record");
        return record;
      }

      public long todoVersion() {
        call("todo");
        return todo;
      }

      public String snapshot() {
        call("snapshot" + (snapshots + 1));
        return snapshotValues.get(snapshots++);
      }

      public String digest() {
        call("digest");
        return digest;
      }
    }

    public Row projection() {
      call("query");
      return row;
    }

    public String hash(String value) {
      call("hash:" + value);
      return hash;
    }

    public Binding decode(String value) {
      call("decode:" + value);
      return new Binding() {
        public int version() {
          call("codecVersion");
          return version;
        }

        public int todoVersion() {
          call("codecTodo");
          return codecTodo;
        }
      };
    }

    public String expectedBinding() {
      call("contract");
      return expected;
    }
  }

  private void rejected(Fixture f, String code, String... calls) {
    var e = assertThrows(TaskFailure.class, () -> service.projection(f));
    assertEquals(code, e.code());
    assertEquals(List.of(calls), f.calls);
  }

  @Test
  void returnsOriginalRowAndReadsSnapshotThreeTimes() {
    var f = new Fixture();
    assertSame(f.row, service.projection(f));
    assertEquals(
        List.of(
            "query",
            "record",
            "todo",
            "snapshot1",
            "hash:snapshot",
            "digest",
            "snapshot2",
            "decode:snapshot",
            "codecVersion",
            "codecTodo",
            "contract",
            "snapshot3"),
        f.calls);
  }

  @Test
  void recordVersionShortCircuitsTodo() {
    var f = new Fixture();
    f.record = 2;
    rejected(f, "TODO_BINDING_UNSUPPORTED", "query", "record");
  }

  @Test
  void todoVersionShortCircuitsHash() {
    var f = new Fixture();
    f.todo = 2;
    rejected(f, "TODO_BINDING_UNSUPPORTED", "query", "record", "todo");
  }

  @Test
  void digestFailureSkipsParse() {
    var f = new Fixture();
    f.digest = null;
    rejected(
        f, "SNAPSHOT_CORRUPT", "query", "record", "todo", "snapshot1", "hash:snapshot", "digest");
  }

  @Test
  void codecVersionShortCircuitsSecondPath() {
    var f = new Fixture();
    f.version = 0;
    rejected(
        f,
        "TODO_BINDING_UNSUPPORTED",
        "query",
        "record",
        "todo",
        "snapshot1",
        "hash:snapshot",
        "digest",
        "snapshot2",
        "decode:snapshot",
        "codecVersion");
  }

  @Test
  void codecTodoShortCircuitsContract() {
    var f = new Fixture();
    f.codecTodo = 0;
    rejected(
        f,
        "TODO_BINDING_UNSUPPORTED",
        "query",
        "record",
        "todo",
        "snapshot1",
        "hash:snapshot",
        "digest",
        "snapshot2",
        "decode:snapshot",
        "codecVersion",
        "codecTodo");
  }

  @Test
  void finalComparisonUsesOriginalString() {
    var f = new Fixture();
    f.expected = "different";
    assertEquals(
        "SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, () -> service.projection(f)).code());
    assertEquals(3, f.snapshots);
  }

  @Test
  void eachSnapshotIsReadAtItsOriginalPoint() {
    var f = new Fixture();
    f.snapshotValues = List.of("hash-input", "decode-input", "expected");
    f.expected = "expected";
    assertSame(f.row, service.projection(f));
    assertTrue(f.calls.contains("hash:hash-input"));
    assertTrue(f.calls.contains("decode:decode-input"));
  }

  @Test
  void nullHashStillEvaluatesDigestArgument() {
    var f = new Fixture();
    f.hash = null;
    assertThrows(NullPointerException.class, () -> service.projection(f));
    assertEquals("digest", f.calls.getLast());
  }

  @Test
  void nullContractStillEvaluatesThirdSnapshotArgument() {
    var f = new Fixture();
    f.expected = null;
    assertThrows(NullPointerException.class, () -> service.projection(f));
    assertEquals("snapshot3", f.calls.getLast());
  }

  @Test
  void thirdSnapshotErrorPrecedesNullContractEquals() {
    var f = new Fixture();
    f.expected = null;
    f.fail = "snapshot3";
    assertSame(f.error, assertThrows(IllegalStateException.class, () -> service.projection(f)));
  }

  @Test
  void everyCallbackExceptionPropagatesWithoutCatch() {
    for (String at :
        List.of(
            "query",
            "record",
            "todo",
            "snapshot1",
            "hash:snapshot",
            "digest",
            "snapshot2",
            "decode:snapshot",
            "codecVersion",
            "codecTodo",
            "contract",
            "snapshot3")) {
      var f = new Fixture();
      f.fail = at;
      assertSame(f.error, assertThrows(IllegalStateException.class, () -> service.projection(f)));
      assertEquals(at, f.calls.getLast());
    }
  }

  @Test
  void taskFailureIsNotTranslated() {
    var f = new Fixture();
    f.fail = "query";
    f.error = new TaskFailure(404, "NOT_FOUND");
    assertSame(f.error, assertThrows(TaskFailure.class, () -> service.projection(f)));
  }

  @Test
  void repeatedInvocationDoesNotCacheQualification() {
    var f = new Fixture();
    service.projection(f);
    f.snapshots = 0;
    service.projection(f);
    assertEquals(2, f.calls.stream().filter("query"::equals).count());
    assertEquals(2, f.calls.stream().filter("contract"::equals).count());
  }
}
