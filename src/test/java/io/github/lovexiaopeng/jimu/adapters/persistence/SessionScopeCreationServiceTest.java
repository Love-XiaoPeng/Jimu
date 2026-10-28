package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeCreationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionScopeCreationServiceTest {
  final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 1, 2, 3, 456789000);
  final Map<String, Object> caller =
      new LinkedHashMap<>(
          Map.of(
              "tenant_id",
              "tenant",
              "id",
              "caller",
              "owner_actor_id",
              "小鹏",
              "tools_digest",
              "pins",
              "created_at",
              now));
  final Map<String, Map<String, Object>> sources = new HashMap<>(), histories = new HashMap<>();
  final List<String> calls = new ArrayList<>();
  final Map<String, Object[]> writes = new LinkedHashMap<>();
  final List<Map<String, Object>> serialized = new ArrayList<>();
  String fail;
  RuntimeException failure;
  long charged;
  int bindings;

  void source(String id, int days) {
    sources.put(
        id,
        new HashMap<>(
            Map.of("id", id, "tools_digest", "pins-" + id, "created_at", now.minusDays(1))));
    histories.put(
        id,
        new HashMap<>(
            Map.of(
                "retain_until",
                now.plusDays(days),
                "binding_digest",
                "binding-" + id,
                "next_seq",
                4L)));
  }

  SessionScopeCreationRecords port() {
    return (SessionScopeCreationRecords)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionScopeCreationRecords.class},
            (p, m, a) -> {
              String name = m.getName();
              if (name.equals("version")) return 4;
              calls.add(name);
              if (name.equals(fail)) throw failure;
              return switch (name) {
                case "source" -> {
                  yield MapperSessionScopeCreationRecords.wrap(sources.get(a[1]));
                }
                case "history" ->
                    MapperSessionScopeCreationRecords.wrap(histories.get(((Row) a[0]).id()));
                case "binding" -> "binding" + (++bindings);
                case "hash" -> Payloads.hash((String) a[0]);
                case "json" -> {
                  var map = (Map<String, Object>) a[0];
                  serialized.add(map);
                  yield Payloads.json(map);
                }
                case "validateAuthority" -> null;
                case "insertAuthority", "insertAuthorityTarget", "retainAuthorityHistory" -> {
                  writes.put(name + (name.equals("insertAuthorityTarget") ? a[2] : ""), a);
                  yield null;
                }
                case "body" -> {
                  assertEquals("caller", ((Row) a[0]).id());
                  charged = (Long) a[1];
                  yield null;
                }
                default -> throw new AssertionError(name);
              };
            });
  }

  void run(List<String> ids) {
    new SessionScopeCreationServiceImpl(port())
        .create(MapperSessionScopeCreationRecords.wrap(caller), ids);
  }

  @Test
  void emptyScopeKeepsTwoBindingArrivalsAndUtf8Charge() {
    run(List.of());
    var a = serialized.getFirst();
    assertEquals(
        List.of(
            "version",
            "caller_session_id",
            "tenant_id",
            "owner_actor_id",
            "caller_binding_sha256",
            "caller_tools_sha256",
            "authority_policy",
            "created_at",
            "retain_until",
            "targets"),
        new ArrayList<>(a.keySet()));
    assertEquals(2, bindings);
    assertEquals(Payloads.hash("binding1"), a.get("caller_binding_sha256"));
    assertEquals(Payloads.hash("binding2"), writes.get("insertAuthority")[3]);
    assertEquals(now.plusDays(7), writes.get("retainAuthorityHistory")[0]);
    assertEquals(
        Payloads.json(a).getBytes(java.nio.charset.StandardCharsets.UTF_8).length, charged);
    assertEquals(
        List.of("insertAuthority", "retainAuthorityHistory"), new ArrayList<>(writes.keySet()));
  }

  @Test
  void orderedTargetsEarliestRetentionAndEveryStoredCopyCharged() {
    source("z", 6);
    source("a", 2);
    run(List.of("z", "a"));
    assertEquals(List.of("source", "history", "source", "history"), calls.subList(0, 4));
    var targets = (List<Map<String, Object>>) serialized.getFirst().get("targets");
    assertEquals(List.of("z", "a"), targets.stream().map(t -> t.get("session_id")).toList());
    assertEquals(
        List.of(
            "session_id",
            "history_version",
            "binding_sha256",
            "pins_sha256",
            "upper_seq",
            "created_at",
            "retain_until"),
        new ArrayList<>(targets.getFirst().keySet()));
    assertEquals(3L, targets.getFirst().get("upper_seq"));
    assertEquals("2026-10-26T01:02:03.456789Z", targets.getFirst().get("created_at"));
    assertEquals(now.plusDays(2), writes.get("retainAuthorityHistory")[0]);
    long expected =
        Payloads.json(serialized.getFirst())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length;
    for (var t : targets)
      expected +=
          Payloads.json(t).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              + t.get("session_id")
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                  .length;
    assertEquals(expected, charged);
    assertSame(targets.get(0), serialized.get(1));
    assertSame(targets.get(1), serialized.get(2));
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 410, 409, 503})
  void onlyOriginalLoopMapsSelectedFailures(int status) {
    source("one", 2);
    fail = "history";
    failure = new TaskFailure(status, "ORIGINAL");
    var result = assertThrows(TaskFailure.class, () -> run(List.of("one", "later")));
    if (status == 403 || status == 404 || status == 410) {
      assertNotSame(failure, result);
      assertEquals(404, result.status());
      assertEquals("SESSION_SCOPE_UNAVAILABLE", result.code());
    } else assertSame(failure, result);
    assertEquals(List.of("source", "history"), calls);
    assertTrue(writes.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "validateAuthority",
        "insertAuthority",
        "insertAuthorityTarget",
        "retainAuthorityHistory",
        "body"
      })
  void laterFailureIsSameInstanceAndStopsAtItsBoundary(String operation) {
    source("one", 2);
    fail = operation;
    failure = new TaskFailure(403, "ORIGINAL");
    assertSame(failure, assertThrows(TaskFailure.class, () -> run(List.of("one"))));
    assertEquals(operation, calls.getLast());
    if (operation.equals("insertAuthorityTarget"))
      assertFalse(writes.containsKey("retainAuthorityHistory"));
  }

  @Test
  void lazyViewRetainsMapReferenceAndOriginalNullCastTiming() {
    var row = MapperSessionScopeCreationRecords.wrap(caller);
    caller.put("owner_actor_id", 17);
    assertEquals("tenant", row.tenant());
    assertThrows(ClassCastException.class, row::owner);
    caller.put("owner_actor_id", "changed");
    assertEquals("changed", row.owner());
    caller.put("created_at", null);
    assertThrows(NullPointerException.class, () -> run(null));
    assertTrue(calls.isEmpty());
  }

  @Test
  void sourceNullNumberRetainsFirstErrorBeforeNextTarget() {
    source("one", 2);
    histories.get("one").put("next_seq", null);
    assertThrows(NullPointerException.class, () -> run(List.of("one", "later")));
    assertEquals(List.of("source", "history"), calls);
    assertTrue(writes.isEmpty());
  }

  @Test
  void constructorDoesNotTouchRowsOrPort() {
    new SessionScopeCreationServiceImpl(null);
    MapperSessionScopeCreationRecords.wrap(null);
    assertTrue(calls.isEmpty());
  }

  @Test
  void repeatedCallerReadsAreNotCached() {
    var changing =
        new HashMap<String, Object>(caller) {
          int owners;

          @Override
          public Object get(Object key) {
            if (key.equals("owner_actor_id")) return "owner" + (++owners);
            return super.get(key);
          }
        };
    new SessionScopeCreationServiceImpl(port())
        .create(MapperSessionScopeCreationRecords.wrap(changing), List.of());
    assertEquals("owner1", serialized.getFirst().get("owner_actor_id"));
    assertEquals("owner2", writes.get("insertAuthority")[2]);
  }
}
