package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TodoContractTest {
  static Stream<JsonNode> cases() throws Exception {
    var cases =
        new ObjectMapper()
            .readTree(
                Files.readString(
                    Path.of(
                        "docs/verification/XIAO-50/architecture/evidence/semantic-cases.json")));
    return java.util.stream.StreamSupport.stream(cases.spliterator(), false);
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("cases")
  void pinnedUpstreamAndCloudBoundary(JsonNode test) throws Exception {
    var contract = new TodoContracts();
    String raw =
        test.path("raw").isNull() ? test.path("input").toString() : test.path("raw").asText();
    if (test.path("valid").asBoolean()) {
      var result = contract.input(raw);
      assertEquals(
          test.path("expected"),
          new ObjectMapper().readTree(Payloads.json(result)),
          test.path("name").asText());
      assertEquals(result, contract.output(Payloads.json(result)));
    } else {
      assertEquals(
          "TOOL_INPUT_INVALID", assertThrows(TaskFailure.class, () -> contract.input(raw)).code());
    }
  }
}
