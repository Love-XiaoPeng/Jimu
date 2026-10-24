package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 原槽保留字节证明，跨 JVM 恢复不依赖 Map.of 的 JSON 字段顺序。 */
class ScopeReadNormalizationTest {
  private final JdbcSessionRecords records = new JdbcSessionRecords(new JdbcTemplate());
  private static final String STORED =
      "{\"seq\":2,\"before\":1,\"after\":1,\"upper_seq\":3,\"target_session_id\":\"source\"}";
  private static final String REORDERED =
      "{\"after\":1,\"target_session_id\":\"source\",\"seq\":2,\"upper_seq\":3,\"before\":1}";

  private Map<String, Object> binding() {
    return new HashMap<>(
        Map.of(
            "normalized_snapshot", STORED,
            "normalized_digest", Payloads.hash(STORED),
            "normalized_bytes", SessionContracts.bytes(STORED)));
  }

  @Test
  void reorderedFieldsPreserveOriginalSnapshotAndProof() {
    var binding = binding();
    assertDoesNotThrow(() -> records.validateNormalizedScope(REORDERED, binding));
    assertEquals(STORED, binding.get("normalized_snapshot"));
    assertEquals(Payloads.hash(STORED), binding.get("normalized_digest"));
  }

  @Test
  void changedCutOrSourceCannotReuseOriginalBinding() {
    for (String changed :
        new String[] {
          REORDERED.replace("\"seq\":2", "\"seq\":1"), REORDERED.replace("source", "other")
        })
      assertEquals(
          "SESSION_HISTORY_CORRUPT",
          assertThrows(TaskFailure.class, () -> records.validateNormalizedScope(changed, binding()))
              .code());
  }

  @Test
  void semanticEqualityDoesNotReplaceExactDigestAndByteChecks() {
    var digest = binding();
    digest.put("normalized_digest", Payloads.hash(REORDERED));
    var bytes = binding();
    bytes.put("normalized_bytes", SessionContracts.bytes(STORED) + 1);
    for (var corrupt : java.util.List.of(digest, bytes))
      assertEquals(
          "SESSION_HISTORY_CORRUPT",
          assertThrows(TaskFailure.class, () -> records.validateNormalizedScope(REORDERED, corrupt))
              .code());
  }
}
