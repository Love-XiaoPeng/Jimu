package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned JSON snapshots contain wire values, never framework classes. */
public final class Payloads {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

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
