package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.time.LocalDateTime;

/** Lazy original rows; each operation is one existing IO or codec boundary. */
public interface SessionScopeCreationRecords {
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
