package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords.Admission;
import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords.MediaBudget;
import io.github.lovexiaopeng.jimu.kernel.execution.service.impl.ExecutionAdmissionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 拒绝边界、惰性codec、首错和媒体结算顺序由纯Java规则负责。 */
class ExecutionAdmissionPolicyTest {
  private final List<String> calls = new ArrayList<>();
  private final ExecutionAdmissionServiceImpl service =
      new ExecutionAdmissionServiceImpl(
          (facts, bytes) -> calls.add("question:" + bytes),
          (facts, bytes) -> calls.add("todo:" + bytes),
          new io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService() {
            public void create(
                io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage s) {
              throw new AssertionError();
            }

            public void body(
                io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryStorage s,
                long bytes) {
              calls.add("session:" + bytes);
            }
          });

  private Admission task(Map<String, Object> values) {
    return (Admission)
        Proxy.newProxyInstance(
            Admission.class.getClassLoader(),
            new Class<?>[] {Admission.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (values.containsKey(name)) {
                Object value = values.get(name);
                if (value instanceof RuntimeException error) throw error;
                if (name.equals("requestBody")) {
                  if (value instanceof java.util.function.Supplier<?>) return value;
                  return (java.util.function.Supplier<String>) () -> (String) value;
                }
                return value;
              }
              if (List.of("question", "todo", "session", "directory").contains(name)) return false;
              if (name.endsWith("Storage")) return null;
              if (name.equals("directoryStorageBytes")) return 0L;
              if (name.equals("directoryRequest")) {
                calls.add("directory");
                return new io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords
                    .DirectoryRequest() {
                  public int version() {
                    return 2;
                  }

                  public String requestBody() {
                    return "";
                  }

                  public boolean messagesEmpty() {
                    return false;
                  }

                  public String lastRole() {
                    return "user";
                  }

                  public String lastContent() {
                    return "context";
                  }

                  public String expectedContext() {
                    return "context";
                  }

                  public String cwd() {
                    return "/";
                  }
                };
              }
              throw new AssertionError("Unexpected fact: " + name);
            });
  }

  @Test
  void questionRequestUsesUtf8AndInclusiveLimitBeforeCharging() {
    var t = task(Map.of("question", true));
    service.request(t, "🧱".repeat(16384));
    assertEquals(List.of("question:65536"), calls);
    calls.clear();
    var error = assertThrows(TaskFailure.class, () -> service.request(t, "a".repeat(65537)));
    assertEquals("MODEL_INPUT_TOO_LARGE", error.code());
    assertEquals(List.of(), calls);
  }

  @Test
  void todoDecodesBeforeLimitAndPreservesOriginalCodecFailure() {
    var original = new IllegalArgumentException("codec");
    var t = task(Map.of("todo", true, "requestBody", original));
    assertSame(
        original,
        assertThrows(IllegalArgumentException.class, () -> service.request(t, "a".repeat(131073))));
    assertEquals(List.of(), calls);
  }

  @Test
  void oversizedTodoCheckpointDoesNotDereferenceDecodedNullBody() {
    java.util.function.Supplier<String> body =
        () -> {
          throw new NullPointerException("wire");
        };
    var t = task(Map.of("todo", true, "requestBody", body));
    assertEquals(
        "MODEL_INPUT_TOO_LARGE",
        assertThrows(TaskFailure.class, () -> service.request(t, " ".repeat(131073))).code());
    assertThrows(NullPointerException.class, () -> service.request(t, "null"));
    assertEquals(List.of(), calls);
  }

  @Test
  void todoBothRequestLimitsAndDirectoryOrdering() {
    var t = task(Map.of("todo", true, "directory", true, "requestBody", "a".repeat(65536)));
    service.request(t, "a".repeat(131072));
    assertEquals(List.of("todo:131072", "directory"), calls);
    calls.clear();
    assertEquals(
        "MODEL_INPUT_TOO_LARGE",
        assertThrows(TaskFailure.class, () -> service.request(t, "a".repeat(131073))).code());
    assertEquals(List.of(), calls);
    assertThrows(
        TaskFailure.class,
        () ->
            service.request(task(Map.of("todo", true, "requestBody", "a".repeat(65537))), "valid"));
  }

  @Test
  void sessionUnsafeSummaryFailsBeforeSizeAndStorage() {
    var t = task(Map.of("session", true, "publicSummary", "redacted"));
    assertEquals(
        "SESSION_EVENT_UNSAFE",
        assertThrows(TaskFailure.class, () -> service.summary(t, "a".repeat(8193))).code());
    assertEquals(List.of(), calls);
  }

  @Test
  void summariesPreserveModuleCodesAndInclusiveByteLimits() {
    for (String module : List.of("question", "todo", "session", "directory")) {
      calls.clear();
      var values = new java.util.HashMap<String, Object>();
      values.put(module, true);
      values.put("publicSummary", "a".repeat(8192));
      values.put("directoryStorageBytes", 0L);
      service.summary(task(values), "a".repeat(8192));
      if (!module.equals("directory")) assertEquals(List.of(module + ":8192"), calls);
      calls.clear();
      values.put("publicSummary", "a".repeat(8193));
      String code =
          switch (module) {
            case "session" -> "SESSION_HISTORY_LIMIT";
            case "directory" -> "DIRECTORY_STORAGE_LIMIT";
            default -> "SUMMARY_TOO_LARGE";
          };
      assertEquals(
          code,
          assertThrows(TaskFailure.class, () -> service.summary(task(values), "a".repeat(8193)))
              .code());
      assertEquals(List.of(), calls);
    }
  }

  @Test
  void malformedUnicodeIsRejectedBeforeTodoStorage() {
    assertEquals(
        "TOOL_INPUT_INVALID",
        assertThrows(TaskFailure.class, () -> service.summary(task(Map.of("todo", true)), "\ud800"))
            .code());
    assertEquals(List.of(), calls);
  }

  private MediaBudget budget(long held) {
    return new MediaBudget() {
      public long limit() {
        return 20;
      }

      public long held() {
        return held;
      }

      public void hold(long bytes) {
        calls.add("hold:" + bytes);
      }

      public void reserve(String id, long bytes, String category, String state, String quality) {
        calls.add(id + ":" + bytes + ":" + category + ":" + state + ":" + quality);
      }

      public void settle(String id) {
        calls.add("settle:" + id);
      }
    };
  }

  @Test
  void mediaUsesOriginalReservationAndSettlementOrder() {
    service.media(task(Map.of("recordVersion", 3L, "lockMediaBudget", budget(16))), "s", "🧱");
    assertEquals(
        List.of(
            "hold:4",
            "s:media-checkpoint:4:MEDIA_CHECKPOINT:SETTLED:KNOWN",
            "settle:s:media-checkpoint"),
        calls);
  }

  @Test
  void mediaInsufficientStorageNeverMutatesAndOtherVersionsNeverReadBudget() {
    assertEquals(
        "STORAGE_BUDGET_EXCEEDED",
        assertThrows(
                TaskFailure.class,
                () ->
                    service.media(
                        task(Map.of("recordVersion", 3L, "lockMediaBudget", budget(17))),
                        "s",
                        "🧱"))
            .code());
    service.media(task(Map.of("recordVersion", 2L)), "s", "ignored");
    assertEquals(List.of(), calls);
  }

  private io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission leaseFacts(
      Map<String, Object> values) {
    var type = io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission.class;
    return (io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              calls.add(name);
              if (values.containsKey(name)) {
                var value = values.get(name);
                if (value instanceof RuntimeException failure) throw failure;
                return value;
              }
              if (method.getReturnType() == boolean.class) return name.equals("withinDeadline");
              return null;
            });
  }

  private final io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease =
      new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
          "t",
          "owner",
          "task",
          "attempt",
          1,
          "worker",
          java.time.Instant.MAX,
          "goal",
          List.of(),
          1);

  @Test
  void modelRejectionRemainsBeforeRunScopeAndPermissions() {
    var f = leaseFacts(Map.of("modelRejection", "MODEL_REJECTED"));
    assertEquals(
        "MODEL_REJECTED", assertThrows(TaskFailure.class, () -> service.lease(f, lease)).code());
    assertEquals(List.of("modelRejection"), calls);
  }

  @Test
  void originalAccessFailureRemainsBeforeOwnerFeatureAndDeadline() {
    var failure = new IllegalStateException("access");
    var f =
        leaseFacts(Map.of("access", failure, "todo", true, "owner", "other", "cancelled", true));
    assertSame(failure, assertThrows(IllegalStateException.class, () -> service.lease(f, lease)));
    assertEquals(
        List.of("modelRejection", "runRejection", "requireScope", "checkMaterials", "access"),
        calls);
  }

  @Test
  void todoOwnerFeatureExpiryThenCancellationOrderRemains() {
    var values = new java.util.HashMap<String, Object>();
    values.put("todo", true);
    values.put("owner", "other");
    values.put("cancelled", true);
    assertEquals(
        "INVOCATION_MISMATCH",
        assertThrows(TaskFailure.class, () -> service.lease(leaseFacts(values), lease)).code());
    values.put("owner", "owner");
    assertEquals(
        "TODO_FEATURE_DISABLED",
        assertThrows(TaskFailure.class, () -> service.lease(leaseFacts(values), lease)).code());
    values.put("todosEnabled", true);
    values.put("todoContentState", "PURGED");
    assertEquals(
        "TODO_CONTENT_EXPIRED",
        assertThrows(TaskFailure.class, () -> service.lease(leaseFacts(values), lease)).code());
    values.put("todoContentState", "RETAINED");
    assertEquals(
        "CANCEL_REQUESTED",
        assertThrows(TaskFailure.class, () -> service.lease(leaseFacts(values), lease)).code());
    values.put("cancelled", false);
    values.put("withinDeadline", false);
    assertEquals(
        "DEADLINE_EXCEEDED",
        assertThrows(TaskFailure.class, () -> service.lease(leaseFacts(values), lease)).code());
  }
}
