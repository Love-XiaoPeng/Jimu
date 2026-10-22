package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpPolicy;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpWire;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class McpContractTest {
  @Test
  void packagedSchemasAreBytePinnedAndInputCannotCarryAuthority() throws Exception {
    var contracts = new McpContracts();
    for (String name : McpLedger.NAMES) {
      String valid =
          name.equals("read_mcp_resource")
              ? "{\"server\":\"controlled\",\"uri\":\"jimu-fixture://tenant-a/docs/test\"}"
              : "{\"server\":\"controlled\"}";
      contracts.contract(name).in().validate(Payloads.read(valid, JsonNode.class));
      for (String field :
          new String[] {"endpoint", "tenant", "task", "credential", "command", "hostPath"}) {
        var node = Payloads.read(valid, com.fasterxml.jackson.databind.node.ObjectNode.class);
        node.put(field, "untrusted");
        assertThrows(
            IllegalArgumentException.class, () -> contracts.contract(name).in().validate(node));
      }
    }
  }

  @Test
  void resourceIdentifiersCannotBecomeHostPathsOrNetworkTargets() {
    assertTrue(McpPolicy.safe("jimu-fixture://tenant-a/docs/resource_1"));
    for (String uri :
        new String[] {
          "file:///etc/passwd",
          "https://example.org/x",
          "data://x/y",
          "jimu-fixture://x/a/../b",
          "jimu-fixture://x/%2e%2e",
          "jimu-fixture://x/a?x",
          "jimu-fixture://user@x/a",
          "jimu-fixture://x:80/a",
          "jimu-fixture://x/a//b",
          "jimu-fixture://x/a\\b",
          "jimu-fixture://x/中文"
        }) assertFalse(McpPolicy.safe(uri), uri);
  }

  @Test
  void strictJsonRejectsAmbiguousAndInvalidUnicodeData() {
    for (byte[] raw :
        new byte[][] {
          "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8),
          "{} {}".getBytes(StandardCharsets.UTF_8),
          new byte[] {(byte) 0xff},
          ("[".repeat(33) + "0" + "]".repeat(33)).getBytes(StandardCharsets.UTF_8)
        }) assertThrows(TaskFailure.class, () -> McpWire.parse(raw));
    assertThrows(TaskFailure.class, () -> McpWire.textBytes("\ud800"));
  }

  @Test
  void optionalItemFieldsAreOmittedWithoutChangingOldTaskCanonicalBytes() {
    String json =
        Payloads.json(
            new McpResourcePort.Resource("jimu-fixture://x/a", "name", null, null, null, null));
    assertEquals("{\"name\":\"name\",\"uri\":\"jimu-fixture://x/a\"}", json);
    assertEquals(
        "{\"pins\":{},\"schemaVersion\":1}",
        Payloads.json(
            new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot(
                1, java.util.Map.of())));
  }
}
