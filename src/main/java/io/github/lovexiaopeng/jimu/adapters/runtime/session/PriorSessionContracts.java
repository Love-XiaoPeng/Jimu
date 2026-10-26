package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.PriorSessionPolicyV4;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** ADR-0014 byte-pinned v4 contracts and strict untrusted input. */
public final class PriorSessionContracts {
  public static final String NAME = ToolCapability.SESSION_SEARCH;
  private final SessionContracts history = new SessionContracts(4);
  private final SchemaContract input, output, admission, authority, refs;
  private final ToolDescriptor descriptor;
  private final String schema, description;

  public PriorSessionContracts() {
    try {
      var catalog =
          history.json(
              new String(SessionContracts.resource("v4/catalog-v4.json"), StandardCharsets.UTF_8),
              8192);
      var entry = catalog.path("tools").get(1);
      String root = "v4/session_search-0.1.0/";
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
      schema = new String(in, StandardCharsets.UTF_8);
      input = new SchemaContract(in);
      output = new SchemaContract(out);
      admission = contract(catalog, "admission-input.schema.json");
      authority = contract(catalog, "authority-scope.schema.json");
      refs = contract(catalog, "read-refs.schema.json");
      descriptor =
          new ToolDescriptor(
              "jimu.session.search",
              "0.1.0",
              NAME,
              "session/" + root + "input.schema.json",
              "session/" + root + "output.schema.json",
              Payloads.hash(out),
              "io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionSearchPlugin");
    } catch (IOException failure) {
      throw new IllegalStateException("Packaged prior session contract unavailable", failure);
    }
  }

  private static SchemaContract contract(JsonNode catalog, String name) throws IOException {
    return new SchemaContract(
        SessionContracts.checked("v4/" + name, catalog.path("schemas").path(name).asText()));
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

  public static String utf8(byte[] raw) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(raw))
          .toString();
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw invalid();
    }
  }

  /** Check original numeric tokens before a tree can normalize -0 or exponent syntax. */
  public static void decimals(String raw) {
    try (var parser = new JsonFactory().createParser(raw)) {
      while (parser.nextToken() != null) {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) throw invalid();
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT
            && !parser.getText().matches("0|[1-9][0-9]*")) throw invalid();
      }
    } catch (IOException failure) {
      throw invalid();
    }
  }

  public record Admission(String goal, List<String> historySessionIds) {
    public Admission {
      historySessionIds = List.copyOf(historySessionIds);
    }
  }

  public Admission admission(byte[] raw) {
    try {
      var value = history.strictJson(utf8(raw), PriorSessionPolicyV4.ADMISSION_BYTES);
      admission.validate(value);
      String goal = value.path("goal").textValue();
      if (SessionContracts.bytes(goal) > PriorSessionPolicyV4.GOAL_UTF8_BYTES) throw invalid();
      return new Admission(
          goal, strings(value.path("history_session_ids")).stream().sorted().toList());
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  public SessionSearchPort.Input input(byte[] raw) {
    return input(utf8(raw));
  }

  public SessionSearchPort.Input input(String raw) {
    try {
      var value = history.strictJson(raw, PriorSessionPolicyV4.INPUT_BYTES);
      decimals(raw);
      input.validate(value);
      String query = value.path("query").textValue();
      checkQuery(query);
      query = SessionSearchContracts.normalize(query);
      checkQuery(query);
      Long from = value.has("event_seq_from") ? value.path("event_seq_from").longValue() : null;
      Long to = value.has("event_seq_to") ? value.path("event_seq_to").longValue() : null;
      Instant createdFrom = time(value, "created_at_from"),
          createdTo = time(value, "created_at_to"),
          timeFrom = time(value, "event_time_from"),
          timeTo = time(value, "event_time_to");
      if (from != null && to != null && from > to
          || createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)
          || timeFrom != null && timeTo != null && timeFrom.isAfter(timeTo)) throw invalid();
      for (String field :
          List.of("parent_session_ids", "include_root_sessions", "availability", "event_surfaces"))
        if (value.has(field))
          throw new TaskFailure(400, SessionErrorCode.SESSION_SEARCH_FILTER_UNSUPPORTED);
      return new SessionSearchPort.Input(
          query,
          strings(value.path("session_ids")).stream().sorted().toList(),
          createdFrom,
          createdTo,
          from,
          to,
          timeFrom,
          timeTo,
          SessionSearchContracts.TYPES.stream()
              .filter(
                  type ->
                      !value.has("event_types")
                          || strings(value.path("event_types")).contains(type))
              .toList());
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private static Instant time(JsonNode value, String key) {
    return value.has(key) ? SessionSearchContracts.time(value.path(key).textValue()) : null;
  }

  private static List<String> strings(JsonNode value) {
    var result = new ArrayList<String>();
    for (var item : value) result.add(item.textValue());
    return result;
  }

  private static void checkQuery(String value) {
    int count = value.codePointCount(0, value.length());
    if (count < 1
        || count > PriorSessionPolicyV4.QUERY_CODEPOINTS
        || SessionContracts.bytes(value) > PriorSessionPolicyV4.QUERY_UTF8_BYTES
        || value.indexOf(0) >= 0) throw invalid();
  }

  private static TaskFailure invalid() {
    return new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
  }

  public void output(String raw) {
    validate(output, raw, PriorSessionPolicyV4.OUTPUT_BYTES);
  }

  public void authority(String raw) {
    validate(authority, raw, PriorSessionPolicyV4.AUTHORITY_BYTES);
  }

  public void refs(String raw) {
    validate(refs, raw, PriorSessionPolicyV4.REFS_BYTES);
  }

  private void validate(SchemaContract contract, String raw, int max) {
    if (SessionContracts.bytes(raw) > max)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    try {
      contract.validate(history.json(raw, max));
    } catch (IllegalArgumentException | TaskFailure failure) {
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    }
  }
}
