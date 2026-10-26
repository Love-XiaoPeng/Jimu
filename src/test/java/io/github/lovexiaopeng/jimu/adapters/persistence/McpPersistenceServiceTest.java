package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.impl.McpPersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** 合成首错/惰性/事务顺序；真实MySQL另由有界IT覆盖。 */
class McpPersistenceServiceTest {
  static final Lease LEASE =
      new Lease(
          "tenant-a",
          "alice",
          "00000000-0000-0000-0000-000000000001",
          "attempt",
          1,
          "w",
          null,
          "g",
          List.of(),
          1);
  static final String INV = "00000000-0000-0000-0000-000000000002";

  static class H {
    final List<String> trace = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    final Map<String, Object> row = new HashMap<>();
    final MapperMcpRecords codec = new MapperMcpRecords(null);
    final McpRecords.Task task = codec.bind(row);
    final McpRecords port = proxy(McpRecords.class);
    final McpPersistenceServiceImpl service = new McpPersistenceServiceImpl(port);
    final McpLedger.Profile profile = McpLifecycleValues.profile("h");
    final McpLedger.Server server =
        new McpLedger.Server(profile, Payloads.hash(Payloads.json(profile)));
    final McpLedger.Binding binding = new McpLedger.Binding(1, Map.of("controlled", server));
    String state = "RUNNING", tool = "list_mcp_resources", failAt;
    boolean inside, admission;
    final RuntimeException failure = new IllegalStateException("sentinel");

    H() {
      var env = new StandardEnvironment();
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "synthetic",
                  Map.of(
                      "jimu.host-id",
                      "h",
                      "jimu.mcp.enabled",
                      true,
                      "jimu.mcp.credentials.tenant-a.controlled.fixture.1",
                      "local-only")));
      codec.configure(new McpSettings(env));
      row.put("tenant_id", LEASE.tenant());
      row.put("id", LEASE.taskId());
      row.put("owner_actor_id", LEASE.actor());
      row.put("mcp_snapshot", Payloads.json(binding));
      row.put("mcp_digest", Payloads.hash(Payloads.json(binding)));
    }

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, a) -> {
                String n = m.getName();
                trace.add(n);
                if (n.equals(failAt)) throw failure;
                if (values.containsKey(n)) return values.get(n);
                return switch (n) {
                  case "leaseTransaction", "transaction" -> {
                    assertFalse(inside);
                    inside = true;
                    try {
                      yield ((Supplier<?>) a[n.equals("transaction") ? 0 : 1]).get();
                    } finally {
                      inside = false;
                      trace.add("end");
                    }
                  }
                  case "fenced" -> {
                    assertTrue(inside);
                    assertSame(LEASE, a[0]);
                    admission = (boolean) a[1];
                    yield task;
                  }
                  case "budget" -> proxy(McpRecords.Budget.class);
                  case "storageLimit" -> 2097152L;
                  case "storageHeld", "mediaCheckpointHeld" -> 0L;
                  case "selectMcpInvocation", "selectMcpListInvocation", "selectMcpProducer" ->
                      proxy(McpRecords.Invocation.class);
                  case "selectMcpReservation" -> proxy(McpRecords.Reservation.class);
                  case "tool" -> tool;
                  case "state" -> state;
                  case "amount" -> 1048576L;
                  case "countMcpIo", "countMcpCleanup", "countMcpReservations" -> 0;
                  case "nextMcpOrdinal" -> 7;
                  case "insertMcpIo",
                          "holdMcpStorage",
                          "insertMcpResult",
                          "insertMcpCursor",
                          "releaseMcpStorage",
                          "settleMcpReservation" ->
                      0;
                  case "respondMcpIo" -> 1;
                  case "reserveStorage", "commitTool" -> null;
                  case "selectMcpPage" -> List.of();
                  case "selectEnabledMcpServers", "selectMcpServer" ->
                      List.of(proxy(McpRecords.ServerRow.class));
                  case "profileSnapshot" -> Payloads.json(profile);
                  case "profileDigest" -> server.digest();
                  case "tenantId" -> "tenant-a";
                  case "serverId" -> "controlled";
                  case "epoch" -> 1L;
                  case "enabled" -> true;
                  case "publicBinding" -> {
                    assertFalse(inside);
                    yield binding;
                  }
                  default -> {
                    try {
                      yield m.invoke(codec, a);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                };
              });
    }

    void reject(String code, Runnable work) {
      assertEquals(code, assertThrows(TaskFailure.class, work::run).code());
    }

    void dispatch(boolean cleanup) {
      service.mcpDispatch(LEASE, INV, "resources/list", "r", "d", cleanup);
    }
  }

  @Test
  void constructionAndTaskRowsAreLazy() {
    var p =
        (McpRecords)
            Proxy.newProxyInstance(
                McpRecords.class.getClassLoader(),
                new Class<?>[] {McpRecords.class},
                (o, m, a) -> {
                  throw new AssertionError(m);
                });
    new McpPersistenceServiceImpl(p);
    var poison =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            throw new AssertionError(k);
          }
        };
    new MapperMcpRecords(null).bind(poison);
  }

  @Test
  void dispatchNormalAndCleanupPreserveOrderAndIgnoredInsertCount() {
    for (boolean c : List.of(false, true)) {
      var h = new H();
      assertEquals(7, h.service.mcpDispatch(LEASE, INV, "resources/list", "r", "d", c));
      assertEquals(!c, h.admission);
      assertEquals(
          c
              ? List.of(
                  "leaseTransaction",
                  "fenced",
                  "selectMcpInvocation",
                  "tool",
                  "state",
                  "countMcpIo",
                  "countMcpCleanup",
                  "nextMcpOrdinal",
                  "insertMcpIo",
                  "end")
              : List.of(
                  "leaseTransaction",
                  "fenced",
                  "selectMcpInvocation",
                  "tool",
                  "state",
                  "countMcpIo",
                  "nextMcpOrdinal",
                  "insertMcpIo",
                  "end"),
          h.trace);
    }
  }

  @Test
  void countThresholdShortCircuitsCleanupAndMax() {
    for (boolean c : List.of(false, true)) {
      var h = new H();
      h.values.put("countMcpIo", c ? 20 : 64);
      h.reject("MCP_LIMIT_EXCEEDED", () -> h.dispatch(c));
      assertFalse(h.trace.contains("nextMcpOrdinal"));
      assertFalse(h.trace.contains("countMcpCleanup"));
    }
  }

  @Test
  void cleanupDuplicateAndNullScalarStopBeforeInsert() {
    var h = new H();
    h.values.put("countMcpCleanup", 1);
    h.reject("MCP_LIMIT_EXCEEDED", () -> h.dispatch(true));
    assertFalse(h.trace.contains("nextMcpOrdinal"));
    H v = new H();
    v.values.put("countMcpIo", null);
    assertThrows(NullPointerException.class, () -> v.dispatch(false));
    assertFalse(h.trace.contains("nextMcpOrdinal"));
  }

  @Test
  void invocationToolIsFirstAndStateNullKeepsNpe() {
    var h = new H();
    h.tool = "other";
    h.reject("INVOCATION_STATE", () -> h.dispatch(false));
    assertFalse(h.trace.contains("state"));
    var v = new H();
    v.state = null;
    assertThrows(NullPointerException.class, () -> v.dispatch(false));
  }

  @Test
  void respondedUsesPlainTransactionAndExactOne() {
    for (int count : List.of(0, 1, 2)) {
      var h = new H();
      h.values.put("respondMcpIo", count);
      if (count == 1) h.service.mcpResponded(LEASE, INV, 1, null);
      else h.reject("INVOCATION_STATE", () -> h.service.mcpResponded(LEASE, INV, 1, "E"));
      assertEquals("transaction", h.trace.getFirst());
      assertFalse(h.admission);
      assertFalse(h.trace.contains("leaseTransaction"));
    }
  }

  @Test
  void reservationBudgetPrecedesFenceAndCountShortCircuitsBudgetFields() {
    var h = new H();
    h.values.put("countMcpReservations", 4);
    h.reject("STORAGE_BUDGET_EXCEEDED", () -> h.service.reserveMcpList(LEASE, INV));
    assertEquals(
        List.of(
            "leaseTransaction",
            "budget",
            "fenced",
            "selectMcpListInvocation",
            "tool",
            "state",
            "countMcpReservations",
            "end"),
        h.trace);
  }

  @Test
  void reservationHasOriginalHeldThenCheckpointReadAndIgnoredCount() {
    var h = new H();
    h.service.reserveMcpList(LEASE, INV);
    assertEquals(
        List.of(
            "leaseTransaction",
            "budget",
            "fenced",
            "selectMcpListInvocation",
            "tool",
            "state",
            "countMcpReservations",
            "storageLimit",
            "storageHeld",
            "mediaCheckpointHeld",
            "reserveStorage",
            "holdMcpStorage",
            "end"),
        h.trace);
  }

  @Test
  void serverLookupOccursAfterBindingTransaction() {
    var h = new H();
    assertSame(h.server, h.service.mcpServer(LEASE, "controlled"));
    assertEquals(List.of("publicBinding"), h.trace);
    h.reject("MCP_SERVER_NOT_AUTHORIZED", () -> h.service.mcpServer(LEASE, "missing"));
  }

  @Test
  void bindingEnabledAndDigestRejectionsAreLazy() {
    var h = new H();
    h.values.put("enabled", false);
    h.reject("MCP_NOT_CONFIGURED", () -> h.service.binding(null));
    assertEquals(List.of("enabled"), h.trace);
    var v = new H();
    v.row.put("mcp_digest", "bad");
    v.reject("SNAPSHOT_CORRUPT", () -> v.service.binding(v.task));
    assertFalse(v.trace.contains("readBinding"));
  }

  @Test
  void bindingPreservesRawObjectTenantAndOwnerReads() {
    var h = new H();
    assertEquals(h.binding, h.service.binding(h.task));
    assertTrue(h.trace.indexOf("profileSnapshot") < h.trace.indexOf("credential"));
    h.row.put("owner_actor_id", 42);
    h.reject("MCP_SERVER_NOT_AUTHORIZED", () -> h.service.binding(h.task));
  }

  @Test
  void bindValidatesProfileBeforeActorThenCredential() {
    var h = new H();
    assertEquals(h.binding, h.service.bind("tenant-a", "alice"));
    assertTrue(h.trace.indexOf("profile") < h.trace.indexOf("credential"));
    var v = new H();
    v.values.put("profileDigest", "bad");
    v.reject("SNAPSHOT_CORRUPT", () -> v.service.bind("tenant-a", "foreign"));
    assertFalse(v.trace.contains("credential"));
  }

  @Test
  void profileContractMismatchAndHostMismatchRetainCodes() {
    var h = new H();
    h.values.put("profileSnapshot", "{}");
    h.values.put("profileDigest", Payloads.hash("{}"));
    h.reject("MCP_SNAPSHOT_MISMATCH", () -> h.service.bind("tenant-a", "alice"));
    var v = new H();
    v.values.put("hostId", "other");
    v.reject("MCP_SNAPSHOT_MISMATCH", () -> v.service.binding(v.task));
  }

  @Test
  void replayReturnsBeforeSnapshotAndCommit() {
    var h = new H();
    h.state = "SUCCEEDED";
    h.values.put("outputSnapshot", "o");
    h.values.put("outputDigest", Payloads.hash("o"));
    h.service.mcpSucceeded(LEASE, INV, "o", null);
    assertFalse(h.trace.contains("newContracts"));
    assertFalse(h.trace.contains("commitTool"));
    h.reject("SNAPSHOT_CORRUPT", () -> h.service.mcpSucceeded(LEASE, INV, "different", null));
  }

  @Test
  void nullSnapshotCommitsWithoutStorageAndNoNewTransaction() {
    var h = new H();
    h.service.mcpSucceeded(LEASE, INV, "o", null);
    assertEquals(
        List.of(
            "leaseTransaction",
            "fenced",
            "selectMcpProducer",
            "tool",
            "state",
            "state",
            "commitTool",
            "end"),
        h.trace);
  }

  @Test
  void wrongPageCountStopsBeforeAnyFieldRead() {
    var h = new H();
    h.reject("MCP_CURSOR_INVALID", () -> h.service.mcpPage(LEASE, "q", "c"));
    assertFalse(h.trace.contains("readList"));
  }

  @Test
  void originalExceptionsKeepIdentityAndStopLaterWork() {
    for (String at :
        List.of("fenced", "selectMcpInvocation", "countMcpIo", "nextMcpOrdinal", "insertMcpIo")) {
      var h = new H();
      h.failAt = at;
      assertSame(h.failure, assertThrows(RuntimeException.class, () -> h.dispatch(false)));
      assertEquals("end", h.trace.getLast());
    }
  }

  @Test
  void nullLeaseEntersTransactionBeforeMemberAccess() {
    var h = new H();
    h.failAt = "leaseTransaction";
    assertSame(h.failure, assertThrows(RuntimeException.class, () -> h.service.mcpBinding(null)));
    assertEquals(List.of("leaseTransaction"), h.trace);
  }
}
