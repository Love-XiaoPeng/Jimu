package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTraceContractsTest {
  private final SessionTraceContracts trace = new SessionTraceContracts();

  @Test
  void strictUntrustedBoundary() {
    for (String raw :
        List.of(
            "{}",
            "null",
            "[]",
            "{\"seq\":null}",
            "{\"seq\":true}",
            "{\"seq\":\"0\"}",
            "{\"seq\":-0}",
            "{\"seq\":1.0}",
            "{\"seq\":1e0}",
            "{\"seq\":-1}",
            "{\"seq\":9007199254740992}",
            "{\"seq\":0,\"seq\":1}",
            "{\"seq\":0,\"depth\":1}",
            "{\"seq\":0}{}",
            "{\"seq\":0,\"session_id\":\"\\uD800\"}")) {
      assertEquals(
          "TOOL_INPUT_INVALID",
          assertThrows(TaskFailure.class, () -> trace.input(raw)).code(),
          raw);
    }
    assertThrows(TaskFailure.class, () -> trace.input(new byte[] {(byte) 0xc3, 0x28}));
    assertEquals(9007199254740991L, trace.input("{\"seq\":9007199254740991}").seq());
    String raw = "{\"seq\":0}";
    trace.input(raw + " ".repeat(1024 - raw.length()));
    assertThrows(TaskFailure.class, () -> trace.input(raw + " ".repeat(1025 - raw.length())));
    assertThrows(
        TaskFailure.class,
        () -> trace.input("{\"seq\":0,\"session_id\":\"00000000-0000-4000-8000-00000000000A\"}"));
  }

  @Test
  void bytePinnedV3AndExamples() throws Exception {
    var root = Path.of("docs/verification/XIAO-54/architecture/contracts");
    try (var files = Files.walk(root)) {
      for (var path : files.filter(Files::isRegularFile).toList())
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            Files.readAllBytes(path),
            Files.readAllBytes(
                Path.of("src/main/resources/session/v3").resolve(root.relativize(path))));
    }
    var history = new SessionContracts(3);
    history.event(
        Files.readString(root.resolve("examples/user-event.json"), StandardCharsets.UTF_8));
    history.event(Files.readString(root.resolve("examples/read-receipt.json")));
    history.output(Files.readString(root.resolve("examples/read-output.json")));
    new SessionSearchContracts(3)
        .output(Files.readString(root.resolve("examples/search-output.json")));
    trace.output(Files.readString(root.resolve("examples/trace-output.json")));
    assertEquals("0.3.0", history.descriptor().version());
    assertEquals("0.2.0", new SessionSearchContracts(3).descriptor().version());
  }
}
