package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned JSON snapshots contain wire values, never framework classes. */
public final class Payloads {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .addMixIn(ModelSnapshot.class, ModelSnapshotJson.class)
          .build();

  // Missing optional policy must retain the exact v2 canonical bytes/digest on old tasks.
  private abstract static class ModelSnapshotJson {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract ModelSnapshot.InputLimit inputLimit();
  }

  private Payloads() {}

  public static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid payload", e);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid snapshot", e);
    }
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
