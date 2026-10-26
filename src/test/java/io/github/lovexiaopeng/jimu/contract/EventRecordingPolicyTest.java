package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.event.port.EventRecords;
import io.github.lovexiaopeng.jimu.kernel.event.service.impl.EventRecordingServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 事件幂等、保留槽、模块错误优先级和跨模块记账的真实政策边界。 */
class EventRecordingPolicyTest {
  private final EventRecordingServiceImpl service = new EventRecordingServiceImpl();

  private static class Facts implements EventRecords {
    long seq;
    boolean existing, todo, directory, session;
    RuntimeException failure;
    List<String> calls = new ArrayList<>();

    public boolean exists(String fact) {
      return existing;
    }

    public boolean todo() {
      return todo;
    }

    public boolean directory() {
      return directory;
    }

    public boolean session() {
      return session;
    }

    public long sequence() {
      return seq;
    }

    public void advance() {
      calls.add("advance");
      seq++;
    }

    public void insert(long s, String f, String t, String c, String artifact) {
      calls.add("insert:" + s + ":" + artifact);
    }

    public void sessionFact(String fact) {
      calls.add("fact");
      if (failure != null) throw failure;
    }

    public void sessionAccount(boolean closing) {
      calls.add("session:" + closing);
    }

    public void directoryAccount(boolean closing) {
      calls.add("directory:" + closing);
    }

    public void todoAccount(boolean closing) {
      calls.add("todo:" + closing);
    }
  }

  @Test
  void replayDoesNotConsumeReservedSlotOrCharge() {
    var f = new Facts();
    f.existing = true;
    f.session = true;
    f.seq = 256;
    service.record(f, "terminal", "task.completed", null);
    assertEquals(List.of(), f.calls);
  }

  @Test
  void ordinaryAndReservedSlotsHaveExactBoundaryAndSessionErrorWins() {
    for (String type :
        List.of(
            "model.prepared",
            "session_history.content_deleted",
            "working_directory.content_deleted",
            "todo.content_deleted",
            "task.completed",
            "task.failed",
            "task.cancelled",
            "task.needs_reconciliation")) {
      int limit = type.equals("model.prepared") ? 254 : 256;
      var f = new Facts();
      f.session = true;
      f.todo = true;
      f.directory = true;
      f.seq = limit - 1;
      service.record(f, "x", type, null);
      assertEquals(limit, f.seq);
      f.calls.clear();
      assertEquals(
          "SESSION_HISTORY_LIMIT",
          assertThrows(TaskFailure.class, () -> service.record(f, "y", type, null)).code());
      assertEquals(List.of(), f.calls);
    }
  }

  @Test
  void directoryAndTodoPreserveTheirErrors() {
    var d = new Facts();
    d.directory = true;
    d.todo = true;
    d.seq = 254;
    assertEquals(
        "DIRECTORY_EXECUTION_LIMIT",
        assertThrows(TaskFailure.class, () -> service.record(d, "x", "model.prepared", null))
            .code());
    d.directory = false;
    assertEquals(
        "TODO_EXECUTION_LIMIT",
        assertThrows(TaskFailure.class, () -> service.record(d, "x", "model.prepared", null))
            .code());
  }

  @Test
  void insertPrecedesFactAndAllClosingCharges() {
    var f = new Facts();
    f.session = true;
    f.directory = true;
    f.todo = true;
    service.record(f, "terminal", "task.completed", null);
    assertEquals(
        List.of("advance", "insert:1:null", "fact", "session:true", "directory:true", "todo:true"),
        f.calls);
  }

  @Test
  void deletionClosingFlagBelongsOnlyToItsModule() {
    var f = new Facts();
    f.session = true;
    f.directory = true;
    f.todo = true;
    service.record(f, "x", "todo.content_deleted", null);
    assertEquals(
        List.of(
            "advance", "insert:1:null", "fact", "session:false", "directory:false", "todo:true"),
        f.calls);
  }

  @Test
  void originalFactFailurePreventsSubsequentCharges() {
    var f = new Facts();
    f.session = true;
    f.failure = new IllegalStateException("original");
    assertSame(
        f.failure,
        assertThrows(
            IllegalStateException.class, () -> service.record(f, "x", "model.prepared", null)));
    assertEquals(List.of("advance", "insert:1:null", "fact"), f.calls);
  }

  @Test
  void unscopedEventDoesNotUseModuleLimitAndRetainsArtifactId() {
    var f = new Facts();
    f.seq = 1000;
    service.record(f, "published:artifact", "artifact.created", null);
    assertEquals(List.of("advance", "insert:1001:artifact", "fact"), f.calls);
  }
}
