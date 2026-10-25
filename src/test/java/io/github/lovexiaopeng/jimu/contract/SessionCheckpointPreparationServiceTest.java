package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCheckpointPreparation;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionCheckpointPreparationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionCheckpointPreparationServiceTest {
  private final List<String> calls = new ArrayList<>();
  private boolean scope, empty;
  private String role = "user", content = "中🧱", requestBody = "body", fail;
  private final SessionCheckpointPreparation.Checkpoint wire =
      new SessionCheckpointPreparation.Checkpoint() {
        public String requestBody() {
          calls.add("requestBody");
          return requestBody;
        }

        public boolean messagesEmpty() {
          calls.add("empty");
          return empty;
        }

        public String lastRole() {
          calls.add("role");
          return role;
        }

        public String lastContent() {
          calls.add("content");
          return content;
        }
      };
  private final SessionCheckpointPreparation.History history =
      new SessionCheckpointPreparation.History() {
        public long nextSeq() {
          calls.add("nextSeq");
          return 0;
        }

        public String bindingDigest() {
          calls.add("bindingDigest");
          return null;
        }
      };

  private SessionCheckpointPreparation storage() {
    return (SessionCheckpointPreparation)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionCheckpointPreparation.class},
            (proxy, method, args) -> {
              String name = method.getName();
              calls.add(name);
              if (name.equals(fail)) throw new IllegalStateException(name);
              return switch (name) {
                case "decode" -> wire;
                case "context" -> "中🧱";
                case "lockHistory" -> history;
                case "scopeTask" -> scope;
                case "requireScope" -> (SessionCheckpointPreparation.Scope) () -> "authority";
                case "updateStep" -> {
                  assertEquals(-1L, args[0]);
                  assertEquals("hash:中🧱", args[1]);
                  assertEquals(null, args[2]);
                  yield null;
                }
                case "body" -> {
                  assertEquals(
                      scope && calls.stream().filter("body"::equals).count() == 1 ? 7L : 3L,
                      args[0]);
                  yield null;
                }
                default -> null;
              };
            });
  }

  private void prepare(String request) {
    new SessionCheckpointPreparationServiceImpl(
            value -> {
              calls.add("hash");
              return "hash:" + value;
            })
        .prepare(storage(), request);
  }

  @Test
  void nativePreservesLazyFieldAndHashOrderWithNegativeUpperAndNullBinding() {
    prepare("req");
    assertEquals(
        List.of(
            "decode",
            "requestBody",
            "empty",
            "role",
            "content",
            "context",
            "lockHistory",
            "nextSeq",
            "content",
            "hash",
            "bindingDigest",
            "updateStep",
            "scopeTask",
            "body"),
        calls);
  }

  @Test
  void scopeInsertsBeforeContextBodyAndRequestBody() {
    scope = true;
    prepare("req");
    assertEquals(
        List.of("scopeTask", "content", "requireScope", "insertScope", "body", "body"),
        calls.subList(12, calls.size()));
  }

  @Test
  void requestOverflowSkipsNullBodyAndAllMessageAccess() {
    requestBody = null;
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> prepare("a".repeat(131073))).code());
    assertEquals(List.of("decode"), calls);
  }

  @Test
  void bodyOverflowSkipsNullMessages() {
    requestBody = "a".repeat(65537);
    assertEquals(429, assertThrows(TaskFailure.class, () -> prepare("req")).status());
    assertEquals(List.of("decode", "requestBody"), calls);
  }

  @Test
  void exactByteLimitsReachMessageCheck() {
    empty = true;
    requestBody = "中".repeat(21845) + "a";
    assertEquals(
        "SESSION_BOUNDARY_CONFLICT",
        assertThrows(TaskFailure.class, () -> prepare("a".repeat(131072))).code());
    assertEquals(List.of("decode", "requestBody", "empty"), calls);
  }

  @Test
  void wrongRoleSkipsNullContentAndContext() {
    role = "assistant";
    content = null;
    assertThrows(TaskFailure.class, () -> prepare("req"));
    assertEquals(List.of("decode", "requestBody", "empty", "role"), calls);
  }

  @Test
  void nullRoleFailsBeforeContentAndContext() {
    role = null;
    assertThrows(NullPointerException.class, () -> prepare("req"));
    assertEquals("role", calls.getLast());
  }

  @Test
  void nullContentStillEvaluatesContextBeforeEqualsNullFailure() {
    content = null;
    assertThrows(NullPointerException.class, () -> prepare("req"));
    assertEquals("context", calls.getLast());
  }

  @Test
  void mismatchSkipsHistoryAndQualification() {
    content = "different";
    assertThrows(TaskFailure.class, () -> prepare("req"));
    assertEquals("context", calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "decode",
        "context",
        "lockHistory",
        "updateStep",
        "requireScope",
        "insertScope",
        "body"
      })
  void failureStopsAtOriginalStage(String point) {
    scope = true;
    fail = point;
    assertThrows(IllegalStateException.class, () -> prepare("req"));
    assertEquals(point, calls.getLast());
  }
}
