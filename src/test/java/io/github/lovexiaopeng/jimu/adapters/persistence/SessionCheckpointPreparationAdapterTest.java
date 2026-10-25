package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SessionCheckpointPreparationAdapterTest {
  private final MapperSessionCheckpointPreparation storage =
      new MapperSessionCheckpointPreparation(
          new JdbcSessionRecords(new JdbcTemplate()), Map.of(), "step");

  @Test
  void decodingDoesNotReadNullMessages() {
    var wire = storage.decode("{\"requestBody\":\"body\",\"messages\":null}");
    assertEquals("body", wire.requestBody());
    assertThrows(NullPointerException.class, wire::messagesEmpty);
  }

  @Test
  void lastMessageAccessDoesNotTouchEarlierNullEntries() {
    var wire = storage.decode("{\"messages\":[null,{\"role\":\"user\",\"content\":\"中🧱\"}]}");
    assertEquals(false, wire.messagesEmpty());
    assertEquals("user", wire.lastRole());
    assertEquals("中🧱", wire.lastContent());
  }

  @Test
  void nullLastMessageFailsOnlyWhenAccessed() {
    var wire = storage.decode("{\"messages\":[null]}");
    assertEquals(false, wire.messagesEmpty());
    assertThrows(NullPointerException.class, wire::lastRole);
  }
}
