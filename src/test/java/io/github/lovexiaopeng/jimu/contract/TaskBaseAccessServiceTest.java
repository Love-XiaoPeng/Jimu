package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskBaseAccess;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskBaseAccessServiceImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskBaseAccessServiceTest {
  private final TaskBaseAccessServiceImpl service = new TaskBaseAccessServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Object> values = new HashMap<>();
  private final List<String> caps = List.of("z", "a", "z", "");
  private final TaskBaseAccess records =
      (TaskBaseAccess)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {TaskBaseAccess.class},
              (proxy, method, args) -> {
                String name = method.getName();
                calls.add(name);
                if (name.equals("epoch")) {
                  assertEquals(new Identity("tenant", "owner"), args[0]);
                  assertSame(caps, args[1]);
                }
                Object value = values.get(name);
                if (value instanceof RuntimeException failure) throw failure;
                if (value instanceof Supplier<?> supplier) return supplier.get();
                if (values.containsKey(name)) return value;
                return switch (name) {
                  case "tenant" -> "tenant";
                  case "owner" -> "owner";
                  case "capabilities" -> caps;
                  case "epoch", "installationEpoch" -> 5L;
                  default -> method.getReturnType() == boolean.class ? false : null;
                };
              });

  @Test
  void independentModulesThenQuestionElseThenRepeatedCapabilities() {
    for (String name : List.of("sessionTask", "directoryTask", "todoTask")) values.put(name, true);
    service.require(records);
    assertEquals(
        List.of(
            "tenant",
            "owner",
            "capabilities",
            "epoch",
            "sessionTask",
            "sessionAccess",
            "directoryTask",
            "directoryAccess",
            "todoTask",
            "todoAccess",
            "questionTask",
            "installationEpoch",
            "capabilities",
            "hasMcpSnapshot",
            "hasMcpDigest"),
        calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "owner", "capabilities"})
  void initialArgumentFailurePrecedesEpochAndAllLocks(String name) {
    var failure = new IllegalStateException(name);
    values.put(name, failure);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> service.require(records)));
    assertFalse(calls.contains("epoch"));
    assertEquals(name, calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"epoch", "sessionAccess", "directoryAccess", "todoAccess", "questionAccess"})
  void qualificationsStopAtOriginalFailure(String name) {
    for (String module : List.of("sessionTask", "directoryTask", "todoTask", "questionTask"))
      values.put(module, true);
    var failure = new TaskFailure(410, "ORIGINAL");
    values.put(name, failure);
    assertSame(failure, assertThrows(TaskFailure.class, () -> service.require(records)));
    assertEquals(name, calls.getLast());
  }

  @Test
  void successfulQuestionSkipsTaskEpochEvenAfterOtherModules() {
    for (String module : List.of("sessionTask", "directoryTask", "todoTask", "questionTask"))
      values.put(module, true);
    values.put("installationEpoch", new NullPointerException("must stay lazy"));
    service.require(records);
    assertFalse(calls.contains("installationEpoch"));
    assertEquals(1, calls.stream().filter("questionAccess"::equals).count());
  }

  @Test
  void nonQuestionEpochMismatchStopsBeforeMcp() {
    values.put("sessionTask", true);
    values.put("installationEpoch", 6L);
    var failure = assertThrows(TaskFailure.class, () -> service.require(records));
    assertEquals(403, failure.status());
    assertEquals("CAPABILITY_REVOKED", failure.code());
    assertEquals("installationEpoch", calls.getLast());
  }

  @Test
  void conditionalEpochFailureIsNotCaught() {
    var failure = new NullPointerException("raw Number");
    values.put("installationEpoch", failure);
    assertSame(failure, assertThrows(NullPointerException.class, () -> service.require(records)));
    assertEquals("installationEpoch", calls.getLast());
  }

  private void mcp(List<String> last) {
    int[] count = {0};
    values.put("capabilities", (Supplier<List<String>>) () -> ++count[0] == 1 ? caps : last);
  }

  @Test
  void mcpRereadsCapabilitiesAndDoesNotInspectRawColumns() {
    mcp(McpLedger.NAMES);
    service.require(records);
    assertEquals(3, calls.stream().filter("capabilities"::equals).count());
    assertEquals("mcpBinding", calls.getLast());
    assertFalse(calls.contains("hasMcpSnapshot"));
  }

  @Test
  void mixedMcpRejectsBeforeBinding() {
    mcp(List.of(McpLedger.NAMES.getFirst(), "other"));
    var failure = assertThrows(TaskFailure.class, () -> service.require(records));
    assertEquals(409, failure.status());
    assertEquals("SNAPSHOT_CORRUPT", failure.code());
    assertEquals(3, calls.stream().filter("capabilities"::equals).count());
    assertFalse(calls.contains("mcpBinding"));
  }

  @Test
  void mcpBindingFailurePropagatesUnchanged() {
    mcp(McpLedger.NAMES);
    var failure = new TaskFailure(409, "MCP_SNAPSHOT_CORRUPT");
    values.put("mcpBinding", failure);
    assertSame(failure, assertThrows(TaskFailure.class, () -> service.require(records)));
    assertEquals("mcpBinding", calls.getLast());
  }

  @Test
  void thirdCapabilitiesReadIsNotReusedFromAnyMatch() {
    int[] count = {0};
    values.put(
        "capabilities",
        (Supplier<List<String>>)
            () ->
                switch (++count[0]) {
                  case 1 -> caps;
                  case 2 -> McpLedger.NAMES;
                  default -> List.of("other");
                });
    assertEquals(
        "SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, () -> service.require(records)).code());
    assertFalse(calls.contains("mcpBinding"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"hasMcpSnapshot", "hasMcpDigest"})
  void rawNonNullCheckStopsAtFirstPresentColumn(String column) {
    values.put(column, true);
    assertEquals(
        "SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, () -> service.require(records)).code());
    assertEquals(column, calls.getLast());
    assertEquals(2, calls.stream().filter("capabilities"::equals).count());
  }

  @Test
  void rawColumnExceptionIsNotCaughtOrDecoded() {
    var failure = new ClassCastException("map access sentinel");
    values.put("hasMcpSnapshot", failure);
    assertSame(failure, assertThrows(ClassCastException.class, () -> service.require(records)));
    assertFalse(calls.contains("hasMcpDigest"));
  }
}
