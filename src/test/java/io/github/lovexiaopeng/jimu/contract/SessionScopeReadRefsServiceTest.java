package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadRefs;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeReadRefsServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 冻结Schema不被手写替代；校验顺序和原异常边界保持。 */
class SessionScopeReadRefsServiceTest {
  private final SessionScopeReadRefsServiceImpl service = new SessionScopeReadRefsServiceImpl();
  private static final PriorSessionContracts CODEC = new PriorSessionContracts();
  private final List<String> calls = new ArrayList<>();
  private String operation = "READ", outcome = "SUCCEEDED", raw = "[]";
  private Long bytes = 2L;
  private String digest = Payloads.hash(raw), retained, failureAt;
  private final TaskFailure timeout = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");

  private final SessionScopeReadRefs refs =
      new SessionScopeReadRefs() {
        private void call(String name) {
          calls.add(name);
          if (name.equals(failureAt)) throw timeout;
        }

        public Binding binding() {
          call("binding");
          return new Binding(operation, outcome, raw, bytes, digest);
        }

        public void validateRefs(String value) {
          call("contract");
          CODEC.refs(value);
        }

        public int bytes(String value) {
          call("bytes");
          return SessionContracts.bytes(value);
        }

        public String hash(String value) {
          call("hash");
          return Payloads.hash(value);
        }

        public void retainNodes(String value) {
          call("nodes");
          retained = value;
        }
      };

  private void reject() {
    var error = assertThrows(TaskFailure.class, () -> service.verify(refs));
    assertEquals(409, error.status());
    assertEquals("SESSION_HISTORY_CORRUPT", error.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCEEDED", "FAILED"})
  void terminalReadRetainsExactRawOnlyAfterAllChecks(String terminal) {
    outcome = terminal;
    raw =
        "[ {\"seq\":2,\"event_sha256\":\""
            + "a".repeat(64)
            + "\",\"session_id\":\"00000000-0000-0000-0000-000000000001\"} ]";
    bytes = (long) SessionContracts.bytes(raw);
    digest = Payloads.hash(raw);
    service.verify(refs);
    assertSame(raw, retained);
    assertEquals(List.of("binding", "contract", "bytes", "hash", "nodes"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SEARCH", "", "read"})
  void operationRejectedBeforeBody(String invalid) {
    operation = invalid;
    raw = "{";
    reject();
    assertEquals(List.of("binding"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "RUNNING", "OUTCOME_UNKNOWN", "CANCELLED", ""})
  void nonterminalRejectedBeforeBody(String invalid) {
    outcome = invalid;
    reject();
    assertEquals(List.of("binding"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{}", "[{}]", "[null]", "[1]", "[true]"})
  void actualSchemaRejectsBeforeByteAndDigestProof(String invalid) {
    raw = invalid;
    bytes = null;
    digest = null;
    reject();
    assertEquals(List.of("binding", "contract"), calls);
  }

  @Test
  void sizeLimitPrecedesDigestAndStoredNull() {
    raw = " ".repeat(2049);
    bytes = null;
    var error = assertThrows(TaskFailure.class, () -> service.verify(refs));
    assertEquals(429, error.status());
    assertEquals("SESSION_HISTORY_LIMIT", error.code());
    assertEquals(List.of("binding", "contract"), calls);
  }

  @Test
  void byteMismatchPrecedesHash() {
    bytes = 3L;
    reject();
    assertEquals(List.of("binding", "contract", "bytes"), calls);
  }

  @Test
  void digestMismatchNeverExposesNodes() {
    digest = null;
    reject();
    assertEquals(List.of("binding", "contract", "bytes", "hash"), calls);
  }

  @Test
  void originalNullStoredByteUnboxingIsPreserved() {
    bytes = null;
    assertThrows(NullPointerException.class, () -> service.verify(refs));
    assertEquals(List.of("binding", "contract", "bytes"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"binding", "contract", "bytes", "hash", "nodes"})
  void failuresPropagateWithoutRetry(String stage) {
    failureAt = stage;
    assertSame(timeout, assertThrows(TaskFailure.class, () -> service.verify(refs)));
    assertEquals(1, calls.stream().filter(stage::equals).count());
    assertEquals(stage, calls.getLast());
  }
}
