package io.github.lovexiaopeng.jimu.kernel.task.api;

/** Fixed metadata only. Never retain exception messages, URLs, headers or page bodies here. */
public record WebFetchObservation(Phase phase, Failure failure) {
  public enum Phase {
    ADMISSION,
    DNS,
    CONNECT_TLS,
    HEADERS,
    BODY,
    PROJECTION,
    REDIRECT
  }

  public enum Failure {
    NONE,
    TIMEOUT,
    DNS,
    TLS,
    IO,
    CANCELLED,
    POLICY,
    INTERNAL
  }
}
