package io.github.lovexiaopeng.jimu.adapters.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts.Contract;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Byte-pinned packaged catalog, never server supplied schemas/classes. */
public final class McpContracts {
  public static final String DIGEST =
      "4ff8e6e0c9ef697c02517426a948479f5976806c9f4249c954a69fa579b826db";
  private final Map<String, Contract> contracts = new LinkedHashMap<>();
  private final SchemaContract profile;
  private final SchemaContract binding;
  private final SchemaContract list;

  public McpContracts() throws IOException {
    byte[] index = resource("schema-index.json");
    if (!DIGEST.equals(Payloads.hash(index))) throw new IOException("MCP index digest mismatch");
    for (var file :
        Payloads.read(new String(index, StandardCharsets.UTF_8), JsonNode.class).path("files"))
      if (!file.path("sha256").asText().equals(Payloads.hash(resource(file.path("path").asText()))))
        throw new IOException("MCP contract digest mismatch");
    for (String name : McpLedger.NAMES) {
      var manifest =
          Payloads.read(
              new String(resource(name + "/plugin.yaml"), StandardCharsets.UTF_8), JsonNode.class);
      byte[] input = resource(name + "/input.schema.json");
      byte[] output = resource(name + "/output.schema.json");
      var descriptor =
          new ToolDescriptor(
              manifest.path("id").asText(),
              "0.1.0",
              name,
              "mcp/" + name + "/input.schema.json",
              "mcp/" + name + "/output.schema.json",
              Payloads.hash(output),
              manifest.path("entry").path("class").asText());
      contracts.put(
          name,
          new Contract(
              descriptor,
              new String(input, StandardCharsets.UTF_8),
              new SchemaContract(input),
              new SchemaContract(output)));
    }
    profile = new SchemaContract(resource("server-profile-v1.schema.json"));
    binding = new SchemaContract(resource("task-binding-v1.schema.json"));
    list = new SchemaContract(resource("list-snapshot-v1.schema.json"));
  }

  public Contract contract(String name) {
    return contracts.get(name);
  }

  public void profile(McpLedger.Profile value) {
    profile.validateObject(value);
    McpPolicy.profile(value);
  }

  public void binding(McpLedger.Binding value) {
    binding.validateObject(value);
  }

  public void list(McpLedger.ListSnapshot value) {
    list.validateObject(value);
  }

  public static byte[] resource(String path) throws IOException {
    try (var stream = McpContracts.class.getClassLoader().getResourceAsStream("mcp/" + path)) {
      if (stream == null) throw new IOException("Missing MCP contract");
      return stream.readAllBytes();
    }
  }
}
