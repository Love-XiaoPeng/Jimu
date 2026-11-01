package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeNormalizationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SessionScopeNormalizationServiceTest {
  private final SessionScopeNormalizationServiceImpl service =
      new SessionScopeNormalizationServiceImpl();
  private final List<String> calls = new ArrayList<>();
  private String fail;

  private void call(String name) {
    calls.add(name);
    if (name.equals(fail)) throw new IllegalStateException(name);
  }

  private SessionScopeNormalization.Input input(String tool, String target) {
    return (SessionScopeNormalization.Input)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {SessionScopeNormalization.Input.class},
            (p, m, a) -> {
              call(m.getName());
              return switch (m.getName()) {
                case "tool" -> tool;
                case "caller" -> "self";
                case "upper" -> 3L;
                case "read" -> new SessionScopeNormalization.Read(target, 1, 0, 0);
                case "requireScope" ->
                    (SessionScopeNormalization.Authority)
                        id -> {
                          call("targets:" + id);
                          return 7;
                        };
                case "writeRead" -> a[0] + ":" + a[2];
                case "searchQuery" -> "normalized";
                case "searchObject" ->
                    new SessionScopeNormalization.SearchObject() {
                      public void query(String query) {
                        call("put:" + query);
                      }

                      public String sortedJson() {
                        call("sorted");
                        return "search";
                      }
                    };
                default -> throw new AssertionError(m.getName());
              };
            });
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"self", "source"})
  void readAlwaysRequiresAfterUpperButOnlyCrossSessionUsesAuthority(String target) {
    assertEquals(
        "source".equals(target) ? "source:7" : "self:3",
        service.normalize(input("session_event_read", target)));
    var expected = new ArrayList<>(List.of("tool", "read"));
    if (target == null) expected.add("caller");
    expected.addAll(List.of("upper", "requireScope", "caller"));
    if ("source".equals(target)) expected.add("targets:source");
    expected.add("writeRead");
    assertEquals(expected, calls);
  }

  @Test
  void searchCodecPrecedesStrictObjectAndDoesNotReadTaskOrScope() {
    assertEquals("search", service.normalize(input("session_search", null)));
    assertEquals(List.of("tool", "searchQuery", "searchObject", "put:normalized", "sorted"), calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"read", "upper", "requireScope", "targets:source"})
  void firstReadFailurePropagatesAndStopsSubsequentReads(String at) {
    fail = at;
    assertEquals(
        at,
        assertThrows(
                IllegalStateException.class,
                () -> service.normalize(input("session_event_read", "source")))
            .getMessage());
    assertEquals(at, calls.getLast());
  }

  @Test
  void actualAdapterSearchKeepsNodeTypesAndSortsFieldsAfterQueryNormalization() {
    var records = new JdbcLedger(new DriverManagerDataSource(), null).sessions;
    String raw = "{\"query\":\"  Public   words  \",\"event_seq_from\":1}";
    String normalized =
        records.normalizedScope(
            Map.of("capabilities", "session_event_read,session_search"),
            null,
            "session_search",
            raw);
    assertEquals("{\"event_seq_from\":1,\"query\":\"Public words\"}", normalized);
  }

  @Test
  void proofRefusalsStayLazyAndUseOriginalStoredBytes() {
    var records = new JdbcSessionRecords(new JdbcTemplate());
    String raw = "{\"x\":1,\"y\":2}";
    var binding = new HashMap<String, Object>();
    binding.put("normalized_snapshot", raw);
    binding.put("normalized_digest", "wrong");
    binding.put("normalized_bytes", "must-not-cast");
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> records.validateNormalizedScope(raw, binding))
            .code());
    binding.put("normalized_digest", Payloads.hash(raw));
    assertThrows(ClassCastException.class, () -> records.validateNormalizedScope(raw, binding));
    binding.put("normalized_bytes", raw.length());
    records.validateNormalizedScope("{\"y\":2,\"x\":1}", binding);
    binding.put("normalized_digest", 7);
    assertThrows(TaskFailure.class, () -> records.validateNormalizedScope("{}", binding));
    binding.put("normalized_snapshot", "broken");
    assertThrows(TaskFailure.class, () -> records.validateNormalizedScope("{", binding));
    binding.put("normalized_snapshot", " ".repeat(4097));
    assertThrows(TaskFailure.class, () -> records.validateNormalizedScope(raw, binding));
  }
}
