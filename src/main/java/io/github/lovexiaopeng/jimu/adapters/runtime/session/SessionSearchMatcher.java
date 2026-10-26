package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionEventSearchPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Linear, non-overlapping code point matching with one shared comparison budget. */
public final class SessionSearchMatcher {
  private final SessionEventSearchPort.Input input;
  private final Runnable deadline;
  private final int[] needle, prefix;
  private final List<Hit> matches = new ArrayList<>();
  private long comparisons;

  private record Hit(
      int occurrences, int length, Instant time, long seq, Map<String, Object> value) {}

  public SessionSearchMatcher(SessionEventSearchPort.Input input, Runnable deadline) {
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
    if (++comparisons > SessionEventSearchPolicyV1.COMPARISONS)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    if ((comparisons & SessionEventSearchPolicyV1.DEADLINE_CHECK_MASK) == 0) deadline.run();
    return a == b;
  }

  /** Call only after the provider verifies this event and its original source. */
  public void accept(JsonNode event, String digest, long upper) {
    deadline.run();
    long seq = event.path("seq").longValue();
    String type = event.path("type").textValue();
    Instant time = SessionSearchContracts.time(event.path("time").textValue());
    if (seq < (input.seqFrom() == null ? 0 : input.seqFrom())
        || seq > Math.min(upper, input.seqTo() == null ? upper : input.seqTo())
        || !input.eventTypes().contains(type)
        || input.timeFrom() != null && time.isBefore(input.timeFrom())
        || input.timeTo() != null && time.isAfter(input.timeTo())
        || !(type.equals("user.message") || type.equals("model.response"))) return;
    int[] document =
        SessionSearchContracts.normalize(event.path("data").path("content").textValue())
            .codePoints()
            .toArray();
    int first = -1, occurrences = 0;
    for (int i = 0, matched = 0; i < document.length; i++) {
      while (matched > 0 && !equal(document[i], needle[matched])) matched = prefix[matched - 1];
      if (equal(document[i], needle[matched])) matched++;
      if (matched == needle.length) {
        if (first < 0) first = i + 1 - needle.length;
        occurrences++;
        matched = 0;
      }
    }
    if (occurrences == 0) return;
    int start = Math.max(0, first - SessionEventSearchPolicyV1.SNIPPET_BEFORE_CODEPOINTS),
        end =
            Math.min(document.length, start + SessionEventSearchPolicyV1.SNIPPET_WINDOW_CODEPOINTS);
    String snippet = new String(document, start, end - start);
    if (SessionContracts.bytes(Payloads.json(snippet))
        > SessionEventSearchPolicyV1.SNIPPET_JSON_BYTES)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    var hit = new LinkedHashMap<String, Object>();
    for (String key : List.of("seq", "type", "time", "source", "redacted"))
      hit.put(key, event.get(key));
    hit.put("event_sha256", digest);
    hit.put("snippet", snippet);
    hit.put("left_truncated", start > 0);
    hit.put("right_truncated", end < document.length);
    if (SessionContracts.bytes(Payloads.json(hit)) > SessionEventSearchPolicyV1.HIT_JSON_BYTES
        || matches.size() >= SessionEventSearchPolicyV1.CANDIDATES)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    matches.add(new Hit(occurrences, document.length, time, seq, hit));
  }

  public String output(String task, long upper, String boundary) {
    return output(task, upper, boundary, SessionEventSearchPolicyV1.DEFAULT_HISTORY_VERSION);
  }

  public String output(String task, long upper, String boundary, int historyVersion) {
    deadline.run();
    matches.sort(
        Comparator.comparingInt(Hit::occurrences)
            .reversed()
            .thenComparingInt(Hit::length)
            .thenComparing(Hit::time, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingLong(Hit::seq).reversed()));
    var value = new LinkedHashMap<String, Object>();
    value.put("version", SessionEventSearchPolicyV1.OUTPUT_VERSION);
    value.put("session_id", task);
    value.put("history_version", historyVersion);
    value.put("content_policy", SessionEventSearchPolicyV1.CONTENT_POLICY);
    value.put("boundary", boundary);
    value.put("upper_seq", upper);
    value.put("match_policy", SessionEventSearchPolicyV1.MATCH_POLICY);
    value.put("ordering", SessionEventSearchPolicyV1.ORDERING);
    value.put("returned_count", Math.min(SessionEventSearchPolicyV1.RETURNED_HITS, matches.size()));
    value.put("capped", matches.size() > SessionEventSearchPolicyV1.RETURNED_HITS);
    value.put(
        "items",
        matches.stream().limit(SessionEventSearchPolicyV1.RETURNED_HITS).map(Hit::value).toList());
    return Payloads.json(value);
  }
}
