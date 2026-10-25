package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionHistoryStorageAdapterTest {
  private final List<Object> reads = new ArrayList<>();
  private final HashMap<String, Object> task =
      new HashMap<>() {
        @Override
        public Object get(Object key) {
          reads.add(key);
          return super.get(key);
        }
      };

  @Test
  void nonSessionReturnsBeforeKeysAndMapperAssembly() {
    task.put("capabilities", "web_fetch");
    new JdbcSessionRecords(new JdbcTemplate()).body(task, Long.MIN_VALUE);
    assertEquals(List.of("capabilities", "capabilities", "capabilities", "capabilities"), reads);
  }

  @Test
  void createResolvesTenantBeforeIdBindingGoalAndPersistence() {
    task.put("tenant_id", 1L);
    assertThrows(
        ClassCastException.class, () -> new JdbcSessionRecords(new JdbcTemplate()).create(task));
    assertEquals(List.of("tenant_id"), reads);
  }

  @Test
  void byteSumUsesOriginalGoalThenSnapshotAndPreservesNullFailure() {
    task.put("goal", "🧱");
    var adapter = new MapperSessionHistoryStorage(new JdbcSessionRecords(new JdbcTemplate()), task);
    assertThrows(NullPointerException.class, adapter::initialBytes);
    assertEquals(List.of("goal", "request_snapshot"), reads);
    task.put("request_snapshot", "中");
    assertEquals(7, adapter.initialBytes());
  }
}
