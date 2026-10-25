package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoProjectionPortTest {
  @Test
  void originalColumnOrderCaseAccessNullAndObjectIdentityAreRetained() {
    var values = new LinkedHashMap<String, Object>();
    Object revision = new BigDecimal("1.99");
    values.put("retained_digests", null);
    values.put("revision", revision);
    values.put("content_state", "PURGED");
    values.put("tenant_id", "tenant");
    var row = MapperTodoProjections.originalColumns(values);
    assertEquals(
        List.of("tenant_id", "revision", "content_state", "retained_digests"),
        new ArrayList<>(row.keySet()));
    assertSame(revision, row.get("REVISION"));
    assertEquals("PURGED", row.get("CONTENT_STATE"));
    assertTrue(row.containsKey("RETAINED_DIGESTS"));
    assertNull(row.get("retained_digests"));
    assertEquals(values, row);
  }

  @Test
  void constructionReadsNothing() {
    new MapperTodoProjections(null, null);
    new MapperTodoProjections.Row(null);
  }

  @Test
  void rowRetainsAllKeysTypesNullAndIdentity() {
    var m = new LinkedHashMap<String, Object>();
    m.put("revision", 37L);
    m.put("content_state", "PURGED");
    m.put("source_invocation_id", null);
    var row = new MapperTodoProjections.Row(m);
    assertSame(m, row.values());
    assertEquals(m, row.values());
  }

  @Test
  void numberUsesOriginalLongValue() {
    var m = new HashMap<String, Object>();
    m.put("record_version", new BigDecimal("1.99"));
    m.put("todo_version", 1.9d);
    var row = new MapperTodoProjections.Row(m);
    assertEquals(1, row.recordVersion());
    assertEquals(1, row.todoVersion());
  }

  @Test
  void rowValuesRemainLazyAndUnnormalized() {
    var m = new HashMap<String, Object>();
    var row = new MapperTodoProjections.Row(m);
    assertNull(row.snapshot());
    m.put("binding_snapshot", 17);
    assertThrows(ClassCastException.class, row::snapshot);
    assertThrows(NullPointerException.class, row::recordVersion);
    m.put("record_version", "1");
    assertThrows(ClassCastException.class, row::recordVersion);
  }

  @Test
  void jacksonPathAsIntSemanticsRemainOriginal() {
    var port = new MapperTodoProjections(null, null);
    for (String v : List.of("1", "\"1\"", "1.9")) {
      var b = port.decode("{\"version\":" + v + ",\"todoVersion\":1}");
      assertEquals(1, b.version());
      assertEquals(1, b.todoVersion());
    }
    assertEquals(0, port.decode("{}").version());
    assertEquals(0, port.decode("{\"version\":null}").version());
    assertEquals(0, port.decode("[]").todoVersion());
  }

  @Test
  void parseAndNullFailuresAreOriginal() {
    var port = new MapperTodoProjections(null, null);
    assertThrows(RuntimeException.class, () -> port.decode("not json"));
    assertThrows(RuntimeException.class, () -> port.hash(null));
    assertEquals(0, port.decode("null").version());
  }

  @Test
  void queryArgumentsCastTenantBeforeTaskBeforeSession() {
    var reads = new ArrayList<String>();
    final var task =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add((String) k);
            return k.equals("tenant_id") ? 17 : "task";
          }
        };
    assertThrows(
        ClassCastException.class, () -> new MapperTodoProjections(null, task).projection());
    assertEquals(List.of("tenant_id"), reads);
    reads.clear();
    final var second =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add((String) k);
            return k.equals("id") ? 17 : "tenant";
          }
        };
    assertThrows(
        ClassCastException.class, () -> new MapperTodoProjections(null, second).projection());
    assertEquals(List.of("tenant_id", "id"), reads);
  }
}
