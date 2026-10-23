package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One strongest public event per source, sharing a whole-query linear matching budget. */
public final class PriorSessionMatcher {
  private final SessionSearchPort.Input input;
  private final Runnable deadline;
  private final java.util.function.LongConsumer queryOperations;
  private final int[] needle, prefix;
  private final Map<String, Hit> best = new java.util.HashMap<>();
  private long comparisons, operations;

  private record Hit(
      int count, int length, Instant time, String session, long seq, Map<String, Object> item) {}

  private static final Comparator<Hit> ORDER =
      Comparator.comparingInt(Hit::count)
          .reversed()
          .thenComparingInt(Hit::length)
          .thenComparing(Hit::time, Comparator.reverseOrder())
          .thenComparing(Hit::session)
          .thenComparing(Comparator.comparingLong(Hit::seq).reversed());

  public PriorSessionMatcher(SessionSearchPort.Input input, Runnable deadline) {
    this(input, deadline, count -> {});
  }

  public PriorSessionMatcher(
      SessionSearchPort.Input input,
      Runnable deadline,
      java.util.function.LongConsumer queryOperations) {
    this.queryOperations = queryOperations;
    this.input = input;
    this.deadline = deadline;
    needle = input.query().codePoints().toArray();
    prefix = new int[needle.length];
    for (int i = 1, matched = 0; i < needle.length; i++) {
      while (matched > 0 && !equal(needle[i], needle[matched])) matched = prefix[matched - 1];
      if (equal(needle[i], needle[matched])) matched++;
      prefix[i] = matched;
    }
  }

  private boolean equal(int a, int b) {
    if (++comparisons > 16777216) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    if ((comparisons & 1023) == 0) deadline.run();
    return a == b;
  }

  private void operation(long count) {
    operations += count;
    queryOperations.accept(count);
    if (operations > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    if ((operations & 1023) == 0 || count > 1023) deadline.run();
  }

  /** Provider must verify the full source event before applying these filters. */
  public void accept(JsonNode event, String digest, JsonNode source) {
    operation(1);
    String session = source.path("session_id").asText(), type = event.path("type").asText();
    long seq = event.path("seq").asLong(), upper = source.path("upper_seq").asLong();
    Instant created = SessionSearchContracts.time(source.path("created_at").asText()),
        time = SessionSearchContracts.time(event.path("time").asText());
    if (!input.sessionIds().isEmpty() && !input.sessionIds().contains(session)
        || input.createdFrom() != null && created.isBefore(input.createdFrom())
        || input.createdTo() != null && created.isAfter(input.createdTo())
        || seq < (input.seqFrom() == null ? 0 : input.seqFrom())
        || seq > Math.min(upper, input.seqTo() == null ? upper : input.seqTo())
        || input.timeFrom() != null && time.isBefore(input.timeFrom())
        || input.timeTo() != null && time.isAfter(input.timeTo())
        || !input.eventTypes().contains(type)
        || !(type.equals("user.message") || type.equals("model.response"))) return;
    String raw = event.path("data").path("content").textValue();
    int[] document =
        SessionSearchContracts.normalize(raw, () -> operation(1)).codePoints().toArray();
    operation(document.length);
    int first = -1, count = 0;
    for (int i = 0, matched = 0; i < document.length; i++) {
      while (matched > 0 && !equal(document[i], needle[matched])) matched = prefix[matched - 1];
      if (equal(document[i], needle[matched])) matched++;
      if (matched == needle.length) {
        if (first < 0) first = i + 1 - needle.length;
        count++;
        matched = 0;
      }
    }
    if (count == 0) return;
    int start = Math.max(0, first - 80), end = Math.min(document.length, start + 240);
    var hit = new LinkedHashMap<String, Object>();
    for (String key : List.of("seq", "type", "time", "source", "redacted"))
      hit.put(key, event.get(key));
    hit.put("event_sha256", digest);
    String snippet = new String(document, start, end - start);
    hit.put("snippet", snippet);
    hit.put("left_truncated", start > 0);
    hit.put("right_truncated", end < document.length);
    if (SessionContracts.bytes(Payloads.json(snippet)) > 1536
        || SessionContracts.bytes(Payloads.json(hit)) > 2048)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    var item = new LinkedHashMap<String, Object>();
    for (String key : List.of("session_id", "created_at", "history_version", "upper_seq"))
      item.put(key, source.get(key));
    item.put("best_match", hit);
    var candidate = new Hit(count, document.length, time, session, seq, item);
    var old = best.get(session);
    if (old == null || ORDER.compare(candidate, old) < 0) best.put(session, candidate);
    if (best.size() > 8) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
  }

  public String output(String caller, String scopeDigest) {
    operation(best.size());
    var sorted = new ArrayList<>(best.values());
    sorted.sort(ORDER);
    var value = new LinkedHashMap<String, Object>();
    value.put("version", 1);
    value.put("caller_session_id", caller);
    value.put("scope_sha256", scopeDigest);
    value.put("boundary", "scoped-admission");
    value.put("content_policy", "public-v1");
    value.put("match_policy", "public-content-literal-v1");
    value.put("ordering", "occurrences-desc,length-asc,time-desc,session-id-asc,seq-desc");
    value.put("returned_count", Math.min(4, sorted.size()));
    value.put("capped", sorted.size() > 4);
    value.put("items", sorted.stream().limit(4).map(Hit::item).toList());
    String raw = Payloads.json(value);
    if (SessionContracts.bytes(raw) > 16384) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    return raw;
  }
}
