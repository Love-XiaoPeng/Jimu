package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class SessionContractsTest {
  private final SessionContracts contracts = new SessionContracts();

  @TestFactory
  Stream<DynamicTest> strictUntrustedInputs() {
    return Stream.of(
            "{}",
            "null",
            "[]",
            "{\"seq\":0,\"seq\":1}",
            "{\"seq\":1.0}",
            "{\"seq\":1e0}",
            "{\"seq\":-1}",
            "{\"seq\":9007199254740992}",
            "{\"seq\":true}",
            "{\"seq\":null}",
            "{\"seq\":\"1\"}",
            "{\"seq\":0,\"before\":5}",
            "{\"seq\":0,\"after\":-1}",
            "{\"seq\":0,\"after\":4.0}",
            "{\"seq\":0,\"extra\":1}",
            "{\"seq\":0} {}",
            "{\"seq\":0,\"session_id\":\"00000000-0000-4000-8000-00000000005A\"}",
            "{\"seq\":0,\"session_id\":\"\\ud800\"}",
            " ".repeat(1024) + "{\"seq\":0}")
        .map(
            raw ->
                DynamicTest.dynamicTest(
                    raw.length() > 100 ? "input byte limit" : raw,
                    () ->
                        assertEquals(
                            "TOOL_INPUT_INVALID",
                            assertThrows(TaskFailure.class, () -> contracts.input(raw)).code())));
  }

  @Test
  void exactSafeIntegerAndWindowLimits() {
    var value = contracts.input("{\"seq\":9007199254740991,\"before\":4,\"after\":4}");
    assertEquals(9007199254740991L, value.seq());
    assertEquals(4, value.before());
    assertEquals(4, value.after());
    assertEquals(0, contracts.input("{\"seq\":0}").after());
    assertThrows(TaskFailure.class, () -> contracts.input(new byte[] {(byte) 0xc0, (byte) 0xaf}));
  }

  @Test
  void publicProjectionRedactsSimultaneouslyAndPreservesWhitespace() {
    var value =
        contracts.publicText(
            "Visible 🧱\nBearer\u00a0ABC\tend /private/path next final", List.of("ABC"));
    assertEquals(
        "Visible 🧱\n[redacted]\u00a0[redacted]\tend [redacted] [redacted] final", value.content());
    assertTrue(value.redacted());
    assertEquals(
        "safe [redacted] suffix",
        contracts.publicText("safe abcdef suffix", List.of("abc", "abcdef")).content());
    assertFalse(contracts.publicText("普通 Unicode 🧱\n空白", List.of()).redacted());
    assertEquals(
        "[redacted] [redacted] [redacted] tail",
        contracts.publicText("token secret value tail", List.of()).content());
  }

  @Test
  void summariesKeepCodePointsAndExplicitlyBoundUtf8() {
    String value = "🧱".repeat(257);
    assertEquals("🧱".repeat(256), SessionContracts.prefix(value));
    assertEquals(1024, SessionContracts.prefix(value).getBytes(StandardCharsets.UTF_8).length);
    assertThrows(TaskFailure.class, () -> contracts.callId("slot/path", List.of()));
    assertThrows(TaskFailure.class, () -> contracts.callId("slot-sentinel", List.of("sentinel")));
  }

  @Test
  void completeInputAndEventByteLimitsAcceptNAndRejectNPlusOne() throws Exception {
    String input = "{\"seq\":0}";
    assertEquals(0, contracts.input(input + " ".repeat(1024 - input.length())).seq());
    assertThrows(
        TaskFailure.class, () -> contracts.input(input + " ".repeat(1025 - input.length())));
    String event;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("session/examples/user-event.json")) {
      event = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String complete = event + " ".repeat(16384 - SessionContracts.bytes(event));
    contracts.event(complete.substring(0, complete.length() - 1));
    contracts.event(complete);
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> contracts.event(complete + " ")).code());
    String output;
    try (var stream =
        getClass().getClassLoader().getResourceAsStream("session/examples/read-output.json")) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String full = output + " ".repeat(32768 - SessionContracts.bytes(output));
    contracts.output(full.substring(0, full.length() - 1));
    contracts.output(full);
    assertEquals(
        "SESSION_HISTORY_LIMIT",
        assertThrows(TaskFailure.class, () -> contracts.output(full + " ")).code());
  }
}
