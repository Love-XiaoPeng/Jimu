package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.McpPersistenceService;
import io.github.lovexiaopeng.jimu.kernel.media.port.ImageRecords;
import io.github.lovexiaopeng.jimu.kernel.media.service.ImagePersistenceService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Rewired callers must leave Map access, repetitions and failure ownership with the Service. */
class FacadeCallersTest {
  private final List<String> accesses = new ArrayList<>();
  private final RuntimeException poison = new IllegalStateException("synthetic row failure");
  private final Map<String, Object> row =
      new AbstractMap<>() {
        public Object get(Object key) {
          accesses.add((String) key);
          if (key.equals("tenant_id")) return "tenant-" + accesses.size();
          throw poison;
        }

        public Set<Entry<String, Object>> entrySet() {
          throw new AssertionError("do not enumerate or copy the task");
        }
      };

  private JdbcLedger ledger() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("jimu.test-mode", true)));
    return new JdbcLedger(
        new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"),
        new ModelProfiles(environment));
  }

  private static <T> void replace(
      JdbcLedger ledger, String field, Class<T> type, InvocationHandler handler) throws Exception {
    var member = JdbcLedger.class.getDeclaredField(field);
    member.setAccessible(true);
    member.set(
        ledger, Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  @Test
  void materialImageCallsBindAtArrivalWithoutReadingCopyingOrCaching() throws Exception {
    var ledger = ledger();
    var calls = new ArrayList<String>();
    replace(
        ledger,
        "imageService",
        ImagePersistenceService.class,
        (p, method, args) -> {
          assertEquals(List.of(), accesses);
          calls.add(method.getName());
          var task = (ImageRecords.Task) args[0];
          assertEquals("tenant-1", task.tenantId());
          assertEquals("tenant-2", task.tenantId());
          assertSame(poison, assertThrows(RuntimeException.class, task::id));
          accesses.clear();
          return null;
        });
    var caller = new MapperMaterialEvidenceRecords(ledger);
    var task = MapperMaterialEvidenceRecords.wrap(row);
    caller.imageSnapshot(task);
    caller.stepImages(task, "step");
    caller.imageObject(task, null);
    assertEquals(List.of("snapshot", "step", "object"), calls);
  }

  @Test
  void baseAccessMcpBindingLeavesRepeatedReadsAndOriginalExceptionToService() throws Exception {
    var ledger = ledger();
    replace(
        ledger,
        "mcpService",
        McpPersistenceService.class,
        (p, method, args) -> {
          assertEquals("binding", method.getName());
          assertEquals(List.of(), accesses);
          var task = (McpRecords.Task) args[0];
          assertEquals("tenant-1", task.tenantId());
          assertEquals("tenant-2", task.tenantId());
          task.id();
          return null;
        });
    var caller = new MapperTaskBaseAccess(ledger, row, null, null, null, null);
    assertSame(poison, assertThrows(RuntimeException.class, caller::mcpBinding));
    assertEquals(List.of("tenant_id", "tenant_id", "id"), accesses);
  }

  @Test
  void nullRowStillReachesServiceBeforeOriginalFieldFailure() throws Exception {
    var ledger = ledger();
    var calls = new ArrayList<String>();
    replace(
        ledger,
        "imageService",
        ImagePersistenceService.class,
        (p, method, args) -> {
          calls.add(method.getName());
          assertThrows(NullPointerException.class, () -> ((ImageRecords.Task) args[0]).tenantId());
          throw poison;
        });
    assertSame(
        poison,
        assertThrows(
            RuntimeException.class,
            () ->
                new MapperMaterialEvidenceRecords(ledger)
                    .imageSnapshot(MapperMaterialEvidenceRecords.wrap(null))));
    assertEquals(List.of("snapshot"), calls);
    assertEquals(List.of(), accesses);
  }
}
