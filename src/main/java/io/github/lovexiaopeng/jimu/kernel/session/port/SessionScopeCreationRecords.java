package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.time.LocalDateTime;

/** Lazy original rows; each operation is one existing IO or codec boundary. */
public interface SessionScopeCreationRecords {
  /** 原位置每次独立UTF8编码；适配器可在相同query中预留并计量。 */
  default int utf8Bytes(String raw) {
    return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  /** One local raw consumption; encoded workspace remains owned through use. */
  default <T> T withRaw(String raw, java.util.function.BiFunction<Integer, String, T> use) {
    return use.apply(utf8Bytes(raw), hash(raw));
  }

  interface Row {
    String tenant();

    String id();

    String owner();

    String tools();

    LocalDateTime created();

    LocalDateTime retain();

    String binding();

    long nextSeq();
  }

  Row source(Row caller, String id);

  Row history(Row source);

  String binding();

  default int version(Row caller) {
    return 4;
  }

  default String binding(Row caller) {
    return binding();
  }

  default void validateAuthority(Row caller, String raw) {
    validateAuthority(raw);
  }

  String hash(String value);

  String json(Object value);

  void validateAuthority(String raw);

  void body(Row caller, long bytes);

  void insertAuthority(
      String tenant,
      String id,
      String owner,
      String binding,
      String tools,
      String raw,
      String digest,
      int bytes,
      int count,
      LocalDateTime created,
      LocalDateTime retain,
      LocalDateTime validation);

  void insertAuthorityTarget(
      String tenant, String id, int ordinal, Object source, String raw, String digest, int bytes);

  void retainAuthorityHistory(LocalDateTime retain, String tenant, String id);
}
