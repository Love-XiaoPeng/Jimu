package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspaceToolContractTest {
  private final ObjectMapper json = new ObjectMapper();

  private JsonNode tool(JsonNode node, String name) {
    if (node.isObject() && node.path("name").asText().equals(name) && node.has("parameters"))
      return node.get("parameters");
    for (var child : node) {
      var found = tool(child, name);
      if (found != null) return found;
    }
    return null;
  }

  @Test
  void modelParametersMatchTheImmutableBaseline() throws Exception {
    var baseline =
        json.readTree(Path.of("docs/baseline/XIAO-33-harness-tool-inventory-v1.1.json").toFile());
    for (var name : java.util.List.of("read", "write", "present")) {
      var contract = new WorkspaceContracts().load(name);
      var input = json.readTree(contract.input()).deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) input).remove("$schema");
      assertEquals(tool(baseline, name), input);
    }
  }

  @Test
  void modelCannotSupplyHostOperationOrCasAndLineBoundsMustBeIntegral() throws Exception {
    var runtime = new WorkspaceRuntime(null); // Validation-only boundary; execute is not called.
    for (var args :
        new String[] {
          "{\"file_path\":\"report.md\",\"operationId\":\"forged\"}",
          "{\"file_path\":\"report.md\",\"offset\":1.5}",
          "{\"file_path\":\"report.md\",\"limit\":2001}"
        })
      assertThrows(
          IllegalArgumentException.class, () -> runtime.validate("read", json.readTree(args)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.validate(
                "write",
                json.readTree(
                    "{\"file_path\":\"report.md\",\"content\":\"x\",\"expectedVersion\":0}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.validate(
                "present",
                json.readTree("{\"files\":[{\"path\":\"report.md\"},{\"path\":\"report.md\"}]}")));
  }
}
