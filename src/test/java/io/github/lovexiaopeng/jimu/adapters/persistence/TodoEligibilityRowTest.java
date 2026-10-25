package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoEligibilityRowTest {
  @Test
  void constructorsDoNotReadTaskRowOrSession() {
    Map<String, Object> poison =
        new HashMap<>() {
          public Object get(Object key) {
            throw new AssertionError("early " + key);
          }
        };
    new MapperTodoEligibility(null, poison);
    new MapperTodoEligibility.Row(poison);
  }

  @Test
  void originalCastsAndNumberLongValueRemainLazy() {
    var values = new HashMap<String, Object>();
    var row = new MapperTodoEligibility.Row(values);
    assertNull(row.version());
    assertNull(row.digest());
    assertThrows(NullPointerException.class, row::epoch);
    values.put("plugin_version", 17);
    assertThrows(ClassCastException.class, row::version);
    values.put("plugin_digest", true);
    assertThrows(ClassCastException.class, row::digest);
    values.put("epoch", "1");
    assertThrows(ClassCastException.class, row::epoch);
    values.put("epoch", new BigDecimal("-2.9"));
    assertEquals(-2, row.epoch());
    values.put("epoch", Long.MIN_VALUE);
    assertEquals(Long.MIN_VALUE, row.epoch());
  }
}
