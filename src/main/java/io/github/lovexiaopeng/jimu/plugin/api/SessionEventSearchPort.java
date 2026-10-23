package io.github.lovexiaopeng.jimu.plugin.api;

import java.time.Instant;
import java.util.List;

/** Read-only search bound by the host to an immutable caller, step and response slot. */
public interface SessionEventSearchPort {
  record Input(
      String query,
      String sessionId,
      Long seqFrom,
      Long seqTo,
      Instant timeFrom,
      Instant timeTo,
      List<String> eventTypes) {
    public Input {
      eventTypes = List.copyOf(eventTypes);
    }
  }

  String search(Input input);
}
