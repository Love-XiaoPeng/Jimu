package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;

/** ADR-0012 strict input and byte-pinned search contracts; no user expression evaluation. */
public final class SessionSearchContracts {
  public static final String NAME = "session_event_search";
  public static final List<String> TYPES =
      List.of("user.message", "step.started", "model.response", "session.read", "turn.ended");
  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, true)
          .optionalEnd()
          .appendOffsetId()
          .toFormatter(java.util.Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private final SessionContracts history = new SessionContracts(2);
  private final SchemaContract input, output;
  private final ToolDescriptor descriptor;
  private final String schema, description;

  public SessionSearchContracts() {
    try {
      var catalog =
          history.json(
              new String(SessionContracts.resource("v2/catalog-v2.json"), StandardCharsets.UTF_8),
              8192);
      var entry = catalog.path("tools").get(1);
      String root = "v2/session_event_search-0.1.0/";
      byte[] in =
          SessionContracts.checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] out =
          SessionContracts.checked(
              root + "output.schema.json", entry.path("outputSha256").asText());
      SessionContracts.checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      description =
          new String(
              SessionContracts.checked(
                  root + "description.txt", entry.path("descriptionSha256").asText()),
              StandardCharsets.UTF_8);
      input = new SchemaContract(in);
      output = new SchemaContract(out);
      schema = new String(in, StandardCharsets.UTF_8);
      descriptor =
          new ToolDescriptor(
              "jimu.session.event_search",
              "0.1.0",
              NAME,
              "session/" + root + "input.schema.json",
              "session/" + root + "output.schema.json",
              Payloads.hash(out),
              "io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventSearchPlugin");
    } catch (IOException e) {
      throw new IllegalStateException("Packaged search contract unavailable", e);
    }
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public String inputSchema() {
    return schema;
  }

  public String description() {
    return description;
  }

  public SessionEventSearchPort.Input input(byte[] raw) {
    try {
      return input(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString());
    } catch (java.nio.charset.CharacterCodingException e) {
      throw invalid();
    }
  }

  public SessionEventSearchPort.Input input(String raw) {
    try {
      var node = history.strictJson(raw, 2048);
      for (String key : List.of("seq_from", "seq_to"))
        if (node.has(key) && !node.path(key).isIntegralNumber()) throw invalid();
      input.validate(node);
      String query = node.path("query").textValue();
      checkQuery(query);
      query = normalize(query);
      checkQuery(query);
      Long from = node.has("seq_from") ? node.path("seq_from").longValue() : null;
      Long to = node.has("seq_to") ? node.path("seq_to").longValue() : null;
      if (from != null && to != null && from > to) throw invalid();
      Instant timeFrom = node.has("time_from") ? time(node.path("time_from").textValue()) : null;
      Instant timeTo = node.has("time_to") ? time(node.path("time_to").textValue()) : null;
      if (timeFrom != null && timeTo != null && timeFrom.isAfter(timeTo)) throw invalid();
      var selected =
          TYPES.stream()
              .filter(type -> !node.has("event_types") || contains(node.path("event_types"), type))
              .toList();
      if (node.has("surfaces")) throw new TaskFailure(400, "SESSION_SEARCH_FILTER_UNSUPPORTED");
      return new SessionEventSearchPort.Input(
          query,
          node.has("session_id") ? node.path("session_id").textValue() : null,
          from,
          to,
          timeFrom,
          timeTo,
          selected);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static boolean contains(JsonNode array, String value) {
    for (var element : array) if (element.asText().equals(value)) return true;
    return false;
  }

  private static void checkQuery(String value) {
    int count = value.codePointCount(0, value.length());
    if (count < 1 || count > 256 || SessionContracts.bytes(value) > 1024 || value.indexOf(0) >= 0)
      throw invalid();
  }

  private static TaskFailure invalid() {
    return new TaskFailure(400, "TOOL_INPUT_INVALID");
  }

  public static Instant time(String value) {
    try {
      if (!value.matches(
          "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})"))
        throw invalid();
      var parsed = OffsetDateTime.parse(value, TIME);
      int year = parsed.getYear(), utcYear = parsed.withOffsetSameInstant(ZoneOffset.UTC).getYear();
      if (year < 1 || year > 9999 || utcYear < 1 || utcYear > 9999) throw invalid();
      return parsed.toInstant();
    } catch (java.time.DateTimeException e) {
      throw invalid();
    }
  }

  public String normalized(SessionEventSearchPort.Input value, String task, long upper) {
    if (value.sessionId() != null && !task.equals(value.sessionId()))
      throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var node = new LinkedHashMap<String, Object>();
    node.put("query", value.query());
    node.put("session_id", task);
    node.put("seq_from", value.seqFrom() == null ? 0 : value.seqFrom());
    node.put("seq_to", value.seqTo() == null ? upper : value.seqTo());
    node.put("time_from", value.timeFrom() == null ? null : UTC.format(value.timeFrom()));
    node.put("time_to", value.timeTo() == null ? null : UTC.format(value.timeTo()));
    node.put("event_types", value.eventTypes());
    String raw = Payloads.json(node);
    if (SessionContracts.bytes(raw) > 2048) throw invalid();
    return raw;
  }

  public void output(String raw) {
    if (SessionContracts.bytes(raw) > 32768) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    try {
      output.validate(history.json(raw, 32768));
    } catch (IllegalArgumentException | TaskFailure e) {
      throw new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
    }
  }

  public static String normalize(String raw) {
    var result = new StringBuilder();
    boolean space = false;
    for (int offset = 0; offset < raw.length(); ) {
      int c = raw.codePointAt(offset);
      offset += Character.charCount(c);
      if (white(c)) space = !result.isEmpty();
      else {
        if (space) result.append(' ');
        result.appendCodePoint(c);
        space = false;
      }
    }
    return result.toString();
  }

  private static boolean white(int c) {
    return c >= 9 && c <= 13
        || c == 0x20
        || c == 0xa0
        || c == 0x1680
        || c >= 0x2000 && c <= 0x200a
        || c == 0x2028
        || c == 0x2029
        || c == 0x202f
        || c == 0x205f
        || c == 0x3000
        || c == 0xfeff;
  }
}
