package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionLineageContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionLineageContractsTest {
  private final SessionLineageContracts contracts = new SessionLineageContracts();
  private static final String A = "00000000-0000-0000-0000-000000000001";

  @Test
  void strictTraceDoesNotAcceptEventTraceOrAuthority() {
    assertNull(contracts.input("{}"));
    assertEquals(A, contracts.input("{\"session_id\":\"" + A + "\"}"));
    for (String input :
        new String[] {
          "{\"seq\":0}",
          "{\"session_id\":null}",
          "{\"session_id\":\"" + A + "\",\"session_id\":\"" + A + "\"}",
          "[]",
          "{\"depth\":2}",
          "{\"session_id\":\"UPPERCASE\"}",
          "{} {}",
          "{\"session_id\":\"\\ud800\"}"
        })
      assertEquals(
          "TOOL_INPUT_INVALID",
          assertThrows(TaskFailure.class, () -> contracts.input(input)).code(),
          input);
    assertThrows(TaskFailure.class, () -> contracts.input(new byte[] {(byte) 0xff}));
    assertNull(contracts.input(" ".repeat(1022) + "{}"));
    assertThrows(TaskFailure.class, () -> contracts.input(" ".repeat(1023) + "{}"));
  }

  @Test
  void admissionRequiresExplicitParentAndCanonicalUniqueSources() {
    var input =
        contracts.admission(
            ("{\"goal\":\"继续\",\"history_session_ids\":[\""
                    + A
                    + "\"],\"parent_session_id\":\""
                    + A
                    + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(A, input.parentSessionId());
    for (String raw :
        new String[] {
          "{\"goal\":\"x\",\"history_session_ids\":[],\"parent_session_id\":\"" + A + "\"}",
          "{\"goal\":\" \",\"history_session_ids\":[]}",
          "{\"goal\":\"x\",\"history_session_ids\":[],\"parent_session_id\":null}",
          "{\"goal\":\"x\",\"history_session_ids\":[\"" + A + "\",\"" + A + "\"]}"
        })
      assertThrows(
          TaskFailure.class, () -> contracts.admission(raw.getBytes(StandardCharsets.UTF_8)), raw);
  }

  @Test
  void genesisRoundTripPreservesOriginalProofAndRootHasNoParentField() {
    var root =
        new Genesis(
            1,
            A,
            "ROOT",
            "2026-10-28T10:00:00.000000Z",
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            null,
            null);
    String raw = contracts.genesis(root);
    assertEquals(root, contracts.genesis(raw));
    assertThrows(
        TaskFailure.class, () -> contracts.genesis(raw.replace("\"ROOT\"", "\"CONTINUATION\"")));
  }
}
