package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionScopesTest {
  private ExecutionScopes.Scope scope(Map<String, ExecutionScopes.Pin> pins) {
    String digest = Payloads.hash("fixture-authority");
    return new ExecutionScopes.Scope(
        1,
        "scope",
        "issue",
        "instance",
        "schema",
        "tenant",
        "run",
        digest,
        digest,
        digest,
        digest,
        digest,
        "qwen3.7-plus-2026-05-26",
        "qwen3.7-plus-2026-05-26",
        pins,
        Map.of("W1", "call", "W2", "sse", "W3", "call", "W4", "sse"),
        true);
  }

  @Test
  void canonicalMapOrderAndStrictUnknownFields() {
    var call = new ExecutionScopes.Pin(1, false, Payloads.hash("call"));
    var sse = new ExecutionScopes.Pin(1, true, Payloads.hash("sse"));
    var pins = new LinkedHashMap<String, ExecutionScopes.Pin>();
    pins.put("sse", sse);
    pins.put("call", call);
    String json = ExecutionScopes.canonical(scope(pins));
    assertEquals(json, ExecutionScopes.canonical(scope(Map.of("call", call, "sse", sse))));
    assertEquals(json, ExecutionScopes.canonical(Payloads.read(json, ExecutionScopes.Scope.class)));
    assertThrows(UnsupportedOperationException.class, () -> scope(pins).profilePins().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Payloads.read(
                json.substring(0, json.length() - 1) + ",\"extraAuthority\":true}",
                ExecutionScopes.Scope.class));
  }

  @Test
  void missingPlaceholderAndAmbiguousStreamPinsFailClosed() {
    assertFalse(ExecutionScopes.digest(null));
    assertFalse(ExecutionScopes.digest("0".repeat(64)));
    assertFalse(ExecutionScopes.valid(scope(null)));
    assertFalse(
        ExecutionScopes.valid(
            scope(
                Map.of(
                    "call",
                    new ExecutionScopes.Pin(1, false, "0".repeat(64)),
                    "sse",
                    new ExecutionScopes.Pin(1, true, Payloads.hash("sse"))))));
    var pin = new ExecutionScopes.Pin(1, false, Payloads.hash("snapshot"));
    assertFalse(ExecutionScopes.valid(scope(Map.of("call", pin, "sse", pin))));
    assertTrue(ExecutionScopes.digest(Payloads.hash("non-placeholder")));
  }
}
