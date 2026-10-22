package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkingDirectoryContractTest {
  static Stream<JsonNode> paths() throws Exception {
    var node =
        new ObjectMapper()
            .readTree(
                Files.readString(
                    Path.of("docs/verification/XIAO-51/architecture/evidence/path-cases.json")));
    return java.util.stream.StreamSupport.stream(node.spliterator(), false);
  }

  @ParameterizedTest
  @MethodSource("paths")
  void acceptedArchitecturePaths(JsonNode test) {
    Runnable check =
        () -> {
          boolean cd = test.path("kind").asText().equals("cd");
          String resolved =
              DirectoryPaths.resolve(test.path("cwd").asText(), test.path("input").asText(), cd);
          if (cd) {
            var files = new ArrayList<String>();
            test.path("files").forEach(file -> files.add(file.asText()));
            DirectoryPaths.requireDirectory(resolved, files);
          }
          assertEquals(test.path("expected").asText(), resolved);
        };
    if (!test.has("error")) check.run();
    else {
      var failure = assertThrows(RuntimeException.class, check::run);
      String code =
          failure instanceof FileFailure file ? file.code() : ((TaskFailure) failure).code();
      assertEquals(test.path("error").asText(), code);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"cd\":null}",
        "{\"cd\":1}",
        "{\"cd\":true}",
        "[]",
        "null",
        "{\"cd\":\"a\",\"cd\":\"b\"}",
        "{} {}",
        "{\"cd\":\"\"}",
        "{\"cd\":\"\\ud800\"}",
        "{\"cd\":\"\\udc00\"}",
        "{\"cwd\":\"/\"}",
        "{\"cd\":\"a\",\"source\":{}}"
      })
  void rejectsInvalidBoundary(String raw) {
    assertEquals(
        "TOOL_INPUT_INVALID",
        assertThrows(TaskFailure.class, () -> new DirectoryContracts().input(raw)).code());
  }

  @Test
  void exactSizesAndUnicodeNames() {
    assertEquals("/" + "x".repeat(511), DirectoryPaths.resolve("/", "x".repeat(511), true));
    assertThrows(TaskFailure.class, () -> DirectoryPaths.resolve("/", "x".repeat(512), true));
    assertThrows(TaskFailure.class, () -> DirectoryPaths.resolve("/", "x".repeat(513), true));
    assertNotEquals(
        DirectoryPaths.resolve("/", "é/x", false), DirectoryPaths.resolve("/", "é/x", false));
    assertNotEquals(
        DirectoryPaths.resolve("/", "A/x", false), DirectoryPaths.resolve("/", "a/x", false));
    assertEquals("a／b/x", DirectoryPaths.resolve("/", "a／b/x", false));
    assertThrows(TaskFailure.class, () -> DirectoryPaths.resolve("/", "\ud800", true));
    assertThrows(TaskFailure.class, () -> new DirectoryContracts().input(" ".repeat(4097) + "{}"));
  }

  @Test
  void outputAndNamespaceAreBound() {
    var contract = new DirectoryContracts();
    assertEquals(null, contract.input("{}").cd());
    assertEquals("子目录/😀", contract.input("{\"cd\":\"子目录/😀\"}").cd());
    var output = new WorkingDirectoryPort.Result("/a");
    assertEquals(output, contract.output(Payloads.json(output)));
    assertThrows(TaskFailure.class, () -> contract.output("{\"cwd\":\"a\"}"));
    String namespace = Payloads.hash("tenant:task");
    contract.binding(contract.binding(namespace), namespace);
    assertThrows(
        TaskFailure.class,
        () -> contract.binding(contract.binding(namespace), Payloads.hash("other:task")));
    assertThrows(FileFailure.class, () -> DirectoryPaths.requireFileNamespace("a", List.of("a/b")));
    assertThrows(FileFailure.class, () -> DirectoryPaths.requireFileNamespace("a/b", List.of("a")));
    DirectoryPaths.requireFileNamespace("ab/x", List.of("a"));
  }
}
