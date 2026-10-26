package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRecords;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoPersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 原引用、首错、完整扫描、重放及事务/计数边界的定向用例。 */
class TodoPersistenceServiceTest {
  static final Lease LEASE =
      new Lease("t", "a", "task", "attempt", 2, "w", null, "g", List.of(), 1);
  static final Call CALL = new Call("0:c", "c", "todo_write", "{\"todos\":[]}");
  static final TodoPort.Result RESULT =
      new TodoPort.Result(List.of(), new TodoPort.Counts(0, 0, 0));

  static Map<String, Object> map(Object... v) {
    var m = new HashMap<String, Object>();
    for (int i = 0; i < v.length; i += 2) m.put((String) v[i], v[i + 1]);
    return m;
  }

  static class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Object[]> args = new HashMap<>();
    final Map<String, Object> answers = new HashMap<>();
    final Map<String, Object> task = map("tools_used", 0, "max_tools", 2);
    final Map<String, Object> p = map("revision", 0);
    final Map<String, Object> i =
        map(
            "tool",
            "todo_write",
            "provider_id",
            "c",
            "input_snapshot",
            CALL.input(),
            "input_digest",
            Payloads.hash(CALL.input()),
            "step_task",
            "task",
            "step_state",
            "RESPONDED",
            "response_snapshot",
            "response",
            "response_digest",
            Payloads.hash("response"),
            "record_version",
            1,
            "step_version",
            1,
            "plugin_version",
            "v",
            "plugin_digest",
            "d",
            "installation_epoch",
            1,
            "todo_expected_revision",
            0,
            "state",
            "PENDING",
            "id",
            "inv");
    String failAt;
    RuntimeException failure = new IllegalStateException("sentinel");
    int callsRead;
    final TodoRecords records =
        (TodoRecords)
            Proxy.newProxyInstance(
                TodoRecords.class.getClassLoader(),
                new Class<?>[] {TodoRecords.class},
                (proxy, m, a) -> {
                  String n = m.getName();
                  trace.add(n);
                  args.put(n, a);
                  if (n.equals(failAt)) throw failure;
                  if (n.equals("leaseTransaction")
                      || n.equals("session")
                      || n.equals("transaction"))
                    return ((Supplier<?>) a[n.equals("transaction") ? 0 : 1]).get();
                  if (answers.containsKey(n)) return answers.get(n);
                  return switch (n) {
                    case "fenced", "visible" -> MapperTodoRecords.wrap(task);
                    case "todoTask" -> true;
                    case "todoProjection" -> MapperTodoRecords.wrap(p);
                    case "todoCandidates" -> List.of(MapperTodoRecords.wrap(i));
                    case "hash" -> Payloads.hash((String) a[0]);
                    case "json" -> Payloads.json(a[0]);
                    case "input", "output" -> RESULT;
                    case "bytes" ->
                        ((String) a[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    case "tools" ->
                        new ToolSnapshot(1, Map.of("todo_write", new PluginPin("v", "d", 1)));
                    case "response" ->
                        new TodoRecords.Response() {
                          public int version() {
                            return 2;
                          }

                          public List<TodoRecords.WireCall> calls() {
                            callsRead++;
                            return List.of(
                                new TodoRecords.WireCall() {
                                  public String id() {
                                    return "c";
                                  }

                                  public String name() {
                                    return "todo_write";
                                  }

                                  public String arguments() {
                                    return CALL.input();
                                  }
                                });
                          }
                        };
                    case "bucket" -> MapperTodoRecords.wrap(map("tool_used", 0, "tool_limit", 2));
                    case "factSequence" -> 7L;
                    case "countTodoTool",
                            "countTodoBudget",
                            "settleTodoReservation",
                            "completeTodoInvocation",
                            "advanceTodoRevision" ->
                        1;
                    default -> null;
                  };
                });
    final TodoPersistenceServiceImpl service = new TodoPersistenceServiceImpl(records);

    Object replace() {
      return service.replaceOrResult(LEASE, "step", CALL, "v", "d");
    }
  }

  static String code(Runnable r) {
    return assertThrows(TaskFailure.class, r::run).code();
  }

  @Test
  void constructionAndRowsRemainLazyAndKeepOriginalMap() {
    var h = new H();
    assertTrue(h.trace.isEmpty());
    var row = MapperTodoRecords.wrap(h.i);
    assertSame(h.i, MapperTodoRecords.unwrap(row));
    h.i.put("tool", 9);
    assertThrows(ClassCastException.class, row::tool);
    h.i.put("revision", null);
    assertThrows(NullPointerException.class, row::revision);
    h.i.put("revision", 1.9);
    assertEquals(1, row.revision());
  }

  @Test
  void admissionPrecedesProjectionAndCallDereference() {
    var h = new H();
    h.answers.put("todoTask", false);
    assertEquals(
        "TODO_NOT_AUTHORIZED",
        code(() -> h.service.replaceOrResult(LEASE, null, null, null, null)));
    assertEquals(List.of("leaseTransaction", "session", "fenced", "todoTask"), h.trace);
    assertSame(LEASE, h.args.get("fenced")[0]);
    assertEquals(true, h.args.get("fenced")[1]);
  }

  @Test
  void candidateSizeExactlyOnePrecedesRowFields() {
    var h = new H();
    h.answers.put(
        "todoCandidates", List.of(MapperTodoRecords.wrap(h.i), MapperTodoRecords.wrap(h.i)));
    h.i.put("tool", 99);
    assertEquals("INVOCATION_MISMATCH", code(() -> h.replace()));
    assertEquals(List.of("t", "task", "step", "0:c"), List.of(h.args.get("todoCandidates")));
  }

  @Test
  void originalMemberErrorsAndResponseScanArePreserved() {
    var h = new H();
    h.i.put("input_snapshot", 1);
    assertThrows(ClassCastException.class, h::replace);
    assertTrue(!h.trace.contains("response"));
    var valid = new H();
    valid.i.put("todo_expected_revision", null);
    assertEquals("TODO_BINDING_UNSUPPORTED", code(() -> valid.replace()));
    assertEquals(3, valid.callsRead);
    assertTrue(!valid.trace.contains("input"));
  }

  @Test
  void successKeepsOrderedAccountingCasAndFinalFence() {
    var h = new H();
    assertSame(RESULT, h.replace());
    var names =
        List.of(
            "bucket",
            "output",
            "todoStorage",
            "countTodoTool",
            "countTodoBudget",
            "reserve",
            "settleTodoReservation",
            "event",
            "factSequence",
            "completeTodoInvocation",
            "advanceTodoRevision",
            "todoCommitFence");
    int at = -1;
    for (String name : names) {
      int next = h.trace.subList(at + 1, h.trace.size()).indexOf(name);
      assertTrue(next >= 0, name);
      at += next + 1;
    }
    assertEquals(
        List.of("t", "task", "inv", "inv", "TOOL", 1L, "SETTLED", "KNOWN"),
        List.of(h.args.get("reserve")));
    assertEquals(
        1L,
        h.args.get("advanceTodoRevision")[0] == null
            ? -1L
            : ((Number) h.args.get("completeTodoInvocation")[2]).longValue());
  }

  @Test
  void replayValidatesSourceThenReReadsOutputAfterFenceAndAvoidsCharge() {
    var h = new H();
    h.i.put("state", "SUCCEEDED");
    h.i.put("output_snapshot", Payloads.json(RESULT));
    assertSame(RESULT, h.replace());
    assertTrue(h.trace.indexOf("todoResultSource") < h.trace.indexOf("todoCommitFence"));
    assertTrue(h.trace.indexOf("todoCommitFence") < h.trace.indexOf("output"));
    assertTrue(!h.trace.contains("bucket"));
    h = new H();
    h.i.put("state", "SUCCEEDED");
    h.failAt = "todoResultSource";
    assertSame(h.failure, assertThrows(IllegalStateException.class, h::replace));
  }

  @Test
  void revisionBudgetAndUpdateFailuresKeepOriginalShortCircuit() {
    var h = new H();
    h.p.put("revision", 2);
    assertEquals("TODO_REVISION_CONFLICT", code(() -> h.replace()));
    assertTrue(!h.trace.contains("bucket"));
    var ignored = new H();
    ignored.answers.put("countTodoTool", 0);
    assertSame(RESULT, ignored.replace());
    var v = new H();
    v.answers.put("completeTodoInvocation", 0);
    assertEquals("INVOCATION_MISMATCH", code(() -> v.replace()));
    assertTrue(!v.trace.contains("advanceTodoRevision"));
  }

  @Test
  void matchedCallStillScansLaterWireCalls() {
    var h = new H();
    h.answers.put(
        "response",
        new TodoRecords.Response() {
          public int version() {
            return 2;
          }

          public List<TodoRecords.WireCall> calls() {
            return new java.util.AbstractList<>() {
              public int size() {
                return 2;
              }

              public TodoRecords.WireCall get(int index) {
                if (index == 1) throw h.failure;
                return new TodoRecords.WireCall() {
                  public String id() {
                    return "c";
                  }

                  public String name() {
                    return "todo_write";
                  }

                  public String arguments() {
                    return CALL.input();
                  }
                };
              }
            };
          }
        });
    assertSame(h.failure, assertThrows(IllegalStateException.class, h::replace));
    assertTrue(!h.trace.contains("tools"));
  }

  @Test
  void purgedOwnerChecksSourceAndCountsWithoutDecodingOutput() {
    var h = new H();
    String id = "00000000-0000-0000-0000-000000000001";
    h.answers.put(
        "todoOwnerView",
        MapperTodoRecords.wrap(
            map(
                "task_id",
                id,
                "next_event_seq",
                9,
                "revision",
                1,
                "content_state",
                "PURGED",
                "retain_until",
                java.time.LocalDateTime.of(2030, 1, 1, 0, 0),
                "invocation_task",
                id,
                "source_step_id",
                "step",
                "invocation_step",
                "step",
                "todo_applied_revision",
                1,
                "committed_attempt_id",
                "attempt",
                "todo_committed_attempt_id",
                "attempt",
                "committed_fence",
                2,
                "todo_committed_fence",
                2,
                "updated_seq",
                7,
                "todo_event_seq",
                7,
                "output_digest",
                "hash",
                "invocation_digest",
                "hash",
                "pending_count",
                0,
                "in_progress_count",
                0,
                "completed_count",
                2,
                "source_invocation_id",
                "inv",
                "provider_id",
                "c",
                "attempt_id",
                "attempt",
                "todo_committed_at",
                java.time.LocalDateTime.of(2029, 1, 1, 0, 0),
                "output_snapshot",
                99)));
    var result = h.service.getTodos(new Identity("t", "a"), id);
    assertEquals("PURGED", result.get("state"));
    assertEquals(new TodoPort.Counts(0, 0, 2), result.get("counts"));
    assertTrue(h.trace.contains("todoResultSource"));
    assertTrue(!h.trace.contains("output"));
    assertEquals("view", h.trace.getLast());
  }

  @Test
  void ownerUuidCatchAndAbsentViewOrderAndFinalGate() {
    var h = new H();
    assertEquals("NOT_FOUND", code(() -> h.service.getTodos(null, "invalid")));
    assertTrue(h.trace.isEmpty());
    assertThrows(NullPointerException.class, () -> h.service.getTodos(null, null));
    String id = "00000000-0000-0000-0000-000000000001";
    h.answers.put("todoOwnerView", MapperTodoRecords.wrap(map("next_event_seq", 4)));
    var result = h.service.getTodos(new Identity("t", "a"), id);
    assertEquals(
        List.of(
            "version",
            "taskId",
            "asOfSeq",
            "revision",
            "state",
            "todos",
            "counts",
            "source",
            "retainUntil"),
        new ArrayList<>(result.keySet()));
    assertEquals("ABSENT", result.get("state"));
    assertSame(result, h.args.get("view")[0]);
    assertTrue(!h.trace.contains("todoResultSource"));
    assertEquals("view", h.trace.getLast());
  }
}
