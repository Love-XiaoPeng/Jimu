package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** graph-v2接入前置证据：只保存新形状候选，不绕开V23或旧binding激活生产写者。 */
class SessionLineageGraphCodecPreconditionTest {
  private static final String ID = "00000000-0000-0000-0000-000000000001";
  private static final String HASH = "a".repeat(64);
  private final SessionContracts json = new SessionContracts(5);

  private static byte[] resource(String path) throws IOException {
    try (var stream =
        SessionLineageGraphCodecPreconditionTest.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) throw new IOException("Missing contract: " + path);
      return stream.readAllBytes();
    }
  }

  private ObjectNode compact() {
    return (ObjectNode)
        json.json(
            Payloads.json(
                Map.of(
                    "version",
                    2,
                    "caller_session_id",
                    ID,
                    "authority_sha256",
                    HASH,
                    "nodes",
                    List.of(Map.of("session_id", ID, "genesis_sha256", HASH)))),
            16384);
  }

  @Test
  void newShapeRequiresItsOwnVersionAndRejectsEmbeddedOrMixedNodes() throws IOException {
    var candidate = new SchemaContract(resource("session/graph-reference-v2.schema.json"));
    var value = compact();
    candidate.validate(value);
    value.put("version", 1);
    assertThrows(IllegalArgumentException.class, () -> candidate.validate(value));
    value.put("version", 2);
    ((ObjectNode) value.path("nodes").get(0)).put("genesis_snapshot", "{}");
    assertThrows(IllegalArgumentException.class, () -> candidate.validate(value));
    value.set("nodes", json.json("[]", 16384));
    assertThrows(IllegalArgumentException.class, () -> candidate.validate(value));
  }

  @Test
  void existingReaderRejectsCompactGraphIncludingRelabelledVersionOne() {
    var current = new SessionLineageContracts();
    var value = compact();
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> current.graph(value.toString())).code());
    value.put("version", 1);
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> current.graph(value.toString())).code());
  }

  @Test
  void changingGraphCatalogPinRequiresAnotherBindingIdentity() throws IOException {
    byte[] catalogBytes = resource("session/v5/catalog-v5.json");
    var binding = (ObjectNode) json.json(json.binding(), 16384);
    var catalog = json.json(new String(catalogBytes, StandardCharsets.UTF_8), 16384);
    var bindingSchema = new SchemaContract(resource("session/v5/task-binding-v5.schema.json"));
    bindingSchema.validate(binding);
    assertEquals(Payloads.hash(catalogBytes), binding.path("catalogDigest").textValue());
    assertEquals(
        Payloads.hash(resource("session/v5/graph.schema.json")),
        catalog.path("schemas").path("graph.schema.json").textValue());
    assertEquals(1, catalogGraphVersion());

    // 即使只新增独立v2项而不覆盖graph-v1，catalog原字节摘要仍改变。
    ((ObjectNode) catalog.path("schemas"))
        .put(
            "graph-reference-v2.schema.json",
            Payloads.hash(resource("session/graph-reference-v2.schema.json")));
    binding.put("catalogDigest", Payloads.hash(catalog.toString()));
    assertThrows(IllegalArgumentException.class, () -> bindingSchema.validate(binding));
  }

  private int catalogGraphVersion() throws IOException {
    return json.json(
            new String(resource("session/v5/graph.schema.json"), StandardCharsets.UTF_8), 16384)
        .path("properties")
        .path("version")
        .path("const")
        .intValue();
  }
}
