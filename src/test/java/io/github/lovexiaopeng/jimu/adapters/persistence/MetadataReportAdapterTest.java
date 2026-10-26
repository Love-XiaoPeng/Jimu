package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.AbstractMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataReportAdapterTest {
  @Test
  void constructorsAndTaskWrappingDoNotReadLedgerOrTask() {
    var task =
        new AbstractMap<String, Object>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("eager entries");
          }

          public Object get(Object key) {
            throw new AssertionError("eager " + key);
          }
        };
    assertDoesNotThrow(() -> new MapperTaskMetadataAccounting(null, task));
    assertDoesNotThrow(() -> new MapperReportContexts(null).wrap(task));
  }
}
