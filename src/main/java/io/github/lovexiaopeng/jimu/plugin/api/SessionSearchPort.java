package io.github.lovexiaopeng.jimu.plugin.api;

import java.time.Instant;
import java.util.List;

/** Host-authorized prior-session query. IDs are filters, never authority. */
public interface SessionSearchPort {
  record Input(
      String query,
      List<String> sessionIds,
      Instant createdFrom,
      Instant createdTo,
      Long seqFrom,
      Long seqTo,
      Instant timeFrom,
      Instant timeTo,
      List<String> eventTypes) {
    public Input {
      sessionIds = List.copyOf(sessionIds);
      eventTypes = List.copyOf(eventTypes);
    }
  }

  String search(Input input);
}
