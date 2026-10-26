package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.directory.service.impl.DirectoryPersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Evidence;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 合成惰性端口验证原首错、引用、事务与结算短路，不代表物理文件证据。 */
class DirectoryPersistenceServiceTest {
  static final Lease LEASE =
      new Lease("tenant", "actor", "task", "attempt", 2, "worker", null, "goal", List.of(), 1);
  static final Call CALL = new Call("0:call", "call", "working_directory", "{}");

  static final class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Object[]> args = new HashMap<>();
    final Map<String, Object> answers = new HashMap<>();
    final Map<String, Object> task =
        map("tenant_id", "tenant", "id", "task", "tools_used", 0L, "max_tools", 20L);
    final Map<String, Object> p =
        map(
            "record_version",
            1L,
            "directory_version",
            1L,
            "path_policy_version",
            1L,
            "namespace_digest",
            Payloads.hash("tenant:task"),
            "binding_snapshot",
            "binding",
            "binding_digest",
            Payloads.hash("binding"),
            "revision",
            0L,
            "content_state",
            "PRESENT",
            "cwd",
            "/",
            "workspace_state",
            "READY");
    final Map<String, Object> i =
        map(
            "id",
            "inv",
            "step_id",
            "step",
            "installation_epoch",
            1L,
            "step_task",
            "task",
            "step_state",
            "RESPONDED",
            "record_version",
            1L,
            "step_version",
            1L,
            "response_snapshot",
            "response",
            "response_digest",
            Payloads.hash("response"),
            "input_snapshot",
            "{}",
            "input_digest",
            Payloads.hash("{}"),
            "provider_id",
            "call",
            "slot",
            "0:call",
            "tool",
            "working_directory",
            "plugin_version",
            "v",
            "plugin_digest",
            "d",
            "step_ordinal",
            1L,
            "state",
            "PENDING",
            "directory_slot_index",
            0L);
    final Map<String, Object> f =
        map(
            "record_version",
            1L,
            "path_policy_version",
            1L,
            "base_cwd",
            "/",
            "base_revision",
            0L,
            "content_state",
            "PRESENT",
            "binding_digest",
            Payloads.hash("binding"),
            "namespace_digest",
            Payloads.hash("tenant:task"),
            "step_id",
            "step",
            "task_id",
            "task",
            "slot_index",
            0L,
            "mode",
            "READ",
            "target_cwd",
            "/",
            "canonical_paths_snapshot",
            "[]",
            "canonical_paths_digest",
            Payloads.hash("[]"),
            "input_digest",
            Payloads.hash("{}"));
    boolean inside;
    String failAt;
    final RuntimeException sentinel = new IllegalStateException("original");
    final DirectoryRecords records =
        (DirectoryRecords)
            Proxy.newProxyInstance(
                DirectoryRecords.class.getClassLoader(),
                new Class<?>[] {DirectoryRecords.class},
                (proxy, method, a) -> {
                  String n = method.getName();
                  trace.add(n);
                  args.put(n, a);
                  if (n.equals(failAt)) throw sentinel;
                  if (n.equals("leaseTransaction") || n.equals("transaction")) {
                    assertFalse(inside);
                    inside = true;
                    try {
                      return ((Supplier<?>) a[n.equals("transaction") ? 0 : 1]).get();
                    } finally {
                      inside = false;
                      trace.add("end");
                    }
                  }
                  if (n.equals("fenced")) {
                    assertTrue(inside);
                    assertSame(LEASE, a[0]);
                    assertEquals(true, a[1]);
                    return row(task);
                  }
                  if (answers.containsKey(n)) return answers.get(n);
                  return switch (n) {
                    case "directoryTask" -> true;
                    case "projectionRow" -> row(p);
                    case "originalDirectoryInvocation",
                            "directoryCommitInvocation",
                            "directoryFileInvocation" ->
                        row(i);
                    case "originalDirectoryTask", "visible" -> row(task);
                    case "frozenDirectoryBinding" -> row(f);
                    case "existingDirectoryBinding" -> List.of(row(f));
                    case "tools" ->
                        new ToolSnapshot(
                            1, Map.of("working_directory", new PluginPin("v", "d", 1)));
                    case "response" ->
                        new DirectoryRecords.Response() {
                          public int version() {
                            return 2;
                          }

                          public List<DirectoryRecords.WireCall> calls() {
                            return List.of(
                                new DirectoryRecords.WireCall() {
                                  public String id() {
                                    return "call";
                                  }

                                  public String name() {
                                    return "working_directory";
                                  }

                                  public String arguments() {
                                    return "{}";
                                  }
                                });
                          }
                        };
                    case "input" -> (DirectoryRecords.Input) () -> null;
                    case "json" -> Payloads.json(a[0]);
                    case "hash" -> Payloads.hash((String) a[0]);
                    case "output" -> new WorkingDirectoryPort.Result("/");
                    case "bucket" -> row(map("tool_used", 0L, "tool_limit", 20L));
                    default ->
                        method.getReturnType() == int.class
                            ? 1
                            : method.getReturnType() == List.class ? List.of() : null;
                  };
                });
    final DirectoryPersistenceServiceImpl service = new DirectoryPersistenceServiceImpl(records);
  }

  static Map<String, Object> map(Object... values) {
    var result = new HashMap<String, Object>();
    for (int n = 0; n < values.length; n += 2) result.put((String) values[n], values[n + 1]);
    return result;
  }

  static Row row(Map<String, Object> values) {
    return MapperDirectoryRecords.wrap(values);
  }

  static String code(Runnable call) {
    return assertThrows(TaskFailure.class, call::run).code();
  }

  @Test
  void constructorsAndRowViewsAreLazyAndKeepOriginalMap() {
    new DirectoryPersistenceServiceImpl(null);
    new MapperDirectoryRecords(null);
    var poison =
        new HashMap<String, Object>() {
          @Override
          public Object get(Object k) {
            throw new AssertionError("pre-read");
          }
        };
    var wrapped = row(poison);
    assertSame(poison, MapperDirectoryRecords.unwrap(wrapped));
    var original = map("id", "first");
    var view = row(original);
    original.put("id", "second");
    assertEquals("second", view.id());
    view.directorySlotIndex(3);
    assertEquals(3, original.get("directory_slot_index"));
    assertThrows(ClassCastException.class, () -> row(map("id", 3)).id());
    assertThrows(NullPointerException.class, () -> row(Map.of()).revision());
  }

  @Test
  void snapshotGuardStopsBeforeProjectionInsideOriginalTransaction() {
    var h = new H();
    h.answers.put("directoryTask", false);
    assertEquals("DIRECTORY_NOT_AUTHORIZED", code(() -> h.service.directorySnapshot(LEASE)));
    assertEquals(List.of("leaseTransaction", "fenced", "directoryTask", "end"), h.trace);
  }

  @Test
  void readyHasNoAddedGuardIgnoresCountAndEndsWithFence() {
    var h = new H();
    h.p.put("workspace_state", "UNINITIALIZED");
    h.answers.put("readyWorkspace", 0);
    h.service.directoryReady(LEASE);
    assertFalse(h.trace.contains("directoryTask"));
    assertEquals(
        List.of("readyWorkspace", "todoCommitFence", "end"),
        h.trace.subList(h.trace.size() - 3, h.trace.size()));
    assertEquals(List.of("tenant", "task"), List.of(h.args.get("readyWorkspace")));
  }

  @Test
  void wrongToolFailsBeforeTransactionAndNullCallPreservesFirstError() {
    var h = new H();
    assertEquals(
        "DIRECTORY_COMMIT_REQUIRED",
        code(
            () ->
                h.service.freezeDirectory(
                    null, null, new Call(null, null, "read", null), null, null)));
    assertThrows(
        NullPointerException.class, () -> h.service.freezeDirectory(null, null, null, null, null));
    assertTrue(h.trace.isEmpty());
  }

  @Test
  void projectionVersionFailurePrecedesHashAndBinding() {
    var h = new H();
    h.p.put("record_version", 2L);
    assertEquals(
        "DIRECTORY_BINDING_UNSUPPORTED", code(() -> h.service.directoryProjection(row(h.task))));
    assertEquals(List.of("projectionRow"), h.trace);
  }

  @Test
  void originalSlotFreshTaskPinBeforeResponseAndMutatesOnlyAfterAllOrderChecks() {
    var h = new H();
    h.i.remove("directory_slot_index");
    var result = h.service.originalDirectorySlot(LEASE, "step", CALL, "v", "d");
    assertSame(h.i, MapperDirectoryRecords.unwrap(result));
    assertEquals(0, h.i.get("directory_slot_index"));
    assertEquals(
        List.of(
            "originalDirectoryInvocation",
            "originalDirectoryTask",
            "tools",
            "hash",
            "hash",
            "response",
            "unfinishedDirectoryStep"),
        h.trace);
    h = new H();
    h.i.remove("directory_slot_index");
    h.answers.put("unfinishedDirectoryStep", List.of(row(Map.of())));
    var blocked = h;
    assertEquals(
        "DIRECTORY_SLOT_ORDER",
        code(() -> blocked.service.originalDirectorySlot(LEASE, "step", CALL, "v", "d")));
    assertNull(h.i.get("directory_slot_index"));
  }

  @Test
  void pinMismatchStopsBeforeCorruptResponse() {
    var h = new H();
    h.i.put("response_snapshot", null);
    assertEquals(
        "PLUGIN_PIN_MISMATCH",
        code(() -> h.service.originalDirectorySlot(LEASE, "step", CALL, "wrong", "d")));
    assertEquals(List.of("originalDirectoryInvocation", "originalDirectoryTask", "tools"), h.trace);
  }

  @Test
  void responseStillScansLaterCallsBeforeMutatingMatchedSlot() {
    var h = new H();
    h.i.remove("directory_slot_index");
    h.answers.put(
        "response",
        new DirectoryRecords.Response() {
          public int version() {
            return 2;
          }

          public List<DirectoryRecords.WireCall> calls() {
            return List.of(
                new DirectoryRecords.WireCall() {
                  public String id() {
                    return "call";
                  }

                  public String name() {
                    return "working_directory";
                  }

                  public String arguments() {
                    return "{}";
                  }
                },
                new DirectoryRecords.WireCall() {
                  public String id() {
                    throw h.sentinel;
                  }

                  public String name() {
                    throw new AssertionError("after id failure");
                  }

                  public String arguments() {
                    throw new AssertionError("after id failure");
                  }
                });
          }
        });
    assertSame(
        h.sentinel,
        assertThrows(
            IllegalStateException.class,
            () -> h.service.originalDirectorySlot(LEASE, "step", CALL, "v", "d")));
    assertNull(h.i.get("directory_slot_index"));
    assertFalse(h.trace.contains("unfinishedDirectoryStep"));
  }

  @Test
  void successfulHistoricalFreezeSkipsCurrentRevisionButStillChecksWholeBinding() {
    var h = new H();
    h.p.put("revision", 2L);
    h.p.put("cwd", "/new");
    h.i.put("state", "SUCCEEDED");
    assertSame(
        h.f, MapperDirectoryRecords.unwrap(h.service.freezeDirectorySlot(row(h.task), row(h.i))));
    h.f.put("input_digest", "corrupt");
    assertEquals(
        "SNAPSHOT_CORRUPT", code(() -> h.service.freezeDirectorySlot(row(h.task), row(h.i))));
  }

  @Test
  void pendingFreezeRejectsRevisionAfterFullComparison() {
    var h = new H();
    h.p.put("revision", 1L);
    assertEquals(
        "DIRECTORY_REVISION_CONFLICT",
        code(() -> h.service.freezeDirectorySlot(row(h.task), row(h.i))));
    assertTrue(h.trace.contains("frozenDirectoryBinding"));
  }

  @Test
  void sourceAttemptUsesStrictSizeAndNochangeHasNoEventCheck() {
    var h = new H();
    h.i.put("state", "SUCCEEDED");
    h.i.put("output_snapshot", "out");
    h.i.put("output_digest", Payloads.hash("out"));
    h.f.putAll(
        map(
            "applied_revision",
            0L,
            "committed_attempt_id",
            "attempt",
            "committed_fence",
            2L,
            "changed",
            false));
    h.answers.put("directorySourceAttempt", List.of(row(Map.of()), row(Map.of())));
    assertEquals(
        "SNAPSHOT_CORRUPT",
        code(() -> h.service.directoryResultSource(row(h.task), row(h.i), row(h.f))));
    h.answers.put("directorySourceAttempt", List.of(row(Map.of())));
    h.service.directoryResultSource(row(h.task), row(h.i), row(h.f));
    assertFalse(h.trace.contains("directorySourceEvent"));
  }

  @Test
  void nochangeCommitRetainsCountOrderAndDoesNotCasOrEmitChanged() {
    var h = new H();
    var evidence = new Evidence(Payloads.hash("tenant:task"), "/", "a".repeat(64));
    assertEquals(
        new WorkingDirectoryPort.Result("/"), h.service.commitDirectory(LEASE, "inv", evidence));
    assertSame(evidence, h.args.get("json")[0]);
    assertFalse(h.trace.contains("advanceDirectoryRevision"));
    assertFalse(h.trace.contains("factSequence"));
    assertTrue(h.trace.indexOf("countDirectoryTool") < h.trace.indexOf("countDirectoryBudget"));
    assertTrue(
        h.trace.indexOf("completeDirectoryInvocation")
            < h.trace.indexOf("completeDirectoryBinding"));
    assertEquals(List.of("tenant", "inv"), List.of(h.args.get("settleDirectoryReservation")));
  }

  @Test
  void failedInvocationCountStopsBindingAndFinalFence() {
    var h = new H();
    h.answers.put("completeDirectoryInvocation", 0);
    assertEquals(
        "INVOCATION_MISMATCH",
        code(
            () ->
                h.service.commitDirectory(
                    LEASE,
                    "inv",
                    new Evidence(Payloads.hash("tenant:task"), "/", "a".repeat(64)))));
    assertFalse(h.trace.contains("completeDirectoryBinding"));
    assertFalse(h.trace.contains("todoCommitFence"));
  }

  @Test
  void ownerUuidBoundaryAndOriginalExceptionInstanceRemain() {
    var h = new H();
    assertEquals("NOT_FOUND", code(() -> h.service.getWorkingDirectory(null, "invalid")));
    assertThrows(NullPointerException.class, () -> h.service.getWorkingDirectory(null, null));
    assertTrue(h.trace.isEmpty());
    h.failAt = "fenced";
    assertSame(
        h.sentinel,
        assertThrows(IllegalStateException.class, () -> h.service.directorySnapshot(LEASE)));
    assertEquals(List.of("leaseTransaction", "fenced", "end"), h.trace);
  }
}
