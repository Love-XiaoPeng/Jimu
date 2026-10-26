package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.impl.McpPersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 分页完整性规则及原catch范围的增量合成证明。 */
class McpListValidationTest {
  private final McpPersistenceServiceTest.H h = new McpPersistenceServiceTest.H();

  private McpLedger.ListSnapshot valid(boolean templates) {
    String name = templates ? "list_mcp_resource_templates" : "list_mcp_resources";
    h.tool = name;
    var pin = new ExecutionLedger.PluginPin("0.1.0", Payloads.hash("pin"), 1);
    h.values.put("tools", new ExecutionLedger.ToolSnapshot(1, Map.of(name, pin)));
    h.values.put(
        "selectMcpReservation",
        new McpRecords.Reservation() {
          public String state() {
            return "RESERVED";
          }

          public long amount() {
            return 1048576;
          }
        });
    String query =
        Payloads.hash(
            Payloads.json(
                new McpLedger.Query(
                    templates ? "resources/templates/list" : "resources/list",
                    "controlled",
                    h.server.digest(),
                    McpContracts.DIGEST,
                    pin.version(),
                    pin.digest(),
                    pin.installationEpoch())));
    return McpLifecycleValues.list(
        McpPersistenceServiceTest.LEASE.taskId(),
        McpPersistenceServiceTest.INV,
        (String) h.row.get("mcp_digest"),
        query,
        h.server,
        templates);
  }

  private void succeed(McpLedger.ListSnapshot value) {
    h.service.mcpSucceeded(
        McpPersistenceServiceTest.LEASE,
        McpPersistenceServiceTest.INV,
        value.pages().getFirst(),
        value);
  }

  private McpLedger.ListSnapshot page(
      McpLedger.ListSnapshot value, int index, Consumer<ObjectNode> mutation) {
    var pages = new ArrayList<>(value.pages());
    var node = Payloads.read(pages.get(index), ObjectNode.class);
    mutation.accept(node);
    pages.set(index, node.toString());
    return new McpLedger.ListSnapshot(
        value.version(),
        value.tenant(),
        value.owner(),
        value.taskId(),
        value.rootInvocation(),
        value.server(),
        value.method(),
        value.bindingDigest(),
        value.queryDigest(),
        value.projectionDigest(),
        pages,
        value.cursorPositions());
  }

  @Test
  void bothListKindsValidateThenStoreAndCommitInOriginalOrder() {
    for (boolean templates : List.of(false, true)) {
      var value = valid(templates);
      succeed(value);
      assertEquals(2, h.trace.stream().filter("newContracts"::equals).count());
      assertTrue(h.trace.indexOf("selectMcpReservation") > h.trace.indexOf("readNode"));
      assertTrue(h.trace.indexOf("insertMcpResult") < h.trace.indexOf("insertMcpCursor"));
      assertTrue(h.trace.indexOf("insertMcpCursor") < h.trace.indexOf("releaseMcpStorage"));
      assertTrue(h.trace.indexOf("releaseMcpStorage") < h.trace.indexOf("settleMcpReservation"));
      assertTrue(h.trace.indexOf("settleMcpReservation") < h.trace.indexOf("commitTool"));
      h.trace.clear();
    }
  }

  @Test
  void pageMetadataProjectionAndCursorFailuresStopBeforeStorage() {
    var value = valid(false);
    List<Consumer<ObjectNode>> mutations =
        List.of(
            n -> n.put("snapshotId", "00000000-0000-0000-0000-000000000003"),
            n -> n.put("snapshotSha256", Payloads.hash("wrong")),
            n -> n.put("server", "other"),
            n -> n.put("serverSnapshotDigest", Payloads.hash("wrong")),
            n -> n.put("schemaDigest", Payloads.hash("wrong")),
            n -> n.put("serverVersion", "2"),
            n -> n.put("page", 3),
            n -> n.put("total", 3),
            n -> n.put("returned", 2),
            n -> n.putNull("nextCursor"));
    for (var change : mutations) {
      var broken = page(value, 0, change);
      h.reject("SNAPSHOT_CORRUPT", () -> succeed(broken));
      assertFalse(h.trace.contains("selectMcpReservation"));
      h.trace.clear();
    }
    var broken = page(value, 1, n -> n.put("nextCursor", McpLifecycleValues.CURSOR));
    h.reject("SNAPSHOT_CORRUPT", () -> succeed(broken));
    assertFalse(h.trace.contains("commitTool"));
  }

  @Test
  void snapshotIdentityAndQueryRejectBeforeStorage() {
    var value = valid(false);
    var node = Payloads.read(Payloads.json(value), ObjectNode.class);
    for (String key :
        List.of("tenant", "owner", "taskId", "bindingDigest", "queryDigest", "projectionDigest")) {
      var altered = node.deepCopy();
      altered.put(
          key,
          key.endsWith("Digest")
              ? Payloads.hash("wrong")
              : key.equals("taskId") ? "00000000-0000-0000-0000-000000000003" : "other");
      var broken = Payloads.read(altered.toString(), McpLedger.ListSnapshot.class);
      h.reject("SNAPSHOT_CORRUPT", () -> succeed(broken));
      assertFalse(h.trace.contains("selectMcpReservation"));
      h.trace.clear();
    }
  }

  @Test
  void onlyOriginalIOExceptionAndIllegalArgumentAreTranslated() {
    var value = valid(false);
    for (Throwable failure :
        List.of(
            new IOException("sentinel"),
            new IllegalArgumentException("sentinel"),
            new NullPointerException("sentinel"),
            new TaskFailure(403, "SENTINEL"))) {
      var port =
          (McpRecords)
              Proxy.newProxyInstance(
                  McpRecords.class.getClassLoader(),
                  new Class<?>[] {McpRecords.class},
                  (p, m, a) -> {
                    if (m.getName().equals("newContracts")) throw failure;
                    try {
                      return m.invoke(h.port, a);
                    } catch (InvocationTargetException error) {
                      throw error.getCause();
                    }
                  });
      var service = new McpPersistenceServiceImpl(port);
      Runnable work =
          () ->
              service.mcpSucceeded(
                  McpPersistenceServiceTest.LEASE,
                  McpPersistenceServiceTest.INV,
                  value.pages().getFirst(),
                  value);
      if (failure instanceof IOException || failure instanceof IllegalArgumentException)
        assertEquals("SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, work::run).code());
      else assertSame(failure, assertThrows(RuntimeException.class, work::run));
    }
  }
}
